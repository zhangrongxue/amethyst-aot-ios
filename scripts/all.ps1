#requires -Version 5.1
<#
.SYNOPSIS
  End-to-end: commit + push, trigger the cloud build, wait, download the IPA into dist.

.EXAMPLE
  .\scripts\all.ps1 -Repo owner/amethyst-aot-ios
  .\scripts\all.ps1 -Repo owner/repo -Token ghp_xxx -McVersion 1.12.2
#>
param(
    [string]$Repo,
    [string]$Token,
    [string]$Branch = 'main',
    [string]$McVersion = '1.12.2',
    [switch]$SkipAot,
    [switch]$NoPush,
    [int]$TimeoutMinutes = 200
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

& (Join-Path $PSScriptRoot 'publish.ps1') -Repo $Repo -Branch $Branch -Token $Token -NoPush:$NoPush

if ($NoPush) { Write-Host '[all] -NoPush set, nothing else to do'; return }

& (Join-Path $PSScriptRoot 'trigger.ps1') -Repo $Repo -Ref $Branch -McVersion $McVersion -SkipAot:$SkipAot
& (Join-Path $PSScriptRoot 'watch.ps1') -Repo $Repo -TimeoutMinutes $TimeoutMinutes
if ($LASTEXITCODE -ne 0) { throw "workflow run failed (see the URL above)" }

& (Join-Path $PSScriptRoot 'download.ps1') -Repo $Repo -IncludeAot

$root = Get-RepoRoot
$ipa = Get-ChildItem (Join-Path $root 'dist') -Filter *.ipa | Select-Object -First 1
if ($ipa) {
    Write-Host "[all] verifying $($ipa.Name)"
    python (Join-Path $PSScriptRoot 'verify_ipa.py') $ipa.FullName
}
