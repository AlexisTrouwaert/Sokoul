param([string]$Device = '192.168.0.132:5555')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# A shell-only test harness, never packaged into Sokoul or installed as an app.
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$androidJar = Join-Path $sdkRoot 'platforms/android-35/android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw 'Android SDK platform 35 is required for this tested harness.' }
$d8 = Join-Path $sdkRoot 'build-tools/35.0.0/lib/d8.jar'
if (-not (Test-Path -LiteralPath $d8)) { throw 'Android SDK Build Tools 35.0.0 are required.' }
$probeDir = Join-Path $env:TEMP 'sokoul-guide-input-probe'
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$driverSource = Join-Path $PSScriptRoot 'GuideKeyDriver.java'
& javac -source 8 -target 8 -Xlint:-options -classpath $androidJar -d $probeDir $driverSource
if ($LASTEXITCODE -ne 0) { throw 'Key driver compilation failed.' }
& java -cp $d8 com.android.tools.r8.D8 --lib $androidJar --min-api 30 --output $probeDir (Join-Path $probeDir 'GuideKeyDriver.class')
if ($LASTEXITCODE -ne 0) { throw 'Key driver DEX generation failed.' }
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
& $adb -s $Device push (Join-Path $probeDir 'classes.dex') /data/local/tmp/sokoul-guide-keydriver.dex
if ($LASTEXITCODE -ne 0) { throw 'Key driver upload failed.' }
Write-Output 'Shell key driver ready. Keep the guide focused before running persistent performance trials.'
