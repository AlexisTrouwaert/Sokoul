# Windows entry point; gradlew.bat handles caches and Android preferences.
$ErrorActionPreference = 'Stop'
if (!$env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
}
if (!(Test-Path (Join-Path $env:ANDROID_HOME 'platforms'))) {
    [Console]::Error.WriteLine('Android SDK not found. Set ANDROID_HOME to an installed SDK.')
    exit 1
}
& (Join-Path (Split-Path $PSScriptRoot -Parent) 'gradlew.bat') :tv:assembleRelease --console=plain @args
exit $LASTEXITCODE
