#requires -Version 5.1
<#
.SYNOPSIS
  Builds the reflection scanner on Windows without Maven.

.DESCRIPTION
  Downloads the two ASM jars from Maven Central (cached in .\lib), compiles
  src/main/java with a JDK found via JAVA_HOME or the known install paths and
  produces build\reflect-scan.jar (runnable with `java -jar`).

.EXAMPLE
  .\build.ps1
#>
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$lib = Join-Path $root 'lib'
$build = Join-Path $root 'build'
$classes = Join-Path $build 'classes'

function Find-Java {
    param([string]$Hint)
    if ($Hint -and (Test-Path (Join-Path $Hint 'bin\javac.exe'))) { return $Hint }
    $candidates = @(
        'C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot',
        'C:\Program Files\Microsoft\jdk-17.0.21-hotspot',
        'C:\Program Files\Eclipse Adoptium\jdk-17.0.0.0-hotspot'
    )
    if ($env:JAVA_HOME) { $candidates = @($env:JAVA_HOME) + $candidates }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { return $c }
    }
    $j = Get-Command javac -ErrorAction SilentlyContinue
    if ($j) { return (Split-Path -Parent (Split-Path -Parent $j.Source)) }
    throw "JDK not found. Install one (winget install Microsoft.OpenJDK.17) or set JAVA_HOME."
}

$jdk = Find-Java $JavaHome
Write-Host "[build] JDK: $jdk"

New-Item -ItemType Directory -Force $lib | Out-Null

$deps = @(
    @{ Name = 'asm-9.7.jar';       Url = 'https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar' },
    @{ Name = 'asm-tree-9.7.jar';  Url = 'https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar' }
)
foreach ($d in $deps) {
    $dst = Join-Path $lib $d.Name
    if ($Force -or -not (Test-Path $dst) -or (Get-Item $dst).Length -lt 10000) {
        Write-Host "[build] downloading $($d.Name)"
        & curl.exe -fSL --retry 3 -o $dst $d.Url
        if ($LASTEXITCODE -ne 0) { throw "download failed: $($d.Url)" }
    }
}

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null

$sources = Get-ChildItem -Recurse (Join-Path $root 'src\main\java') -Filter *.java | ForEach-Object { $_.FullName }
$cp = (Get-ChildItem $lib -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'

Write-Host "[build] compiling $($sources.Count) sources"
& (Join-Path $jdk 'bin\javac.exe') --release 8 -encoding UTF-8 -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# Make the jar self-contained (the CI build uses the Maven shade plugin for the same result).
Push-Location $classes
try {
    foreach ($dep in (Get-ChildItem $lib -Filter *.jar)) {
        & (Join-Path $jdk 'bin\jar.exe') xf $dep.FullName
        if ($LASTEXITCODE -ne 0) { throw "failed to unpack $($dep.Name)" }
    }
    Remove-Item -Recurse -Force (Join-Path $classes 'META-INF') -ErrorAction SilentlyContinue
} finally {
    Pop-Location
}

$manifest = Join-Path $build 'MANIFEST.MF'
@(
    'Manifest-Version: 1.0',
    'Main-Class: dev.mcjava.reflectscan.ReflectScan',
    ''
) | Set-Content -Encoding ascii $manifest

$jar = Join-Path $build 'reflect-scan.jar'
if (Test-Path $jar) { Remove-Item $jar }
& (Join-Path $jdk 'bin\jar.exe') cfm $jar $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "[build] OK -> $jar"
