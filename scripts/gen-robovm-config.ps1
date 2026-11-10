<#
.SYNOPSIS
  Injects forceLinkClasses patterns produced by reflect-scan into mobivm/robovm.xml.

.PARAMETER ForceLink
  Path to force-link-classes.txt (default: work/scan-payload/force-link-classes.txt).

.PARAMETER RoboVMXml
  Path to the robovm.xml to rewrite (default: mobivm/robovm.xml).

.EXAMPLE
  powershell -File scripts/gen-robovm-config.ps1 -ForceLink work\scan-payload\force-link-classes.txt
#>
param(
    [string]$ForceLink = 'work\scan-payload\force-link-classes.txt',
    [string]$RoboVMXml = 'mobivm\robovm.xml'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$fl = if ([System.IO.Path]::IsPathRooted($ForceLink)) { $ForceLink } else { Join-Path $root $ForceLink }
$xml = if ([System.IO.Path]::IsPathRooted($RoboVMXml)) { $RoboVMXml } else { Join-Path $root $RoboVMXml }

if (-not (Test-Path $fl)) { throw "force-link file not found: $fl" }
if (-not (Test-Path $xml)) { throw "robovm.xml not found: $xml" }

$patterns = Get-Content $fl | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { $_.Trim() } | Where-Object { $_ }
$block = ($patterns | ForEach-Object { "        <pattern>$_</pattern>" }) -join "`n"

$text = Get-Content $xml -Raw
$beginMarker = '<!-- GENERATED:BEGIN -->'
$endMarker = '<!-- GENERATED:END -->'
$b = $text.IndexOf($beginMarker, [System.StringComparison]::Ordinal)
$e = $text.IndexOf($endMarker, [System.StringComparison]::Ordinal)
if ($b -lt 0 -or $e -lt 0 -or $e -lt $b) {
    throw "robovm.xml is missing the GENERATED markers"
}
$new = $text.Substring(0, $b + $beginMarker.Length) + "`n" + $block + "`n        " + $text.Substring($e)
Set-Content -Path $xml -Value $new -Encoding UTF8
Write-Host "[gen-robovm] injected $($patterns.Count) patterns into $xml"
