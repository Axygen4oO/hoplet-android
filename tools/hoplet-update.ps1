param(
    [ValidateSet("check","apply","rollback","status")]
    [string]$Action = "check"
)

$Root = Split-Path -Parent $PSScriptRoot

. "$PSScriptRoot\lib\config.ps1"
. "$PSScriptRoot\lib\classify.ps1"
. "$PSScriptRoot\lib\backup.ps1"
. "$PSScriptRoot\lib\git.ps1"
. "$PSScriptRoot\lib\updater.ps1"

if (-not (Test-GitRepository)) {
    Write-Error "Current directory is not a Git repository."
    exit 1
}

Set-Location $Root

$config = Get-UpdateConfig "$PSScriptRoot\config\update-config.json"

switch ($Action) {

    "check" {

        Update-Upstream

        Show-RepositoryStatus

        if (Test-UpstreamUpdates) {

            Write-Host ""
            Show-NewCommits

            Write-Host ""
            $files = Show-ChangedFiles

            Write-Host ""
            Show-UpdatePlan $files $config
        }
        else {
            Write-Host ""
            Write-Host "Repository is already up to date." -ForegroundColor Green
        }
    }

    "status" {
        Show-RepositoryStatus
    }

    "apply" {
        Invoke-SafeUpdate $config
    }

    "rollback" {

        $backup = Get-LatestBackupBranch

        if (-not $backup) {
            Write-Host "No backup branches found." -ForegroundColor Yellow
            exit 0
        }

        Restore-BackupBranch $backup

        Write-Host ""
        Write-Host "Rollback completed from $backup" -ForegroundColor Green
    }
}