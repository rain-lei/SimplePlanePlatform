from pathlib import Path
import json, os, subprocess, uuid
root=Path(__file__).resolve().parents[1]
java=Path(os.environ.get('LOGLENS_JAVA','D:/IntelliJ IDEA 2024.3.2/jbr/bin/java.exe'))
run=root/'.build/proxyremote-agent-verification'/str(uuid.uuid4()); run.mkdir(parents=True)
config=run/'config.yaml'
config.write_text(f'''schemaVersion: 1
application: proxyremote-fixture
web:
  enabled: false
  port: 0
proxyRemote:
  enabled: true
  sampleIntervalMillis: 100
  maxTrackedConnections: 8
  maxTrackedSessions: 16
output:
  directory: {str(run/'logs').replace(chr(92),'/')}
''',encoding='utf-8')
cp=str(root/'loglens-adapter-proxyremote/target/test-classes')
proc=subprocess.run([str(java),f'-Djava.io.tmpdir={root/".build/tmp"}',f'-Duser.home={root/".build/user"}',
    f'-javaagent:{root/"dist/loglens-agent.jar"}=config={config}','-cp',cp,'io.loglens.proxyremote.ProxyRemotePremainFixture'],
    cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
(run/'stdout.txt').write_text(proc.stdout,encoding='utf-8'); (run/'stderr.txt').write_text(proc.stderr,encoding='utf-8')
assert proc.returncode==0,proc.stderr
sessions=list((run/'logs/proxyremote-fixture').iterdir()); assert len(sessions)==1,sessions
rows=[json.loads(line) for file in sessions[0].glob('telemetry-*.jsonl') for line in file.read_text(encoding='utf-8').splitlines()]
kinds={row['kind'] for row in rows}
assert {'CLIENT_CONNECTED','SESSION_OPENED','SESSION_ACTIVE','SESSION_CLOSED','CLIENT_DISCONNECTED'} <= kinds,(kinds,proc.stderr)
closed=next(row for row in rows if row['kind']=='SESSION_CLOSED')
assert closed['uplinkBytes']==12 and closed['downlinkBytes']==7,closed
assert closed['streamId']=='7' and closed['targetHost']=='example.test' and closed['targetPort']==443,closed
assert all('data' not in row and 'payload' not in row for row in rows)
(run/'result.json').write_text(json.dumps({'events':len(rows),'kinds':sorted(kinds),'uplinkBytes':12,'downlinkBytes':7,'payloadStored':False},indent=2),encoding='utf-8')
print(f'Packaged proxy-remote Byte Buddy fixture verified: {run}')