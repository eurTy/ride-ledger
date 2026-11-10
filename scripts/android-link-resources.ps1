param(
    [string]$Aapt2,
    [string]$AndroidJar,
    [string]$Manifest,
    [string]$BuildDir
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$compiled = Join-Path $BuildDir 'android-resources.zip'
$generated = Join-Path $BuildDir 'generated'
Push-Location -LiteralPath $project
try {
    New-Item -ItemType Directory -Path $generated -Force | Out-Null
    # Use a relative resource directory because aapt2 on Windows cannot enumerate the absolute CJK path.
    & $Aapt2 compile --dir 'app/src/main/res' -o $compiled
    if ($LASTEXITCODE -ne 0) { throw 'Android resource compilation failed.' }
    & $Aapt2 link -o (Join-Path $BuildDir 'unsigned.apk') -I $AndroidJar --manifest $Manifest `
        --java $generated --custom-package com.rideledger $compiled
    if ($LASTEXITCODE -ne 0) { throw 'Android resource linking failed.' }
} finally { Pop-Location }
