#requires -Version 5.1
<#
.SYNOPSIS
  Commits the working tree and pushes it to GitHub.

.DESCRIPTION
  Authentication, in order of preference:
    1. -Token / $env:GH_TOKEN / .secrets\gh_token  -> push with an ephemeral
       Authorization header (the token is never written to .git/config).
    2. No token -> plain `git push`, which lets Git Credential Manager open the
       browser login flow.

.EXAMPLE
  .\scripts\publish.ps1 -Repo owner/amethyst-aot-ios -Message "initial import"
#>
param(
    [string]$Repo,
    [string]$Message = 'build: update pipeline',
    [string]$Branch = 'main',
    [string]$Token,
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$root = Get-RepoRoot
Push-Location $root
try {
    if (-not (Test-Path '.git')) { git init -b $Branch | Out-Null }

    $slug = Get-RepoSlug -Repo $Repo

    git add -A
    $status = git status --porcelain
    if ($status) {
        git -c user.name='mcjava-ios' -c user.email='mcjava-ios@users.noreply.github.com' commit -m $Message | Out-Host
    } else {
        Write-Host "[publish] nothing to commit"
    }

    if ($NoPush) { Write-Host "[publish] -NoPush set, stopping"; return }

    $remote = git remote 2>$null
    if (-not $remote) {
        git remote add origin "https://github.com/$slug.git"
        Write-Host "[publish] added origin https://github.com/$slug.git"
    }

    $tok = $null
    try { $tok = Get-GitHubToken -Explicit $Token } catch { $tok = $null }

    if ($tok) {
        $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$tok"))
        git -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $basic" push -u origin $Branch
    } else {
        Write-Host "[publish] no token found, relying on Git Credential Manager (a browser window may open)"
        git push -u origin $Branch
    }
} finally {
    Pop-Location
}
