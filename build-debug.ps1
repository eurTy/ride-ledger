param(
    [switch]$SkipSigning,
    [string]$BuildDir = 'build',
    [string]$Sdk = 'E:\Android_Sdk',
    [string]$JavaHome = 'E:\Android\jbr'
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$build = Join-Path $project $BuildDir
$buildFull = [System.IO.Path]::GetFullPath($build)
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $project 'build'))
if ($buildFull -ne $buildRoot -and -not $buildFull.StartsWith(
        $buildRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Build directory must be inside this project build folder.'
}
foreach ($name in @('classes', 'dex', 'dist')) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $build $name))
    if (-not $target.StartsWith($buildFull + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Build output path is outside the selected build directory.'
    }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
foreach ($name in @('classes.jar', 'unsigned.apk', 'aligned.apk')) {
    $target = Join-Path $build $name
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
}
New-Item -ItemType Directory -Path (Join-Path $build 'classes'),(Join-Path $build 'dex'),(Join-Path $build 'dist') | Out-Null
$toolTemp = Join-Path $build 'tmp'
New-Item -ItemType Directory -Path $toolTemp -Force | Out-Null
$env:TEMP = $toolTemp
$env:TMP = $toolTemp

$tools = Join-Path $Sdk 'build-tools\36.1.0'
$androidJar = Join-Path $Sdk 'platforms\android-36.1\android.jar'
$qrCore = & (Join-Path $project 'scripts/android-qr-core.ps1')
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$requiredTools = @($javac, $jar, $androidJar,
    (Join-Path $tools 'aapt2.exe'), (Join-Path $tools 'd8.bat'),
    (Join-Path $tools 'zipalign.exe'))
if (-not $SkipSigning) {
    $requiredTools += $keytool
    $requiredTools += (Join-Path $tools 'apksigner.bat')
}
foreach ($required in $requiredTools) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing required existing tool: $required" }
}

$sources = @(rg --files (Join-Path $project 'app\src\main\java') -g '*.java')
if ($sources.Count -eq 0) { throw 'No Java sources found.' }
& $javac --release 17 -encoding UTF-8 -classpath "$androidJar;$qrCore" -d (Join-Path $build 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

$classJar = Join-Path $build 'classes.jar'
& $jar --create --file $classJar -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'Class archive failed.' }

$env:JAVA_HOME = $JavaHome
& (Join-Path $tools 'd8.bat') --lib $androidJar --min-api 26 --output (Join-Path $build 'dex') $classJar $qrCore
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }

$unsigned = Join-Path $build 'unsigned.apk'
$aapt2 = Join-Path $tools 'aapt2.exe'
$compiledResources = Join-Path $build 'android-resources.zip'
Push-Location -LiteralPath $project
try {
    # aapt2 on Windows cannot enumerate an absolute resource directory containing CJK characters.
    & $aapt2 compile --dir 'app/src/main/res' -o (Join-Path $BuildDir 'android-resources.zip')
    if ($LASTEXITCODE -ne 0) { throw 'APK resource compilation failed.' }
} finally { Pop-Location }
& $aapt2 link -o $unsigned -I $androidJar --manifest (Join-Path $project 'app\src\main\AndroidManifest.xml') $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'APK resource packaging failed.' }
& $jar --update --file $unsigned -C (Join-Path $build 'dex') classes.dex `
    -C (Join-Path $project 'app/src/main/resources') META-INF/ZXING-LICENSE.txt
if ($LASTEXITCODE -ne 0) { throw 'Adding DEX to APK failed.' }

$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $tools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }

if ($SkipSigning) {
    $apk = Join-Path $build 'dist\ride-ledger-unsigned.apk'
    Copy-Item -LiteralPath $aligned -Destination $apk
    Write-Output "Unsigned APK (inspection only; cannot be installed): $apk"
    return
}

$signing = Join-Path $project 'signing'
$key = Join-Path $signing 'debug.keystore'
if (-not (Test-Path -LiteralPath $key)) {
    New-Item -ItemType Directory -Path $signing -Force | Out-Null
    & $keytool -genkeypair -keystore $key -alias androiddebugkey -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Creating debug key failed.' }
}

$apk = Join-Path $build 'dist\ride-ledger-debug.apk'
& (Join-Path $tools 'apksigner.bat') sign --ks $key --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& (Join-Path $tools 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
Write-Output "APK: $apk"
