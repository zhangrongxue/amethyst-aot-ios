#requires -Version 5.1
<#
.SYNOPSIS
  Local dry run on Windows: builds the scanner, fetches real Minecraft jars,
  scans them and regenerates the MobiVM forceLinkClasses configuration.
  Nothing here needs macOS - the iOS compile itself happens in GitHub Actions.

.DESCRIPTION
  Steps:
    1. check prerequisites (git, python, JDK 17)
    2. build tools/reflect-scan
    3. fetch Minecraft <version> client + libraries into work/mc
    4. scan them -> work/scan-mc (report + force-link-classes.txt)
    5. scan the AOT payload classpath -> work/scan-payload
    6. regenerate mobivm/robovm.xml from that scan

.EXAMPLE
  powershell -File scripts/bootstrap.ps1
  powershell -File scripts/bootstrap.ps1 -McVersion 1.12.2
#>
param(
    [string]$McVersion = '1.12.2',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipMc
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

Write-Host "=== 1/6 prerequisites ==="
foreach ($t in @('git', 'python')) {
    if (-not (Get-Command $t -ErrorAction SilentlyContinue)) { throw "missing tool: $t" }
    Write-Host "  $t OK"
}

function Find-Java {
    param([string]$Hint)
    if ($Hint -and (Test-Path (Join-Path $Hint 'bin\javac.exe'))) { return $Hint }
    foreach ($c in @('C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot', 'C:\Program Files\Microsoft\jdk-17.0.21-hotspot')) {
        if (Test-Path (Join-Path $c 'bin\javac.exe')) { return $c }
    }
    throw "JDK 17 not found. winget install Microsoft.OpenJDK.17"
}
$jdk = Find-Java $JavaHome
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
Write-Host "  java: $jdk"

Write-Host "=== 2/6 build reflection scanner ==="
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'tools\reflect-scan\build.ps1') -JavaHome $jdk

Write-Host "=== 3/6 fetch Minecraft $McVersion ==="
if ($SkipMc) {
    Write-Host '  skipped (-SkipMc)'
} else {
    python (Join-Path $root 'scripts\fetch_minecraft.py') --version $McVersion --out (Join-Path $root 'work\mc') --os osx --arch arm64
}

Write-Host "=== 4/6 scan Minecraft jars ==="
if (-not $SkipMc) {
    java -jar (Join-Path $root 'tools\reflect-scan\build\reflect-scan.jar') --jars (Join-Path $root 'work\mc') --out (Join-Path $root 'work\scan-mc') --min-package-hits 3
}

Write-Host "=== 5/6 scan AOT payload classpath ==="
New-Item -ItemType Directory -Force (Join-Path $root 'work\scan-payload-input') | Out-Null
Copy-Item (Join-Path $root 'work\mc\gson-*.jar') (Join-Path $root 'work\scan-payload-input') -ErrorAction SilentlyContinue
if (-not (Test-Path (Join-Path $root 'mobivm\target\classes'))) {
    Write-Host '  payload not compiled yet (needs Maven); run: mvn -f mobivm/pom.xml package'
}
if (Test-Path (Join-Path $root 'mobivm\target\classes')) {
    & "$jdk\bin\jar.exe" cf (Join-Path $root 'work\scan-payload-input\mcjava-aot.jar') -C (Join-Path $root 'mobivm\target\classes') .
}
java -jar (Join-Path $root 'tools\reflect-scan\build\reflect-scan.jar') --jars (Join-Path $root 'work\scan-payload-input') --out (Join-Path $root 'work\scan-payload') --min-package-hits 2

Write-Host "=== 6/6 regenerate mobivm/robovm.xml ==="
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\gen-robovm-config.ps1') `
    -ForceLink (Join-Path $root 'work\scan-payload\force-link-classes.txt') `
    -RoboVMXml (Join-Path $root 'mobivm\robovm.xml')

Write-Host ''
Write-Host "Done. Reports:"
Write-Host "  work/scan-mc/reflection-report.md      (real Minecraft jars)"
Write-Host "  work/scan-payload/reflection-report.md (AOT payload classpath)"
Write-Host "Next: set a token and run scripts\all.ps1 -Repo <owner>/<repo>"
