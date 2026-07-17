function Invoke-SafeUpdate {
    param(
        [Parameter(Mandatory)]
        $Config
    )

    Update-Upstream

    if (-not (Test-UpstreamUpdates)) {
        Write-Host "Repository is already up to date." -ForegroundColor Green
        return
    }

    $backup = Create-BackupBranch

    Write-Host ""
    Write-Host "Backup branch: $backup" -ForegroundColor Cyan

    $files = Get-ChangedFiles

    Write-Host ""
    Show-UpdatePlan $files $Config

    Write-Host ""

    $manual = @()

    foreach ($file in $files) {
        if ((Get-FileCategory $file $Config) -eq "manual") {
            $manual += $file
        }
    }

    if ($manual.Count -gt 0) {
        Write-Host "Manual review required:" -ForegroundColor Yellow

        foreach ($file in $manual) {
            Write-Host "  $file"
        }

        Write-Host ""
    }

    Update-SafeFiles $files $Config

    Write-Host ""
    Write-Host "Safe update completed." -ForegroundColor Green
    Write-Host "Backup branch: $backup" -ForegroundColor Cyan
}