#requires -Version 5.1
<#
.SYNOPSIS
  Shared helpers for the GitHub-side scripts (token discovery + REST calls).

.DESCRIPTION
  Token lookup order:
    1. -Token parameter (caller)
    2. $env:GH_TOKEN / $env:GITHUB_TOKEN
    3. .secrets\gh_token (git-ignored file, one line)
    4. %USERPROFILE%\.mcjava-gh-token
#>

function Get-RepoRoot {
    return (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
}

function Get-GitHubToken {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit.Trim() }
    if ($env:GH_TOKEN) { return $env:GH_TOKEN.Trim() }
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN.Trim() }
    $root = Get-RepoRoot
    foreach ($p in @(
            (Join-Path $root '.secrets\gh_token'),
            (Join-Path $env:USERPROFILE '.mcjava-gh-token'))) {
        if (Test-Path $p) {
            $t = (Get-Content $p -Raw).Trim()
            if ($t) { return $t }
        }
    }
    throw "No GitHub token. Set `$env:GH_TOKEN, write .secrets\gh_token, or pass -Token."
}

function Invoke-GitHub {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [string]$Token = (Get-GitHubToken),
        [string]$Method = 'GET',
        [string]$Body,
        [string]$OutFile
    )
    $headers = @{
        'Authorization'        = "Bearer $Token"
        'Accept'               = 'application/vnd.github+json'
        'X-GitHub-Api-Version' = '2022-11-28'
        'User-Agent'           = 'mcjava-ios-orchestrator'
    }
    $params = @{
        Uri         = $Uri
        Headers     = $headers
        Method      = $Method
        ErrorAction = 'Stop'
    }
    if ($Body) { $params['Body'] = $Body; $params['ContentType'] = 'application/json' }
    if ($OutFile) { $params['OutFile'] = $OutFile }
    return Invoke-RestMethod @params
}

function Get-RepoSlug {
    param([string]$Repo)
    if (-not $Repo) {
        $root = Get-RepoRoot
        Push-Location $root
        try {
            $url = git remote get-url origin 2>$null
        } finally { Pop-Location }
        if (-not $url) { throw "-Repo is required (git remote origin is not set)" }
        if ($url -match 'github\.com[:/]([^/]+)/([^/\.]+)') {
            return "$($Matches[1])/$($Matches[2])"
        }
        throw "cannot parse repo from remote: $url"
    }
    return $Repo
}
