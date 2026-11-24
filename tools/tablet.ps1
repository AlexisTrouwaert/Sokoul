# Builds the TV/tablet APK with the private API keys, installs it on the tablet and starts Sokoul.
# Keys: $env:SOKOUL_KEYS_FILE, else ~/.sokoul-private/tv-keys.properties (outside the checkout, never committed).
param([string]$Device = '192.168.129.24:45153')
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
if (!$env:SOKOUL_KEYS_FILE) { $env:SOKOUL_KEYS_FILE = Join-Path $HOME '.sokoul-private\tv-keys.properties' }
if (!(Test-Path $env:SOKOUL_KEYS_FILE)) { Write-Host "Keys file missing: $env:SOKOUL_KEYS_FILE" -ForegroundColor Red; exit 1 }
& (Join-Path $PSScriptRoot 'build-tv.ps1')
if ($LASTEXITCODE -ne 0) { Write-Host 'Build failed.' -ForegroundColor Red; exit 1 }
$apk = Join-Path $root 'build-output\tv\outputs\apk\release\tv-release.apk'
adb connect $Device | Out-Null
adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host 'Install failed.' -ForegroundColor Red; exit 1 }
# Compiled ahead of time at once: installed with adb, the app otherwise runs interpreted until the system compiles it
# overnight, and the Chromecast HD dropped twice as many frames.
adb -s $Device shell cmd package compile -m speed -f com.sokoul.tv | Out-Null
adb -s $Device shell am force-stop com.sokoul.tv
adb -s $Device shell monkey -p com.sokoul.tv -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host 'Sokoul installed and started on the tablet.' -ForegroundColor Green
