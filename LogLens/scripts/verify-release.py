"""Check the three documented public artifacts without extracting them."""
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
expected = {
    'loglens-agent.jar': 'loglens-runtime.jar',
    'loglens-viewer.jar': 'io/loglens/viewer/ViewerMain.class',
    'loglens-api.jar': 'io/loglens/api/CapturedLog.class',
}
for filename, required in expected.items():
    artifact = dist / filename
    if not artifact.is_file():
        raise SystemExit(f'Missing release artifact: {artifact}')
    with ZipFile(artifact) as archive:
        names = set(archive.namelist())
        if required not in names:
            raise SystemExit(f'{filename} is missing {required}')
        if filename == 'loglens-agent.jar':
            manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
            if 'Premain-Class: io.loglens.launcher.LogLensAgent' not in manifest:
                raise SystemExit('Agent manifest does not declare Premain-Class')
        if filename == 'loglens-viewer.jar':
            manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
            if 'Main-Class: io.loglens.viewer.ViewerMain' not in manifest:
                raise SystemExit('Viewer manifest does not declare Main-Class')
    print(f'{filename}: {artifact.stat().st_size} bytes')
