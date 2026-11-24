# Builds Sokoul with the private API keys and installs it on the Google TV (Chromecast HD), keeping its data.
# The wireless-debugging port changes when the TV restarts: pass the one shown on the TV with -Device.
param([string]$Device = '192.168.129.22:35787')
& (Join-Path $PSScriptRoot 'tablet.ps1') -Device $Device
exit $LASTEXITCODE
