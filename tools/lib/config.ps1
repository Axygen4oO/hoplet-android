function Get-UpdateConfig {
    param(
        [string]$ConfigPath
    )

    if (-not (Test-Path $ConfigPath)) {
        throw "Config not found: $ConfigPath"
    }

    $config = Get-Content $ConfigPath -Raw | ConvertFrom-Json

    foreach ($section in @("ignore", "safe", "manual")) {
        if (-not ($config.PSObject.Properties.Name -contains $section)) {
            throw "Missing '$section' section in config."
        }

        if ($null -eq $config.$section) {
            throw "'$section' must not be null."
        }
    }

    return $config
}