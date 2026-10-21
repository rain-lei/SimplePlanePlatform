"""Run a packaged Agent with the external OrderTagFactory JAR and assert its output."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
run = root / '.build/plugin-factory-verification' / str(uuid.uuid4())
(run / 'plugins').mkdir(parents=True)
plugin = root / 'examples/custom-plugin/target/custom-plugin-0.1.0-SNAPSHOT.jar'
shutil.copy2(plugin, run / 'plugins' / plugin.name)
config = run / 'config.yaml'
config.write_text(f'''schemaVersion: 1
application: factory-check
agent:
  pluginsDirectory: ./plugins
web:
  enabled: false
redaction:
  messageRules:
    - id: token
      pattern: '(token=)[^ ]+'
      replacement: '$1***'
pipeline:
  - id: extract
    provider: builtin-field-extract
    config:
      rules:
        - field: orderId
          pattern: 'orderId=(?<value>[^ ]+)'
  - id: external-order-tag
    provider: example-order-tag
    config:
      tag: external-order
output:
  directory: {(run / 'logs').as_posix()}
''', encoding='utf-8')
logback = run / 'logback.xml'
logback.write_text('''<configuration>
<appender name="NOP" class="ch.qos.logback.core.helpers.NOPAppender"/>
<root level="INFO"><appender-ref ref="NOP"/></root>
</configuration>''', encoding='utf-8')
java = os.environ.get('LOGLENS_JAVA', 'D:/IntelliJ IDEA 2024.3.2/jbr/bin/java.exe')
repo = root / '.build/repository'
classpath = ';'.join(str(path) for path in [root / 'examples/logback-demo/target/classes',
    repo / 'ch/qos/logback/logback-classic/1.5.18/logback-classic-1.5.18.jar',
    repo / 'ch/qos/logback/logback-core/1.5.18/logback-core-1.5.18.jar',
    repo / 'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar'])
process = subprocess.run([java, f'-Djava.io.tmpdir={root / ".build/tmp"}', f'-Duser.home={root / ".build/user"}',
    f'-Dlogback.configurationFile={logback}', f'-javaagent:{root / "dist/loglens-agent.jar"}=config={config}',
    '-cp', classpath, 'com.example.LogbackDemo'], cwd=root, capture_output=True, text=True,
    encoding='utf-8', errors='replace', timeout=60)
(run / 'stdout.txt').write_text(process.stdout, encoding='utf-8')
(run / 'stderr.txt').write_text(process.stderr, encoding='utf-8')
if process.returncode: raise SystemExit(process.stderr)
sessions = list((run / 'logs/factory-check').iterdir())
if len(sessions) != 1: raise SystemExit(f'expected one session, found {len(sessions)}')
session = sessions[0]
captured = [json.loads(line) for file in session.glob('captured-*.jsonl') for line in file.read_text(encoding='utf-8').splitlines()]
processed = [json.loads(line) for file in session.glob('processed-*.jsonl') for line in file.read_text(encoding='utf-8').splitlines()]
tagged = [record for record in processed if 'external-order' in record['tags']]
if len(captured) != 3 or len(processed) != 3 or len(tagged) != 2:
    raise SystemExit(f'unexpected counts: captured={len(captured)} processed={len(processed)} tagged={len(tagged)}')
if {record['fields']['orderId'] for record in tagged} != {'O-1', 'O-2'}:
    raise SystemExit('external factory did not receive extracted order fields')
if 'private' in json.dumps(captured + processed): raise SystemExit('sensitive value escaped redaction')
if json.loads((session / 'session.json').read_text(encoding='utf-8'))['complete'] is not True:
    raise SystemExit('session did not complete cleanly')
result = {'captured': len(captured), 'processed': len(processed), 'factoryTagged': len(tagged),
          'orderIds': sorted(record['fields']['orderId'] for record in tagged), 'complete': True}
(run / 'results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(f'External node factory verified: {run}')
