function Test-GitRepository {
    try {
        git rev-parse --is-inside-work-tree *> $null
        return ($LASTEXITCODE -eq 0)
    }
    catch {
        return $false
    }
}

function Get-UpstreamDefaultBranch {
    $ref = git symbolic-ref refs/remotes/upstream/HEAD 2>$null

    if ($LASTEXITCODE -eq 0 -and $ref) {
        return ($ref -replace '^refs/remotes/upstream/', '').Trim()
    }

    foreach ($candidate in @("main", "master")) {
        git show-ref --verify --quiet "refs/remotes/upstream/$candidate"

        if ($LASTEXITCODE -eq 0) {
            return $candidate
        }
    }

    throw "Unable to determine upstream default branch."
}

function Update-Upstream {
    git fetch upstream --prune

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to fetch upstream."
    }
}

function Test-UpstreamUpdates {
    $branch = Get-UpstreamDefaultBranch

    $count = git rev-list --count HEAD.."upstream/$branch"

    return ([int]$count -gt 0)
}

function Get-NewCommits {
    $branch = Get-UpstreamDefaultBranch

    git log `
        --oneline `
        HEAD.."upstream/$branch"
}

function Get-ChangedFiles {
    $branch = Get-UpstreamDefaultBranch

    git diff `
        --name-only `
        HEAD.."upstream/$branch"
}

function Update-SafeFiles {
    param(
        [Parameter(Mandatory)]
        [string[]]$Files,

        [Parameter(Mandatory)]
        $Config
    )

    $branch = Get-UpstreamDefaultBranch

    foreach ($file in $Files) {

        $category = Get-FileCategory $file $Config

        if ($category -ne "safe") {
            continue
        }

        Write-Host "[UPDATE] $file" -ForegroundColor Green

        git checkout "upstream/$branch" -- "$file"

        if ($LASTEXITCODE -ne 0) {
            throw "Failed to update $file"
        }
    }
}

function Show-NewCommits {
    $commits = Get-NewCommits

    if (-not $commits) {
        Write-Host "No new commits." -ForegroundColor Green
        return
    }

    Write-Host ""
    Write-Host "New upstream commits:" -ForegroundColor Cyan
    $commits | ForEach-Object {
        Write-Host "  $_"
    }
}

function Show-ChangedFiles {
    $files = Get-ChangedFiles

    if (-not $files) {
        Write-Host "No changed files." -ForegroundColor Green
        return @()
    }

    return $files
}

function Get-RepositoryInfo {

    $current = (git branch --show-current).Trim()

    $branch = Get-UpstreamDefaultBranch

    return [PSCustomObject]@{
        CurrentBranch = $current
        UpstreamBranch = $branch
    }
}

function Show-RepositoryStatus {

    $info = Get-RepositoryInfo

    Write-Host ""
    Write-Host "Repository" -ForegroundColor Cyan
    Write-Host "  Current branch : $($info.CurrentBranch)"
    Write-Host "  Upstream branch: $($info.UpstreamBranch)"

    $dirty = git status --porcelain

    if ($dirty) {
        Write-Host ""
        Write-Host "Working tree has local changes." -ForegroundColor Yellow
    }
    else {
        Write-Host ""
        Write-Host "Working tree is clean." -ForegroundColor Green
    }
}
