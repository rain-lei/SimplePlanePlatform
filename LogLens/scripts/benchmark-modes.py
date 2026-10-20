"""Run the fixed-seed 100k baseline/observe/replace workload and retain raw evidence on E:."""
from pathlib import Path
import json
import os
import platform
import re
import subprocess
import time
import uuid

root = Path(__file__).resolve().parents[1]
java = Path(os.environ.get('LOGLENS_JAVA', 'D:/IntelliJ IDEA 2024.3.2/jbr/bin/java.exe'))
agent = root / 'dist/loglens-agent.jar'
demo = root / 'examples/logback-demo/target/classes'
repo = root / '.build/repository'
classpath = ';'.join(str(path) for path in [demo,
    repo / 'ch/qos/logback/logback-classic/1.5.18/logback-classic-1.5.18.jar',
    repo / 'ch/qos/logback/logback-core/1.5.18/logback-core-1.5.18.jar',
    repo / 'org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar'])
if not java.is_file() or not agent.is_file() or not (demo / 'com/example/PerformanceDemo.class').is_file():
    raise SystemExit('Build the demo and release Agent first (see README).')
run = root / '.build/performance' / str(uuid.uuid4())
run.mkdir(parents=True)
results = []
count = int(os.environ.get('LOGLENS_PERF_COUNT', '100000'))
overflow_policy = os.environ.get('LOGLENS_PERF_OVERFLOW_POLICY', 'DROP_NEWEST').upper()
if overflow_policy not in ('DROP_NEWEST', 'BLOCK'):
    raise SystemExit('LOGLENS_PERF_OVERFLOW_POLICY must be DROP_NEWEST or BLOCK')
for mode in ('baseline', 'observe', 'replace-console'):
    case = run / mode
    case.mkdir()
    log_file = case / 'application.log'
    logback = case / 'logback.xml'
    logback.write_text(f'''<configuration>
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%msg%n</pattern></encoder></appender>
<appender name="FILE" class="ch.qos.logback.core.FileAppender"><file>{log_file.as_posix()}</file><append>false</append><encoder><pattern>%msg%n</pattern></encoder></appender>
<root level="INFO"><appender-ref ref="CONSOLE"/><appender-ref ref="FILE"/></root>
</configuration>''', encoding='utf-8')
    config = case / 'config.yaml'
    output = (case / 'logs').as_posix()
    config.write_text(f'''schemaVersion: 1
application: perf
agent:
  mode: {mode if mode != 'baseline' else 'observe'}
  consoleAppenderNames: [CONSOLE]
capture:
  maxMessageChars: 16384
runtime:
  ringBufferSize: 8192
  overflowPolicy: {overflow_policy}
  shutdownTimeoutMillis: 30000
  tickMillis: 25
web:
  enabled: false
output:
  directory: {output}
  text:
    combined: false
    splitByLevel: false
  structured:
    enabled: true
    rotateSizeMiB: 64
    maxDiskMiB: 2048
''', encoding='utf-8')
    command = [str(java), '-Xms128m', '-Xmx512m', f'-Djava.io.tmpdir={root / ".build/tmp"}',
               f'-Duser.home={root / ".build/user"}', f'-Dlogback.configurationFile={logback}',
               f'-Ddemo.perf.count={count}']
    if mode != 'baseline': command.append(f'-javaagent:{agent}=config={config}')
    command.extend(['-cp', classpath, 'com.example.PerformanceDemo'])
    started = time.perf_counter_ns()
    process = subprocess.run(command, cwd=root, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=600)
    wall = time.perf_counter_ns() - started
    (case / 'stdout.txt').write_text(process.stdout, encoding='utf-8')
    (case / 'stderr.txt').write_text(process.stderr, encoding='utf-8')
    if process.returncode:
        raise SystemExit(f'{mode} exited {process.returncode}: {process.stderr[-3000:]}')
    match = re.search(r'^PERF_RESULT (.+)$', process.stdout, re.MULTILINE)
    if not match: raise SystemExit(f'{mode} emitted no PERF_RESULT')
    metrics = {key: (float(value) if '.' in value else int(value)) for key, value in re.findall(r'(\w+)=([0-9.]+)', match.group(1))}
    session_dir = None
    captured = dropped = record_sequence = 0
    complete = True
    if mode != 'baseline':
        sessions = list((case / 'logs/perf').iterdir())
        if len(sessions) != 1: raise SystemExit(f'{mode} expected one session, found {len(sessions)}')
        session_dir = sessions[0]
        metadata = json.loads((session_dir / 'session.json').read_text(encoding='utf-8'))
        captured = metadata['capturedCount']
        dropped = metadata['counters']['dropped']
        record_sequence = metadata['recordSequence']
        complete = metadata['complete']
        if not complete: raise SystemExit(f'{mode} session is incomplete')
        if captured + dropped != count: raise SystemExit(f'{mode} accounting mismatch: captured={captured}, dropped={dropped}, expected={count}')
    console_records = process.stdout.count('benchmark payload=')
    if mode in ('baseline', 'observe') and console_records != count:
        raise SystemExit(f'{mode} console output lost records: {console_records}/{count}')
    if mode == 'replace-console' and console_records != dropped:
        raise SystemExit(f'replace-console fallback mismatch: console={console_records}, dropped={dropped}')
    appender_bytes = log_file.stat().st_size
    storage_root = case / 'logs'
    storage_bytes = sum(path.stat().st_size for path in storage_root.rglob('*') if path.is_file()) if mode != 'baseline' else 0
    session_bytes = sum(path.stat().st_size for path in session_dir.rglob('*') if path.is_file()) if session_dir else 0
    native_bytes = sum(path.stat().st_size for path in (storage_root / '.native').rglob('*') if path.is_file()) if mode != 'baseline' else 0
    results.append({'mode': mode, **metrics, 'wallNanos': wall, 'captured': captured, 'dropped': dropped,
                    'recordSequence': record_sequence, 'applicationFileBytes': appender_bytes,
                    'logLensStorageBytes': storage_bytes, 'sessionDataBytes': session_bytes, 'sqliteNativeBytes': native_bytes,
                    'consoleFallbackRecords': console_records if mode == 'replace-console' else 0,
                    'sessionComplete': complete})
report = {'java': subprocess.run([str(java), '-version'], capture_output=True, text=True, encoding='utf-8', errors='replace').stderr.splitlines()[0],
          'os': platform.platform(), 'processor': platform.processor(), 'logicalProcessors': os.cpu_count(),
          'seed': 20261012, 'count': count, 'overflowPolicy': overflow_policy, 'cases': results}
(run / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(f'Performance modes verified: {run}')
