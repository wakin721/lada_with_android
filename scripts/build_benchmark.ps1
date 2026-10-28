param(
    [Parameter(Mandatory=$true)][string]$SdkPath,
    [Parameter(Mandatory=$true)][string]$JdkPath,
    [string]$BuildToolsVersion = '37.0.0',
    [string]$Platform = 'android-37.0'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = $JdkPath
$toolsPath = Join-Path $SdkPath "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkPath "platforms\$Platform\android.jar"
$buildPath = Join-Path $projectRoot 'artifacts\benchmark-build'
$runtimePath = Join-Path $projectRoot '.tools\ort-android'
foreach ($folder in @('classes','dex','generated')) { New-Item -ItemType Directory -Force -Path (Join-Path $buildPath $folder) | Out-Null }
if (-not (Test-Path -LiteralPath 'artifacts\benchmark-assets\models.json')) { throw 'Run export_detector_and_assets.py first' }
& "$JdkPath\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -classpath "$androidJar;$runtimePath\classes.jar" -d "$buildPath\classes" 'benchmark\android\src\com\wakin\ladabench\BenchmarkActivity.java'
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& "$toolsPath\d8.bat" --min-api 28 --lib $androidJar --output "$buildPath\dex" "$buildPath\classes\com\wakin\ladabench\BenchmarkActivity.class" "$runtimePath\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'DEX conversion failed' }
& "$toolsPath\aapt2.exe" link -I $androidJar --manifest 'benchmark\android\AndroidManifest.xml' --min-sdk-version 28 --target-sdk-version 35 --java "$buildPath\generated" -o "$buildPath\base.apk"
if ($LASTEXITCODE -ne 0) { throw 'Manifest linking failed' }
python 'scripts\package_benchmark.py'
if ($LASTEXITCODE -ne 0) { throw 'APK packing failed' }
& "$toolsPath\zipalign.exe" -f 4 "$buildPath\unsigned.apk" "$buildPath\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
$keyPath = Join-Path $buildPath 'benchmark-debug.jks'
if (-not (Test-Path -LiteralPath $keyPath)) {
    & "$JdkPath\bin\keytool.exe" -genkeypair -keystore $keyPath -storepass android -keypass android -alias benchmark -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Lada ONNX Benchmark Debug'
    if ($LASTEXITCODE -ne 0) { throw 'Debug signing key generation failed' }
}
& "$toolsPath\apksigner.bat" sign --ks $keyPath --ks-key-alias benchmark --ks-pass pass:android --key-pass pass:android --out 'artifacts\phone-benchmark\lada-onnx-benchmark.apk' "$buildPath\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& "$toolsPath\apksigner.bat" verify --verbose 'artifacts\phone-benchmark\lada-onnx-benchmark.apk'
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Write-Output (Join-Path $projectRoot 'artifacts\phone-benchmark\lada-onnx-benchmark.apk')
