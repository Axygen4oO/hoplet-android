function Create-BackupBranch {
    $branch = "hoplet-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss")

    git branch $branch | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create backup branch."
    }

    return $branch
}

function Get-LatestBackupBranch {
    $branches = git for-each-ref `
        --sort=-creatordate `
        --format="%(refname:short)" `
        refs/heads/hoplet-backup-*

    if (-not $branches) {
        return $null
    }

    return ($branches | Select-Object -First 1).Trim()
}

function Restore-BackupBranch {
    param(
        [string]$Branch
    )

    if (-not $Branch) {
        throw "Backup branch not specified."
    }

    git reset --hard | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to reset working tree."
    }

    git checkout $Branch -- . | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restore files from backup."
    }
}