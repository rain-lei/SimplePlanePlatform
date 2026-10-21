"""Verify duplicate external whole-chain providers fall back or fail under strict mode."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
run = root / '.build/plugin-conflict-verification' / str(uuid.uuid4())
(run / 'plugins').mkdir(parents=True)
provider = root / 'examples/custom-chain-plugin/target/custom-chain-plugin-0.1.0-SNAPSHOT.jar'
second = root / 'examples/duplicate-chain-plugin/target/duplicate-chain-plugin-0.1.0-SNAPSHOT.jar'
shutil.copy2(provider, run / 'plugins/chain-a.jar')
shutil.copy2(second, run / 'plugins/chain-b.jar')
config = run / 'config.yaml'
config.write_text(f'''schemaVersion: 1
application: conflict-check
agent:
  pluginsDirectory: ./plugins
web:
  enabled: false
output:
  directory: {(run / 'logs').as_posix()}
''', encoding='utf-8')
java = os.environ.get('LOGLENS_JAVA', 'D:/IntelliJ IDEA 2024.3.2/jbr/bin/java.exe')
repo = root / '.build/repository'
classpath = ';'.join(str(path) for path in [root / 'examples/logback-demo/target/classes',
    repo / 'ch/qos/logback/logback-classic/1.5.18/logback-classic-1.5.18.jar',
    repo / 'ch/qos/logback/logback-core/1.5.18/logback-core-1.5.18.jar',
    repo / 'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar'])
base = [java, f'-Djava.io.tmpdir={root / ".build/tmp"}', f'-Duser.home={root / ".build/user"}']
fallback = subprocess.run(base + [f'-javaagent:{root / "dist/loglens-agent.jar"}=config={config}',
    '-cp', classpath, 'com.example.LogbackDemo'], cwd=root, capture_output=True, text=True,
    encoding='utf-8', errors='replace', timeout=60)
(run / 'fallback-stdout.txt').write_text(fallback.stdout, encoding='utf-8')
(run / 'fallback-stderr.txt').write_text(fallback.stderr, encoding='utf-8')
if fallback.returncode != 0 or 'multiple LogChainProviders' not in fallback.stderr or 'orderId=O-1' not in fallback.stdout:
    raise SystemExit(f'non-strict conflict did not preserve application logging: {fallback.stderr}')
if (run / 'logs').exists(): raise SystemExit('conflicting plugins must fail before creating a session')
strict = subprocess.run(base + [f'-javaagent:{root / "dist/loglens-agent.jar"}=strict=true,config={config}',
    '-cp', classpath, 'com.example.LogbackDemo'], cwd=root, capture_output=True, text=True,
    encoding='utf-8', errors='replace', timeout=60)
(run / 'strict-stdout.txt').write_text(strict.stdout, encoding='utf-8')
(run / 'strict-stderr.txt').write_text(strict.stderr, encoding='utf-8')
if strict.returncode == 0 or 'multiple LogChainProviders' not in strict.stderr or 'orderId=O-1' in strict.stdout:
    raise SystemExit('strict mode did not stop startup on provider conflict')
result = {'nonStrictExitCode': fallback.returncode, 'nonStrictDiagnostic': 'multiple LogChainProviders' in fallback.stderr,
          'businessLogPreserved': 'orderId=O-1' in fallback.stdout, 'strictExitCode': strict.returncode,
          'strictDiagnostic': 'multiple LogChainProviders' in strict.stderr, 'strictApplicationStarted': 'orderId=O-1' in strict.stdout}
(run / 'results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(f'External chain conflict behavior verified: {run}')
