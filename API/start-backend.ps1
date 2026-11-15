<#
  Starts the whole local Sokoul backend in Docker: MySQL, Redis, Mainapi (25565), proxiesembed (25569), the watch
  party (25566). The historical web site (8088) is opt-in with -WithWeb.
  Settings live in %LOCALAPPDATA%\SokoulApi\local.env (outside OneDrive; never printed).
  Usage: powershell -ExecutionPolicy Bypass -File API\start-backend.ps1        Stop: add -Stop
#>
param(
    [switch]$Stop,
    [switch]$Status,
    [switch]$WithWeb,
    [string]$LanAddress
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dir = Join-Path $env:LOCALAPPDATA 'SokoulApi'
$envFile = Join-Path $dir 'local.env'
$compose = Join-Path $root 'docker-compose.yml'
$env:SOKOUL_ENV_FILE = $envFile

function Compose {
    $options = @('--env-file', $envFile, '-f', $compose)
    if ($WithWeb) { $options += @('--profile', 'web') }
    docker compose @options @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed. Check Docker Desktop and run this script with -Status.' }
}

if ($Stop -and $Status) { throw 'Choose either -Stop or -Status.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is required.' }
if ($Stop -or $Status) {
    if (-not (Test-Path -LiteralPath $envFile)) { throw 'Backend is not configured. Run this script without -Status or -Stop first.' }
    if ($Stop) { Compose --profile web down; return }
    Compose ps --all
    $unhealthy = $false
    foreach ($service in @(@('Mainapi', 25565), @('Proxy', 25569), @('WatchParty', 25566))) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($service[1])/health" -TimeoutSec 5
            Write-Host "$($service[0]): HTTP $($response.StatusCode)"
        } catch { Write-Host "$($service[0]): unavailable or not healthy"; $unhealthy = $true }
    }
    if ($unhealthy) { throw 'One or more backend HTTP health checks failed.' }
    return
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required for schema initialization.' }
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not ready. Start its Linux engine and retry.' }
New-Item -ItemType Directory -Force $dir | Out-Null

function New-Secret([int]$bytes = 24) {
    $b = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    ([Convert]::ToBase64String($b)).Replace('+', 'a').Replace('/', 'b').Replace('=', 'c')
}

# The address the app (PC or TV) uses to reach the media proxy: this PC's address on the network, found again at each
# start because the router may change it.
$lan = $LanAddress
if ($lan) {
    $parsedAddress = $null
    if (-not [System.Net.IPAddress]::TryParse($lan, [ref]$parsedAddress) -or $parsedAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        throw 'LanAddress must be an IPv4 address of this PC.'
    }
} else { $lan = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.PrefixOrigin -in 'Dhcp', 'Manual' -and $_.IPAddress -notlike '169.*' -and $_.InterfaceAlias -notmatch 'vEthernet|WSL|Loopback' } |
    Select-Object -First 1).IPAddress }
if (-not $lan) { $lan = '127.0.0.1' }

$lines = [System.Collections.Generic.List[string]]::new()
if (Test-Path $envFile) { $lines.AddRange([string[]]@(Get-Content $envFile)) }
function Set-Value([string]$name, [string]$value, [switch]$OnlyIfMissing) {
    $i = $null
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match "^$name=") { $i = $index; break }
    }
    if ($null -ne $i -and $lines.Count -gt 0) { if (-not $OnlyIfMissing) { $lines[$i] = "$name=$value" } } else { $lines.Add("$name=$value") }
}
if ($lines.Count -eq 0) {
    # Same TMDB key lookup as the app's build. Never printed.
    $tmdb = ''
    foreach ($file in @($env:SOKOUL_KEYS_FILE, (Join-Path $root '..\local.properties')) | Where-Object { $_ }) {
        if (Test-Path $file) {
            $line = Get-Content $file | Where-Object { $_ -match '^\s*sokoul\.key\.tmdb\s*=' } | Select-Object -First 1
            if ($line) { $tmdb = ($line -split '=', 2)[1].Trim(); break }
        }
    }
    Set-Value TMDB_API_KEY $tmdb
}
Set-Value PORT 25565 -OnlyIfMissing
Set-Value NODE_ENV development -OnlyIfMissing
Set-Value NUM_WORKERS 2 -OnlyIfMissing
Set-Value DB_HOST 127.0.0.1 -OnlyIfMissing
Set-Value DB_PORT 33069 -OnlyIfMissing
Set-Value DB_USER root -OnlyIfMissing
Set-Value DB_PASSWORD (New-Secret) -OnlyIfMissing
Set-Value DB_NAME sokoul -OnlyIfMissing
Set-Value DB_POOL_CONNECTION_LIMIT 10 -OnlyIfMissing
Set-Value DB_POOL_QUEUE_LIMIT 200 -OnlyIfMissing
Set-Value REDIS_HOST 127.0.0.1 -OnlyIfMissing
Set-Value REDIS_PORT 63799 -OnlyIfMissing
Set-Value REDIS_PASSWORD (New-Secret) -OnlyIfMissing
Set-Value JWT_SECRET (New-Secret 48) -OnlyIfMissing
Set-Value MEDIA_SIGNING_SECRET (New-Secret 48) -OnlyIfMissing
Set-Value INTERNAL_API_KEY (New-Secret 32) -OnlyIfMissing
Set-Value VAPID_PUBLIC_KEY '' -OnlyIfMissing
Set-Value VAPID_PRIVATE_KEY '' -OnlyIfMissing
Set-Value VAPID_SUBJECT '' -OnlyIfMissing
Set-Value PUBLIC_API_BASE "http://${lan}:25565"
Set-Value PROXY_SERVER_URL "http://${lan}:25569/proxy"
Set-Value PROXIESEMBED_PUBLIC_URL "http://${lan}:25569"
Set-Value WATCHPARTY_PUBLIC_URL "http://${lan}:25566"
Set-Value SITE_PUBLIC_URL "http://${lan}:8088"
[System.IO.File]::WriteAllLines($envFile, $lines, [System.Text.UTF8Encoding]::new($false))

# Report legacy native services occupying backend ports; never terminate unrelated processes.
# Docker's own relay is left alone.
foreach ($port in 25565, 25566, 25569) {
    Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -Unique OwningProcess |
        ForEach-Object { Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue } |
        Where-Object { $_.ProcessName -in 'node', 'python', 'pythonw' } |
        ForEach-Object { throw "Port $port is occupied by $($_.ProcessName) (PID $($_.Id)). Stop that service explicitly before starting Docker." }
}
Start-Sleep -Milliseconds 500

Write-Host 'Database and cache...'
Compose up -d --wait mysql redis
# Mainapi expects tables it does not create itself; then the access key the app uses.
node (Join-Path $root 'init-local-schema.js'); if ($LASTEXITCODE -ne 0) { throw 'Schema init failed.' }
node (Join-Path $root 'seed-local-key.js'); if ($LASTEXITCODE -ne 0) { throw 'Access key seeding failed.' }
Write-Host 'Services (built on first run, a few minutes)...'
# BuildKit cannot open cloud-placeholder source files directly on some Windows desktops.
# Read their bytes from this checkout into ordinary temporary files; no alternate checkout is used.
$buildContext = node (Join-Path $root 'prepare-build-context.js')
if ($LASTEXITCODE -ne 0 -or -not $buildContext) { throw 'Backend build context preparation failed.' }
$env:SOKOUL_MAINAPI_CONTEXT = Join-Path $buildContext 'Mainapi'
$env:SOKOUL_PROXY_CONTEXT = Join-Path $buildContext 'proxiesembed'
$env:SOKOUL_WATCHPARTY_CONTEXT = Join-Path $buildContext 'watchpartyAPI'
$services = @('mainapi', 'proxiesembed', 'watchparty')
if ($WithWeb) { $services += 'web' }
Compose up -d --build --wait --wait-timeout 180 @services
Write-Host "Ready: Mainapi http://${lan}:25565  proxiesembed http://${lan}:25569  watch party http://${lan}:25566"
if ($WithWeb) { Write-Host "Optional legacy site: http://${lan}:8088" }
if ($lan -eq '127.0.0.1') { Write-Warning 'No LAN address found. TV access requires -LanAddress with this PC IPv4 address.' }
