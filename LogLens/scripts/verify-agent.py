"""Repeatable packaged-agent smoke checks. Every generated file stays in the workspace."""
from pathlib import Path
import json
import os
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get('LOGLENS_JAVA', 'D:/IntelliJ IDEA 2024.3.2/jbr/bin/java.exe'))
repo = ROOT / '.build/repository'
classpath = ';'.join(str(p) for p in [
    ROOT / 'examples/logback-demo/target/classes',
    repo / 'ch/qos/logback/logback-classic/1.5.18/logback-classic-1.5.18.jar',
    repo / 'ch/qos/logback/logback-core/1.5.18/logback-core-1.5.18.jar',
    repo / 'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar',
])
run = ROOT / '.build/agent-verification' / str(uuid.uuid4())
run.mkdir(parents=True)
results = []
for mode in ['observe', 'replace-console']:
    case = run / mode
    case.mkdir()
    config = case / 'config.yaml'
    config.write_text(f'''schemaVersion: 1
application: verify
agent:
  mode: {mode}
  consoleAppenderNames: [CONSOLE]
web:
  enabled: false
redaction:
  messageRules:
    - id: token
      pattern: '(token=)[^\\s,]+'
      replacement: '$1***'
''', encoding='utf-8')
    original = case / 'original.log'
    child_output = case / 'child.log'
    logback = case / 'logback.xml'
    logback.write_text(f'''<configuration>
<turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter"><Marker>BLOCKED</Marker><OnMatch>DENY</OnMatch><OnMismatch>NEUTRAL</OnMismatch></turboFilter>
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %msg%n</pattern></encoder></appender>
<appender name="FILE" class="ch.qos.logback.core.FileAppender"><file>{original.as_posix()}</file><encoder><pattern>%level %msg%n</pattern></encoder></appender>
<appender name="CHILD" class="ch.qos.logback.core.FileAppender"><file>{child_output.as_posix()}</file><encoder><pattern>%level %msg%n</pattern></encoder></appender>
<logger name="com.example" level="INFO" additivity="true"><appender-ref ref="CHILD"/></logger>
<root level="INFO"><appender-ref ref="CONSOLE"/><appender-ref ref="FILE"/></root>
</configuration>''', encoding='utf-8')
    command = [str(JAVA), f'-Djava.io.tmpdir={ROOT / ".build/tmp"}', f'-Duser.home={ROOT / ".build/user"}',
               '-Ddemo.reset=true',
               '-Ddemo.turbo=true',
               f'-Dlogback.configurationFile={logback}', f'-javaagent:{ROOT / "dist/loglens-agent.jar"}=config={config}',
               '-cp', classpath, 'com.example.LogbackDemo']
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
    (case / 'stdout.txt').write_text(process.stdout, encoding='utf-8')
    (case / 'stderr.txt').write_text(process.stderr, encoding='utf-8')
    assert process.returncode == 0, process.stderr
    sessions = list((case / 'logs/verify').iterdir())
    assert len(sessions) == 1
    session = sessions[0]
    captured = [json.loads(line) for p in session.glob('captured-*.jsonl') for line in p.read_text(encoding='utf-8').splitlines()]
    processed = [json.loads(line) for p in session.glob('processed-*.jsonl') for line in p.read_text(encoding='utf-8').splitlines()]
    metadata = json.loads((session / 'session.json').read_text(encoding='utf-8'))
    assert len(captured) == 4, 'Multiple appenders/reset must not duplicate or omit captures'
    assert not any(r['message'] == 'disabled-debug-probe' for r in captured), 'Agent must not enable framework-disabled DEBUG'
    assert not any(r['message'] == 'turbo-filtered-probe' for r in captured), 'TurboFilter-denied event must never reach capture'
    assert len(processed) == 3
    assert sorted(r['repeatCount'] for r in processed) == [1, 1, 2]
    assert any(r['message'] == 'after-reset-probe' for r in captured)
    assert metadata['complete'] is True
    assert 'private' not in json.dumps(captured)
    assert 'private' not in json.dumps(processed)
    assert original.read_text(encoding='utf-8').count('token=private') == 2, 'Original file appender must survive'
    assert child_output.read_text(encoding='utf-8').count('token=private') == 2, 'Additive child appender must survive'
    assert 'turbo-filtered-probe' not in original.read_text(encoding='utf-8') + child_output.read_text(encoding='utf-8')
    assert 'after-reset-probe' in original.read_text(encoding='utf-8'), 'Rebuilt file appender must survive'
    assert ('after-reset-probe' in process.stdout) == (mode == 'observe'), 'Rebuilt console takeover behavior'
    assert ('token=private' in process.stdout) == (mode == 'observe'), 'Console takeover behavior'
    results.append({'mode': mode, 'captured': len(captured), 'processed': len(processed), 'complete': metadata['complete'],
                    'turboFilterDenied': True, 'additiveChildFilePreserved': True})
async_case = run / 'async-console-fallback'
async_case.mkdir()
async_config = async_case / 'config.yaml'
async_config.write_text(f'''schemaVersion: 1
application: async-check
agent:
  mode: replace-console
  consoleAppenderNames: [CONSOLE]
web:
  enabled: false
redaction:
  messageRules:
    - id: token
      pattern: '(token=)[^ ]+'
      replacement: '$1***'
output:
  directory: {(async_case / 'logs').as_posix()}
''', encoding='utf-8')
async_original = async_case / 'original.log'
async_logback = async_case / 'logback.xml'
async_logback.write_text(f'''<configuration>
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %msg%n</pattern></encoder></appender>
<appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender"><queueSize>32</queueSize><neverBlock>true</neverBlock><appender-ref ref="CONSOLE"/></appender>
<appender name="FILE" class="ch.qos.logback.core.FileAppender"><file>{async_original.as_posix()}</file><encoder><pattern>%level %msg%n</pattern></encoder></appender>
<root level="INFO"><appender-ref ref="ASYNC"/><appender-ref ref="FILE"/></root>
</configuration>''', encoding='utf-8')
async_process = subprocess.run([str(JAVA), f'-Djava.io.tmpdir={ROOT / ".build/tmp"}', f'-Duser.home={ROOT / ".build/user"}',
    '-Ddemo.async=true', f'-Dlogback.configurationFile={async_logback}',
    f'-javaagent:{ROOT / "dist/loglens-agent.jar"}=config={async_config}', '-cp', classpath,
    'com.example.LogbackDemo'], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
(async_case / 'stdout.txt').write_text(async_process.stdout, encoding='utf-8')
(async_case / 'stderr.txt').write_text(async_process.stderr, encoding='utf-8')
assert async_process.returncode == 0, async_process.stderr
async_sessions = list((async_case / 'logs/async-check').iterdir())
assert len(async_sessions) == 1
async_session = async_sessions[0]
async_captured = [json.loads(line) for file in async_session.glob('captured-*.jsonl') for line in file.read_text(encoding='utf-8').splitlines()]
async_metadata = json.loads((async_session / 'session.json').read_text(encoding='utf-8'))
assert len(async_captured) == 3 and async_metadata['complete'] is True
assert 'UNSUPPORTED_CONSOLE_WRAPPER' in (async_session / 'diagnostics.log').read_text(encoding='utf-8')
assert 'token=private' in async_process.stdout and 'payment failed' in async_process.stdout, 'wrapped console must fall back to observe'
assert async_original.read_text(encoding='utf-8').count('token=private') == 2, 'file appender must remain intact'
results.append({'mode': 'replace-console-async-fallback', 'captured': len(async_captured), 'complete': True,
                'consolePreserved': True, 'filePreserved': True})
strict_case = run / 'strict-invalid-config'
strict_case.mkdir()
invalid = strict_case / 'invalid.yaml'
invalid.write_text('schemaVersion: [malformed\n', encoding='utf-8')
strict = subprocess.run([str(JAVA), f'-Djava.io.tmpdir={ROOT / ".build/tmp"}', f'-Duser.home={ROOT / ".build/user"}',
                         f'-javaagent:{ROOT / "dist/loglens-agent.jar"}=strict=true,config={invalid}',
                         '-cp', classpath, 'com.example.LogbackDemo'], cwd=ROOT, capture_output=True, text=True,
                        encoding='utf-8', errors='replace', timeout=30)
(strict_case / 'stdout.txt').write_text(strict.stdout, encoding='utf-8')
(strict_case / 'stderr.txt').write_text(strict.stderr, encoding='utf-8')
assert strict.returncode != 0, 'strict invalid YAML must prevent application startup'
assert 'strict initialization failed' in strict.stderr.lower(), strict.stderr
results.append({'mode': 'strict-invalid-yaml', 'applicationStarted': False, 'exitCode': strict.returncode})
(run / 'results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
print(f'Agent modes and file preservation verified: {run}')
