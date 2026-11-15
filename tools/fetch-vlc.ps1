param(
    [string]$Version = '3.0.21',
    [string]$Target = ''
)

# Downloads the official 64-bit VLC for Windows from VideoLAN and keeps what the PC app embeds:
# libvlc.dll, libvlccore.dll and plugins/. libVLC is not stored in Git (see docs/building.md).
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$Target) { $Target = Join-Path $projectRoot 'app/app-resources/windows/vlc' }

if (Test-Path -LiteralPath (Join-Path $Target 'libvlc.dll')) {
    Write-Host "libVLC is already present in $Target. Delete that folder first to download it again."
    exit 0
}

$name = "vlc-$Version-win64"
$url = "https://download.videolan.org/pub/videolan/vlc/$Version/win64/$name.zip"
$work = Join-Path ([IO.Path]::GetTempPath()) ("sokoul-vlc-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $zip = Join-Path $work "$name.zip"
    Write-Host "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

    # VideoLAN publishes a SHA-256 file next to each archive: the download must match it.
    $expected = ((Invoke-WebRequest -Uri "$url.sha256" -UseBasicParsing).Content -split '\s+')[0].Trim().ToLower()
    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
    if ($expected -ne $actual) { throw "Checksum mismatch for $name.zip." }

    Expand-Archive -LiteralPath $zip -DestinationPath $work
    $source = Join-Path $work "vlc-$Version"
    New-Item -ItemType Directory -Path $Target -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $source 'libvlc.dll'), (Join-Path $source 'libvlccore.dll') -Destination $Target
    Copy-Item -LiteralPath (Join-Path $source 'plugins') -Destination $Target -Recurse
    Write-Host "libVLC $Version installed in $Target"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
