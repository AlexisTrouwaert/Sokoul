param(
    [string]$Source = (Join-Path (Split-Path $PSScriptRoot -Parent) 'build-output/app/compose/binaries/main/app/Sokoul PC'),
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $Source).Path
$targetRoot = Join-Path $env:LOCALAPPDATA 'Programs/Sokoul'
$executable = 'Sokoul PC.exe'
if (!(Test-Path -LiteralPath (Join-Path $sourceRoot $executable)) -or
    !(Test-Path -LiteralPath (Join-Path $sourceRoot 'app/resources/vlc/libvlc.dll'))) {
    throw 'The source is not a complete Sokoul desktop distribution with libVLC.'
}
if ($sourceRoot.TrimEnd('\') -eq $targetRoot.TrimEnd('\')) { throw 'Source and installation must differ.' }

# Only close the existing installation; never terminate another checkout or an unresponsive app.
$running = @(Get-Process -Name 'Sokoul PC' -ErrorAction SilentlyContinue | Where-Object {
    $_.Path -eq (Join-Path $targetRoot $executable)
})
foreach ($process in $running) {
    if ($process.MainWindowHandle -ne 0) { [void]$process.CloseMainWindow() }
}
foreach ($process in $running) {
    if (!$process.HasExited -and !$process.WaitForExit(15000)) {
        throw 'The installed app did not close normally. Finish playback/recording and retry installation.'
    }
}
if (Test-Path -LiteralPath $targetRoot) {
    $backupRoot = Join-Path $env:TEMP ('sokoul-install-before-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $backupRoot | Out-Null
    Get-ChildItem -LiteralPath $targetRoot -Force | Copy-Item -Destination $backupRoot -Recurse -Force
    Write-Host ('Previous installation saved: ' + $backupRoot)
}
New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
Get-ChildItem -LiteralPath $sourceRoot -Force | Copy-Item -Destination $targetRoot -Recurse -Force
$files = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File)
foreach ($file in $files) {
    $relative = $file.FullName.Substring($sourceRoot.Length).TrimStart('\')
    $installed = Join-Path $targetRoot $relative
    if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $installed).Hash) {
        throw ('Installed file differs from the build: ' + $relative)
    }
}
Write-Host ('Verified ' + $files.Count + ' installed files. Desktop profile data was not copied or cleared.')
if (!$NoLaunch) { Start-Process -FilePath (Join-Path $targetRoot $executable) -WindowStyle Hidden }
