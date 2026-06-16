# ==============================================================================
# build-jre.ps1 — 使用 jlink 生成精简 JRE (Windows)
# 用 JDK 17 的 jdeps 推导 fat-jar 依赖模块，jlink 生成最小 JRE
# 产出目录: desktop\src-tauri\resources\jre\
# ==============================================================================

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path "$ScriptDir\..\..").Path
$DesktopDir = "$ProjectRoot\desktop"
$ResourcesDir = "$DesktopDir\src-tauri\resources"
$JreOutput = "$ResourcesDir\jre"
$FatJar = "$ProjectRoot\proxy-local\target\proxy-local-1.0.0-SNAPSHOT.jar"

Write-Host "=== SimplePlane: Build Minimal JRE (Windows) ===" -ForegroundColor Cyan
Write-Host "Project root: $ProjectRoot"
Write-Host "Output dir:   $JreOutput"

# 检查 JDK 17+
$javaVersion = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
if ([int]$javaVersion -lt 17) {
    Write-Host "ERROR: JDK 17+ required. Current: $javaVersion" -ForegroundColor Red
    exit 1
}

# 构建 fat-jar（如果不存在）
if (-not (Test-Path $FatJar)) {
    Write-Host "fat-jar not found, building with maven..."
    Push-Location $ProjectRoot
    & mvn package -pl proxy-local -am -DskipTests -q
    Pop-Location
}

if (-not (Test-Path $FatJar)) {
    Write-Host "ERROR: Failed to build fat-jar at $FatJar" -ForegroundColor Red
    exit 1
}

$jarSize = (Get-Item $FatJar).Length / 1MB
Write-Host "fat-jar: $FatJar ($([math]::Round($jarSize, 1)) MB)"

# 使用 jdeps 分析依赖模块
Write-Host "Analyzing module dependencies with jdeps..."
$modules = ""
try {
    $modules = & jdeps --ignore-missing-deps --print-module-deps $FatJar 2>$null
} catch {}

if (-not $modules) {
    Write-Host "jdeps analysis incomplete, using conservative module set"
    $modules = "java.base,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported"
}

# 确保关键模块
$required = @("java.base", "java.logging", "java.management", "java.naming", "jdk.crypto.ec", "jdk.unsupported")
foreach ($mod in $required) {
    if ($modules -notmatch $mod) {
        $modules = "$modules,$mod"
    }
}

Write-Host "Modules: $modules"

# 清理旧产物
if (Test-Path $JreOutput) {
    Remove-Item $JreOutput -Recurse -Force
}

# 获取 JAVA_HOME
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome) {
    $JavaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)
}

# 使用 jlink 生成精简 JRE
# JDK 17 uses --compress=2 (ZIP), JDK 21+ uses --compress=zip-6
if ([int]$javaVersion -ge 21) {
    $compressArg = "--compress=zip-6"
} else {
    $compressArg = "--compress=2"
}

Write-Host "Running jlink..."
& jlink `
    --module-path "$JavaHome\jmods" `
    --add-modules $modules `
    --output $JreOutput `
    --strip-debug `
    --no-man-pages `
    --no-header-files `
    $compressArg

Write-Host ""
Write-Host "=== JRE Build Complete ===" -ForegroundColor Green
$jreSize = (Get-ChildItem $JreOutput -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host "Size: $([math]::Round($jreSize, 1)) MB"
& "$JreOutput\bin\java.exe" -version
Write-Host ""
Write-Host "Done! JRE at: $JreOutput"
