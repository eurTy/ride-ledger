param(
    [string]$Sdk = 'E:\Android_Sdk',
    [string]$JavaHome = 'E:\Android\jbr'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$testBuild = Join-Path $project 'build\android-sync-tests'
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $project 'build'))
foreach ($folder in @('classes', 'dex')) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $testBuild $folder))
    if (-not $target.StartsWith($expectedRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Test output escaped the build directory.' }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
}
$tools = Join-Path $Sdk 'build-tools\36.1.0'
$androidJar = Join-Path $Sdk 'platforms\android-36.1\android.jar'
$qrCore = & (Join-Path $project 'scripts/android-qr-core.ps1')
$env:JAVA_HOME = $JavaHome
Set-Location -LiteralPath $project
$unsigned = Join-Path $testBuild 'unsigned.apk'
& (Join-Path $project 'scripts/android-link-resources.ps1') -Aapt2 (Join-Path $tools 'aapt2.exe') `
    -AndroidJar $androidJar -Manifest (Join-Path $PSScriptRoot 'android/AndroidManifest.xml') -BuildDir 'build/android-sync-tests'
$sources = @(rg --files 'app/src/main/java' -g '*.java')
$sources += @(rg --files 'tests/android' -g '*.java')
$sources += (Join-Path $testBuild 'generated/com/rideledger/R.java')
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -classpath "$androidJar;$qrCore" `
    -d (Join-Path $testBuild 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'Android sync test compilation failed.' }
$jar = Join-Path $testBuild 'classes.jar'
& (Join-Path $JavaHome 'bin\jar.exe') --create --file $jar -C (Join-Path $testBuild 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'Android test archive failed.' }
& (Join-Path $tools 'd8.bat') --lib $androidJar --min-api 26 --output (Join-Path $testBuild 'dex') $jar $qrCore
if ($LASTEXITCODE -ne 0) { throw 'Android test DEX generation failed.' }
& (Join-Path $JavaHome 'bin\jar.exe') --update --file $unsigned -C (Join-Path $testBuild 'dex') classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Android test APK DEX packaging failed.' }
$aligned = Join-Path $testBuild 'aligned.apk'
& (Join-Path $tools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'Android test APK alignment failed.' }
$apk = Join-Path $testBuild 'ride-ledger-sync-tests.apk'
& (Join-Path $tools 'apksigner.bat') sign --ks (Join-Path $project 'signing\debug.keystore') `
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'Android test APK signing failed.' }
Write-Output 'TEST_APK=build/android-sync-tests/ride-ledger-sync-tests.apk'
