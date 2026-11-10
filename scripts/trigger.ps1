#requires -Version 5.1
<#
.SYNOPSIS
  Pushes the local repository (first run) and triggers the iOS build workflow.

.EXAMPLE
  .\scripts\trigger.ps1 -Repo owner/amethyst-aot-ios -Ref main
  .\scripts\trigger.ps1 -Repo owner/repo -McVersion 1.12.2
#>
param(
    [string]$Repo,
    [string]$Ref = 'main',
    [string]$McVersion = '1.12.2',
    [switch]$SkipAot
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$slug = Get-RepoSlug -Repo $Repo
$token = Get-GitHubToken
Write-Host "[trigger] repo=$slug ref=$Ref mc=$McVersion skipAot=$($SkipAot.IsPresent)"

$payload = @{
    ref    = $Ref
    inputs = @{
        mc_version = $McVersion
        skip_aot   = if ($SkipAot) { 'true' } else { 'false' }
    }
} | ConvertTo-Json -Depth 5

$uri = "https://api.github.com/repos/$slug/actions/workflows/build-ios.yml/dispatches"
Invoke-GitHub -Uri $uri -Token $token -Method POST -Body $payload | Out-Null
Write-Host "[trigger] dispatched build-ios.yml on $Ref"

Start-Sleep -Seconds 4
$runs = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/workflows/build-ios.yml/runs?per_page=3" -Token $token
$latest = $runs.workflow_runs | Select-Object -First 1
if ($latest) {
    Write-Host "[trigger] run #$($latest.run_number) id=$($latest.id) status=$($latest.status)"
    Write-Host "[trigger] $($latest.html_url)"
    $latest.id | Set-Content (Join-Path $env:TEMP 'mcjava-last-run-id.txt')
}
