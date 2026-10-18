"""Build isolated Agent layout from Maven-produced runtime and bootstrap artifacts."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
target = root / 'loglens-agent' / 'target'
runtime = target / 'loglens-agent-0.1.0-SNAPSHOT-runtime.jar'
bridge = root / 'loglens-bootstrap' / 'target' / 'loglens-bootstrap-0.1.0-SNAPSHOT.jar'
launcher = target / 'classes' / 'io' / 'loglens' / 'launcher' / 'LogLensAgent.class'
for source in (runtime, bridge, launcher):
    if not source.is_file():
        raise SystemExit(f'Missing build artifact: {source}')
dist = root / 'dist'
dist.mkdir(exist_ok=True)
output = dist / 'loglens-agent.jar'
manifest = ('Manifest-Version: 1.0\r\n'
            'Premain-Class: io.loglens.launcher.LogLensAgent\r\n'
            'Can-Redefine-Classes: false\r\n'
            'Can-Retransform-Classes: false\r\n\r\n')
with ZipFile(output, 'w', ZIP_DEFLATED) as jar:
    jar.writestr('META-INF/MANIFEST.MF', manifest)
    jar.write(launcher, 'io/loglens/launcher/LogLensAgent.class')
    jar.write(bridge, 'loglens-bootstrap.jar')
    jar.write(runtime, 'loglens-runtime.jar')
with ZipFile(output) as jar:
    assert set(jar.namelist()) == {'META-INF/MANIFEST.MF', 'io/loglens/launcher/LogLensAgent.class', 'loglens-bootstrap.jar', 'loglens-runtime.jar'}
with ZipFile(runtime) as jar:
    assert not any(n.startswith('io/loglens/bootstrap/') for n in jar.namelist()), 'Bootstrap must not be duplicated in runtime'
    assert 'io/loglens/agent/AgentRuntime.class' in jar.namelist()
    assert 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll' in jar.namelist()
print(f'Agent package verified: {output}')
