# What is really on screen in the app's window, not what the window says it drew. Usage: screen-shot.ps1 -Name file
param([string]$Name = 'screen')
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class SokoulScreen {
    [StructLayout(LayoutKind.Sequential)] public struct R { public int L, T, Ri, B; }
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
}
"@
[void][SokoulScreen]::SetProcessDPIAware()
$w = Get-Process | Where-Object { $_.ProcessName -like 'Sokoul PC*' -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $w) { 'no window'; exit 1 }
[void][SokoulScreen]::SetForegroundWindow($w.MainWindowHandle)
Start-Sleep -Milliseconds 400
$r = New-Object SokoulScreen+R
[void][SokoulScreen]::GetWindowRect($w.MainWindowHandle, [ref]$r)
$bw = $r.Ri - $r.L; $bh = $r.B - $r.T
$b = New-Object System.Drawing.Bitmap $bw, $bh
$g = [System.Drawing.Graphics]::FromImage($b)
$g.CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $bw, $bh))
$dir = Join-Path $PSScriptRoot '..\renders\live'
$out = Join-Path $dir "$Name.png"
$b.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $b.Dispose()
"saved $Name ${bw}x$bh"
