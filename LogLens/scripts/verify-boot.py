"""Boot nested-loader + external whole-chain plugin acceptance with a unique evidence directory."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
run = root / '.build/boot-verification' / str(uuid.uuid4())
(run / 'plugins').mkdir(parents=True)
shutil.copy2(root / 'examples/custom-chain-plugin/target/custom-chain-plugin-0.1.0-SNAPSHOT.jar', run / 'plugins')
config = run / 'config.yaml'
config.write_text('''schemaVersion: 1
application: boot-check
agent:
  pluginsDirectory: ./plugins
capture:
  includeLoggers: [com.example]
web:
  enabled: false
redaction:
  messageRules:
    - id: token
      pattern: '(token=)[^\\s,]+'
      replacement: '$1***'
''', encoding='utf-8')
command = [os.environ.get('LOGLENS_JAVA', 'D:/IntelliJ IDEA 2024.3.2/jbr/bin/java.exe'),
           f'-Djava.io.tmpdir={root / ".build/tmp"}', f'-Duser.home={root / ".build/user"}',
           f'-javaagent:{root / "dist/loglens-agent.jar"}=config={config}', '-jar',
           str(root / 'examples/springboot-demo/target/springboot-demo-0.1.0-SNAPSHOT.jar')]
process = subprocess.run(command, cwd=root, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=40)
(run / 'stdout.txt').write_text(process.stdout, encoding='utf-8')
(run / 'stderr.txt').write_text(process.stderr, encoding='utf-8')
assert process.returncode == 0, process.stderr
sessions = list((run / 'logs/boot-check').iterdir())
assert len(sessions) == 1
session = sessions[0]
captured = [json.loads(line) for file in session.glob('captured-*.jsonl') for line in file.read_text(encoding='utf-8').splitlines()]
processed = [json.loads(line) for file in session.glob('processed-*.jsonl') for line in file.read_text(encoding='utf-8').splitlines()]
assert len([r for r in captured if 'orderId=BOOT-' in r['message']]) == 2
assert {r['fields'].get('orderId') for r in processed if 'orderId' in r['fields']} == {'BOOT-1', 'BOOT-2'}
assert 'private' not in json.dumps(captured)
assert 'private' not in json.dumps(processed)
assert json.loads((session / 'session.json').read_text(encoding='utf-8'))['complete'] is True
(run / 'results.json').write_text(json.dumps({'captured': len(captured), 'processed': len(processed), 'bootOrders': 2, 'externalChain': True}), encoding='utf-8')
print(f'Boot nested JAR and external chain verified: {run}')
