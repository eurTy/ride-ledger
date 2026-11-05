$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $project 'build/android-deps'
$jar = Join-Path $dir 'zxing-core-3.5.4.jar'
$digest = '71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13'
function Test-QrDigest([string]$file) {
    $inputStream = [IO.File]::OpenRead($file)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($inputStream)).Replace('-', '') -eq $digest) }
    finally { $sha.Dispose(); $inputStream.Dispose() }
}
if ((Test-Path -LiteralPath $jar) -and (Test-QrDigest $jar)) {
    Write-Output $jar
    return
}
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$temporary = Join-Path $dir ([Guid]::NewGuid().ToString() + '.jar')
try {
    $uri = 'https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/core-3.5.4.jar'
    Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $temporary -TimeoutSec 45
    if (-not (Test-QrDigest $temporary)) {
        throw 'ZXing dependency failed SHA-256 verification.'
    }
    Move-Item -LiteralPath $temporary -Destination $jar -Force
    Write-Output $jar
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
