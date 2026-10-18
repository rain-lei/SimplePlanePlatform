[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$IdeaHome = 'D:\IntelliJ IDEA 2024.3.2',
    [string]$JavaHome = '',
    [switch]$ReturnStatus,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]]$MavenArguments
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($workspaceRoot.StartsWith('C:', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Build workspace must not be on C:.' }
if (!$JavaHome) { $JavaHome = Join-Path $IdeaHome 'jbr' }
$mavenCommand = Join-Path $IdeaHome 'plugins\maven\lib\maven3\bin\mvn.cmd'
if (!(Test-Path -LiteralPath $mavenCommand)) { throw "IDEA Maven not found: $mavenCommand" }
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw "JDK not found: $JavaHome" }
$buildDirectory = Join-Path $workspaceRoot '.build'
foreach ($part in @('tmp', 'user', 'repository')) { New-Item -ItemType Directory -Force -Path (Join-Path $buildDirectory $part) | Out-Null }
$environmentNames = @('JAVA_HOME','MAVEN_OPTS','TMP','TEMP','MAVEN_SKIP_RC')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $JavaHome
    $env:TMP = Join-Path $buildDirectory 'tmp'
    $env:TEMP = $env:TMP
    $env:MAVEN_SKIP_RC = '1'
    $env:MAVEN_OPTS = "-Duser.home=$buildDirectory/user -Djava.io.tmpdir=$buildDirectory/tmp -Dfile.encoding=UTF-8"
    if (!$MavenArguments) { $MavenArguments = @('verify') }
    Push-Location $workspaceRoot
    $previousActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $mavenCommand '-B' '-ntp' '-s' (Join-Path $PSScriptRoot 'maven-settings.xml') "-Dmaven.repo.local=$buildDirectory/repository" "-Dloglens.build.directory=$buildDirectory" @MavenArguments 2>&1
        $buildExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousActionPreference
        Pop-Location
    }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
if ($ReturnStatus) { $global:LASTEXITCODE = $buildExitCode; return }
exit $buildExitCode
