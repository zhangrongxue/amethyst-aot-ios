#requires -Version 5.1
<#
.SYNOPSIS
  Waits for a workflow run to finish and prints the per-job progress.

.EXAMPLE
  .\scripts\watch.ps1 -Repo owner/repo
  .\scripts\watch.ps1 -Repo owner/repo -RunId 123456789
#>
param(
    [string]$Repo,
    [long]$RunId = 0,
    [int]$TimeoutMinutes = 200,
    [int]$PollSeconds = 20
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$slug = Get-RepoSlug -Repo $Repo
$token = Get-GitHubToken

if ($RunId -eq 0) {
    $runs = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/workflows/build-ios.yml/runs?per_page=1" -Token $token
    $RunId = $runs.workflow_runs[0].id
}
if (-not $RunId) { throw "no workflow run found" }

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$lastLine = ''
while ((Get-Date) -lt $deadline) {
    $run = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/runs/$RunId" -Token $token
    $line = "run #$($run.run_number) status=$($run.status) conclusion=$($run.conclusion)"
    if ($line -ne $lastLine) { Write-Host "[watch] $(Get-Date -Format HH:mm:ss) $line"; $lastLine = $line }
    if ($run.status -eq 'completed') {
        $jobs = Invoke-GitHub -Uri "https://api.github.com/repos/$slug/actions/runs/$RunId/jobs" -Token $token
        foreach ($j in $jobs.jobs) {
            Write-Host "  job: $($j.name) => $($j.conclusion)"
            if ($j.conclusion -ne 'success') {
                foreach ($s in $j.steps | Where-Object { $_.conclusion -ne 'success' -and $_.conclusion }) {
                    Write-Host "    step failed: $($s.name) ($($s.conclusion))"
                }
            }
        }
        Write-Host "[watch] $($run.html_url)"
        if ($run.conclusion -ne 'success') { exit 1 }
        exit 0
    }
    Start-Sleep -Seconds $PollSeconds
}
Write-Host "[watch] timed out after $TimeoutMinutes minutes"
exit 2
