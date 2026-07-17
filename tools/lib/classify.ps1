function Test-PathMask {
    param(
        [string]$Path,
        [string[]]$Masks
    )

    foreach ($mask in $Masks) {
        if ($Path -like $mask) {
            return $true
        }
    }

    return $false
}

function Get-FileCategory {
    param(
        [string]$Path,
        $Config
    )

    if (Test-PathMask $Path $Config.ignore) {
        return "ignore"
    }

    if (Test-PathMask $Path $Config.safe) {
        return "safe"
    }

    if (Test-PathMask $Path $Config.manual) {
        return "manual"
    }

    return "manual"
}

function Show-UpdatePlan {
    param(
        [string[]]$Files,
        $Config
    )

    foreach ($file in $Files) {
        $category = Get-FileCategory $file $Config

        switch ($category) {
            "safe" {
                Write-Host "[SAFE]    $file" -ForegroundColor Green
            }

            "manual" {
                Write-Host "[MANUAL]  $file" -ForegroundColor Yellow
            }

            "ignore" {
                Write-Host "[IGNORE]  $file" -ForegroundColor DarkGray
            }
        }
    }
}