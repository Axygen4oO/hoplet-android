param()

$ErrorActionPreference = 'Stop'

$rootDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$goDir = $rootDir
$apiLevel = if ($env:ANDROID_NATIVE_API_LEVEL) { $env:ANDROID_NATIVE_API_LEVEL } else { '21' }

function Get-GoVersion {
    $raw = & go version
    if ($LASTEXITCODE -ne 0) {
        throw "go version failed"
    }
    return $raw
}

function Needs-CheckLinknameFlag {
    $version = Get-GoVersion
    if ($version -match 'go(\d+)\.(\d+)') {
        $major = [int]$Matches[1]
        $minor = [int]$Matches[2]
        return ($major -gt 1) -or ($major -eq 1 -and $minor -ge 23)
    }
    return $false
}

function Resolve-LocalSdkDir {
    $localProps = Join-Path $rootDir 'local.properties'
    if (-not (Test-Path $localProps)) {
        return $null
    }

    foreach ($line in Get-Content -LiteralPath $localProps) {
        if ($line -match '^\s*sdk\.dir\s*=(.+)\s*$') {
            return $Matches[1].Replace('\:', ':').Replace('\\', '\')
        }
    }

    return $null
}

function Resolve-NdkDir {
    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
        return $env:ANDROID_NDK_HOME
    }

    if ($env:ANDROID_NDK_ROOT -and (Test-Path $env:ANDROID_NDK_ROOT)) {
        return $env:ANDROID_NDK_ROOT
    }

    $sdkDir = Resolve-LocalSdkDir
    if (-not $sdkDir -and $env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        $sdkDir = $env:ANDROID_SDK_ROOT
    }

    if (-not $sdkDir) {
        return $null
    }

    $ndkRoot = Join-Path $sdkDir 'ndk'
    if (-not (Test-Path $ndkRoot)) {
        return $null
    }

    $latest = Get-ChildItem -LiteralPath $ndkRoot -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
    if ($null -eq $latest) {
        return $null
    }

    return $latest.FullName
}

function Build-Abi {
    param(
        [Parameter(Mandatory = $true)][string]$Abi,
        [Parameter(Mandatory = $true)][string]$NdkDir,
        [Parameter(Mandatory = $true)][string]$HostTag,
        [Parameter(Mandatory = $true)][bool]$UseCheckLinkname
    )

    switch ($Abi) {
        'arm64-v8a' {
            $goArch = 'arm64'
            $clangPrefix = 'aarch64-linux-android'
        }
        'armeabi-v7a' {
            $goArch = 'arm'
            $clangPrefix = 'armv7a-linux-androideabi'
        }
        'x86_64' {
            $goArch = 'amd64'
            $clangPrefix = 'x86_64-linux-android'
        }
        default {
            throw "Unsupported ABI: $Abi"
        }
    }

    $ccBase = Join-Path $NdkDir "toolchains/llvm/prebuilt/$HostTag/bin/$($clangPrefix)$apiLevel-clang"
    $cc = @(
        "$ccBase.cmd",
        "$ccBase.exe",
        $ccBase
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $cc) {
        throw "Compiler not found: $ccBase[.cmd|.exe]"
    }

    $cacheDir = Join-Path $rootDir '.gocache/go-build'
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    $env:GOCACHE = $cacheDir

    Push-Location $goDir
    try {
        $outDir = Join-Path $rootDir "app/src/main/jniLibs/$Abi"
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null

        Write-Host "Building $Abi -> $outDir/libclient.so"

        $env:GOOS = 'android'
        $env:GOARCH = $goArch
        $env:CGO_ENABLED = '1'
        $env:CC = $cc

        if ($UseCheckLinkname) {
            & go build -trimpath -ldflags=-checklinkname=0 -o (Join-Path $outDir 'libclient.so') .\go_client
        } else {
            & go build -trimpath -o (Join-Path $outDir 'libclient.so') .\go_client
        }

        if ($LASTEXITCODE -ne 0) {
            throw "go build failed for $Abi"
        }

        Write-Host "Done: $outDir/libclient.so"
    } finally {
        Pop-Location
    }
}

$ndkDir = Resolve-NdkDir
if (-not $ndkDir) {
    throw "NDK not found. Set ANDROID_NDK_HOME or install NDK via Android Studio."
}

$hostTag = 'windows-x86_64'
$useCheckLinkname = Needs-CheckLinknameFlag

foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    Build-Abi -Abi $abi -NdkDir $ndkDir -HostTag $hostTag -UseCheckLinkname $useCheckLinkname
}
