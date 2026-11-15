param(
    [string]$PairAddress = '192.168.129.22:40877',
    [string]$ConnectAddress = '',
    [string]$ApkPath = ''
)

# Run in the user's normal Windows terminal. Never change profile environment variables.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$ApkPath) {
    $ApkPath = Join-Path $projectRoot 'build-output/tv/outputs/apk/release/tv-release.apk'
}
if (!(Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'Release APK not found.' }
if ($PairAddress -notmatch '^([^\s:]+):(\d{1,5})$') { throw 'PairAddress must be host:port.' }
$deviceHost = $Matches[1]
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($adbCommand) { $adbCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe' }
if (!(Test-Path -LiteralPath $adbPath)) { throw 'ADB is not installed.' }

& $adbPath version
if ($LASTEXITCODE -ne 0) { throw 'ADB could not start. Run this script in a normal Windows terminal.' }

Write-Host 'Keep the pairing-code screen open on the target TV. Enter its current code at the ADB prompt.'
& $adbPath pair $PairAddress
if ($LASTEXITCODE -ne 0) { throw 'Pairing failed. Check the current pairing address and code on the TV.' }

# The pairing port and the connection port are separate. Discover the connection service.
if (!$ConnectAddress) {
    $servicePattern = [regex]::Escape($deviceHost) + ':\d{1,5}'
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $services = @(& $adbPath mdns services)
        $candidates = @($services | Where-Object { $_ -match '_adb-tls-connect\._tcp' } |
            ForEach-Object { [regex]::Matches($_, $servicePattern) | ForEach-Object { $_.Value } } | Select-Object -Unique)
        if ($candidates.Count -eq 1) { $ConnectAddress = $candidates[0]; break }
        Start-Sleep -Seconds 2
    }
}
if (!$ConnectAddress) {
    $ConnectAddress = Read-Host 'Enter the connection IP:port from the main Wireless debugging screen (not the pairing screen)'
}
if ($ConnectAddress -notmatch '^([^\s:]+):(\d{1,5})$' -or $Matches[1] -ne $deviceHost) {
    throw 'The connection address must use the same host as the pairing address.'
}

& $adbPath connect $ConnectAddress
if ($LASTEXITCODE -ne 0) { throw 'Connection failed.' }
$state = @(& $adbPath -s $ConnectAddress get-state)
if ($LASTEXITCODE -ne 0 -or 'device' -notin $state) { throw 'The target is not authorized and online.' }
Write-Host 'Connected target model:'
& $adbPath -s $ConnectAddress shell getprop ro.product.model
if ($LASTEXITCODE -ne 0) { throw 'Could not query the target model.' }

# Preserve data and fail on incompatible signatures; never uninstall or clear app data.
& $adbPath -s $ConnectAddress install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; no uninstall or data reset was attempted.' }
& $adbPath -s $ConnectAddress shell am start -n 'com.sokoul.tv/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'APK installed, but launching the activity failed.' }
Write-Host 'Sokoul APK installed and launch requested.'
