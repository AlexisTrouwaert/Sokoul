# Live check of the running Sokoul PC window. Commands go through the app's debug driver (SOKOUL_DEBUG), never the real
# keyboard, and the capture covers only this window, without bringing it to the front.
# Usage: live.ps1 [-Start] [-Stop] [-Keys DOWN,RIGHT,ENTER,PINCH_IN] [-Name capture] [-WaitMs 900] [-LeftHalf]
# Never capture the setup screen in full: it shows the end of each key. Use -LeftHalf when it could appear.
param(
    [string]$Name = '',
    [string[]]$Keys = @(),
    [int]$WaitMs = 900,
    [switch]$Start,
    [switch]$Stop,
    [switch]$LeftHalf,
    [string]$Lang = ''
)
$buildRoot = if ($env:SOKOUL_BUILD_ROOT) { $env:SOKOUL_BUILD_ROOT } else { Join-Path (Split-Path $PSScriptRoot -Parent) 'build-output' }
$exe = Join-Path $buildRoot 'app\compose\binaries\main\app\Sokoul PC\Sokoul PC.exe'
$data = Join-Path $env:APPDATA 'SokoulPC'
function Find-Window { Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -like 'Sokoul PC*' -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1 }
if ($Stop) {
    $p = Find-Window
    if ($p) { [void]$p.CloseMainWindow(); $n = 0; while (-not $p.HasExited -and $n -lt 40) { Start-Sleep -Milliseconds 250; $n++ }; if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force } }
    'stopped'
    if (-not $Start) { return }
}
if ($Start) {
    $env:SOKOUL_DEBUG = '1'
    if ($Lang) { $env:SOKOUL_LANG = $Lang }
    if (Test-Path (Join-Path $data 'crash.log')) { Move-Item (Join-Path $data 'crash.log') (Join-Path $data 'crash-last.log') -Force }
    Remove-Item (Join-Path $data 'debug.log'), (Join-Path $data 'debug-keys.txt') -ErrorAction SilentlyContinue
    Start-Process $exe -WorkingDirectory (Split-Path $exe)
    $n = 0; while (-not (Find-Window) -and $n -lt 60) { Start-Sleep -Milliseconds 500; $n++ }
    'started'
}
$w = Find-Window
if (-not $w) { 'window not found'; return }
Add-Type -AssemblyName System.Drawing
if (-not ('SokoulWin' -as [type])) {
Add-Type @"
using System; using System.Runtime.InteropServices;
public class SokoulWin {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr d, uint f);
  public struct R { public int L,T,Ri,B; }
}
"@
}
[void][SokoulWin]::SetProcessDPIAware()
if ([SokoulWin]::IsIconic($w.MainWindowHandle)) { [void][SokoulWin]::ShowWindow($w.MainWindowHandle, 4); Start-Sleep -Milliseconds 500; 'window was minimized: restored' }
$Keys = @($Keys | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
if ($Keys.Count -gt 0) {
    [IO.File]::WriteAllLines((Join-Path $data 'debug-keys.txt'), [string[]]$Keys)
    Start-Sleep -Milliseconds (250 + 380 * $Keys.Count)
}
if ($Name) {
    Start-Sleep -Milliseconds $WaitMs
    $r = New-Object SokoulWin+R
    [void][SokoulWin]::GetWindowRect($w.MainWindowHandle, [ref]$r)
    $bw = $r.Ri - $r.L; $bh = $r.B - $r.T
    $b = New-Object System.Drawing.Bitmap $bw, $bh
    $g = [System.Drawing.Graphics]::FromImage($b)
    $hdc = $g.GetHdc(); [void][SokoulWin]::PrintWindow($w.MainWindowHandle, $hdc, 2); $g.ReleaseHdc($hdc)
    $dir = Join-Path $PSScriptRoot '..\renders\live'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $out = Join-Path $dir "$Name.png"
    if ($LeftHalf) { $c = $b.Clone((New-Object System.Drawing.Rectangle 0, 0, ([int]($bw / 2)), $bh), $b.PixelFormat); $c.Save($out); $c.Dispose() } else { $b.Save($out) }
    $g.Dispose(); $b.Dispose()
    "saved $Name"
}
$w.Refresh()
"responding=$($w.Responding) ramMB=$([math]::Round($w.WorkingSet64/1MB)) crash=$(Test-Path (Join-Path $data 'crash.log'))"
