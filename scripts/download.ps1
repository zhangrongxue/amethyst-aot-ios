#requires -Version 5.1
<#
.SYNOPSIS
  Downloads the unsigned IPA (and the AOT reports) from the latest successful run
  into .\dist.

.EXAMPLE
  .\scripts\download.ps1 -Repo owner/repo
  .\scripts\download.ps1 -Repo owner/repo -RunId 123456789
#>
param(
    [string]$Repo,
    [long]$RunId = 0,
    [string]$OutDir = 'dist',
    [switch]$IncludeAot
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$slug = Get-RepoSlug -Repo $Repo
$token = Get-GitHubToken
$root = Get-RepoRoot

if ($RunId -eq 0) {
    $runs = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/workflows/build-ios.yml/runs?per_page=10" -Token $token
    $run = $runs.workflow_runs | Where-Object { $_.status -eq 'completed' -and $_.conclusion -eq 'success' } | Select-Object -First 1
    if (-not $run) { throw "no successful run yet" }
    $RunId = $run.id
}
$run = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/runs/$RunId" -Token $token
Write-Host "[download] run #$($run.run_number) ($RunId) $($run.head_sha.Substring(0,7)) $($run.html_url)"

$arts = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/runs/$RunId/artifacts" -Token $token
if (-not $arts.artifacts -or $arts.artifacts.Count -eq 0) { throw "run has no artifacts" }

$outDir = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $root $OutDir }
New-Item -ItemType Directory -Force $outDir | Out-Null
$tmp = Join-Path $env:TEMP ("mcjava-dl-" + $RunId)
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
New-Item -ItemType Directory -Force $tmp | Out-Null

$wanted = @('amethyst-unsigned-ipa')
if ($IncludeAot) { $wanted += 'mobivm-aot' }

foreach ($a in $arts.artifacts) {
    if ($wanted -notcontains $a.name) { continue }
    Write-Host "[download] artifact $($a.name) ($([math]::Round($a.size_in_bytes/1MB,1)) MiB)"
    $zip = Join-Path $tmp "$($a.name).zip"
    # archive_download_url redirects to a signed blob URL; Invoke-RestMethod follows it.
    Invoke-GitHub -Uri $a.archive_download_url -Token $token -OutFile $zip
    $ex = Join-Path $tmp $a.name
    Expand-Archive -Path $zip -DestinationPath $ex -Force
    Get-ChildItem -Recurse $ex -Filter *.ipa | ForEach-Object {
        $dest = Join-Path $outDir $_.Name
        Copy-Item $_.FullName $dest -Force
        Write-Host "[download] -> $dest ($([math]::Round($_.Length/1MB,1)) MiB)"
        $report = Join-Path $outDir 'ipa-report.json'
        Get-ChildItem -Recurse $ex -Filter 'ipa-report.json' | Select-Object -First 1 | ForEach-Object { Copy-Item $_.FullName $report -Force }
    }
    if ($a.name -eq 'mobivm-aot') {
        $dest = Join-Path $outDir 'mobivm-aot'
        if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
        Copy-Item -Recurse $ex $dest
        Write-Host "[download] -> $dest"
    }
}

Get-ChildItem $outDir | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
Write-Host "[download] done. Install with TrollStore/AltStore/SideStore (unsigned IPA)."
