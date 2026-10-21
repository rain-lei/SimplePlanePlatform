[CmdletBinding()]
param([string]$IdeaHome = 'D:\IntelliJ IDEA 2024.3.2', [string]$JavaHome = '')
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($root.StartsWith('C:', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Release workspace must not be on C:.' }
& (Join-Path $PSScriptRoot 'build.ps1') -IdeaHome $IdeaHome -JavaHome $JavaHome -ReturnStatus -MavenArguments @('package', '-DskipTests')
if ($global:LASTEXITCODE -ne 0) { exit $global:LASTEXITCODE }
Push-Location $root
try {
    python scripts/package-agent.py
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'loglens-viewer/target/loglens-viewer.jar') -Destination (Join-Path $dist 'loglens-viewer.jar') -Force
    Copy-Item -LiteralPath (Join-Path $root 'loglens-api/target/loglens-api-0.1.0-SNAPSHOT.jar') -Destination (Join-Path $dist 'loglens-api.jar') -Force
    python scripts/verify-release.py
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
