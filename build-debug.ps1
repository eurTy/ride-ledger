param(
    [string]$Sdk = 'E:\Android_Sdk',
    [string]$JavaHome = 'E:\Android\jbr'
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$build = Join-Path $project 'build'
$buildFull = [System.IO.Path]::GetFullPath($build)
if (-not $buildFull.StartsWith($project + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Build directory must be inside this project.'
}
if (Test-Path -LiteralPath $build) { Remove-Item -LiteralPath $build -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $build 'classes'),(Join-Path $build 'dex'),(Join-Path $build 'dist') | Out-Null

$tools = Join-Path $Sdk 'build-tools\36.1.0'
$androidJar = Join-Path $Sdk 'platforms\android-36.1\android.jar'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
foreach ($required in @($javac, $jar, $keytool, $androidJar,
        (Join-Path $tools 'aapt2.exe'), (Join-Path $tools 'd8.bat'),
        (Join-Path $tools 'zipalign.exe'), (Join-Path $tools 'apksigner.bat'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing required existing tool: $required" }
}

$sources = @(rg --files (Join-Path $project 'app\src\main\java') -g '*.java')
if ($sources.Count -eq 0) { throw 'No Java sources found.' }
& $javac --release 17 -encoding UTF-8 -classpath $androidJar -d (Join-Path $build 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

$classJar = Join-Path $build 'classes.jar'
& $jar --create --file $classJar -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'Class archive failed.' }

$env:JAVA_HOME = $JavaHome
& (Join-Path $tools 'd8.bat') --lib $androidJar --min-api 26 --output (Join-Path $build 'dex') $classJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }

$unsigned = Join-Path $build 'unsigned.apk'
& (Join-Path $tools 'aapt2.exe') link -o $unsigned -I $androidJar --manifest (Join-Path $project 'app\src\main\AndroidManifest.xml')
if ($LASTEXITCODE -ne 0) { throw 'APK resource packaging failed.' }
& $jar --update --file $unsigned -C (Join-Path $build 'dex') classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Adding DEX to APK failed.' }

$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $tools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }

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
