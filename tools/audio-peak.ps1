# Reads how loud the PC's default output is right now, from Windows' own meter (0 = silence, 1 = full scale).
# It proves a sound is really going out, which no status inside the app can. Takes N readings over a few seconds.
param([int]$Readings = 20, [int]$GapMs = 150)
if (-not ('SokoulPeak' -as [type])) {
Add-Type @"
using System; using System.Runtime.InteropServices;
[Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"), ComImport] class MMDeviceEnumeratorCom {}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator { int NotImpl1(); [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device); }
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice { [PreserveSig] int Activate(ref Guid iid, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o); }
[Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioMeterInformation { [PreserveSig] int GetPeakValue(out float peak); }
public static class SokoulPeak {
  public static float Read() {
    var e = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom(); IMMDevice d; e.GetDefaultAudioEndpoint(0, 1, out d);
    var iid = typeof(IAudioMeterInformation).GUID; object o; d.Activate(ref iid, 23, IntPtr.Zero, out o);
    float p; ((IAudioMeterInformation)o).GetPeakValue(out p); return p;
  }
}
"@
}
# The meter first answers with the peak since it was last read, however long ago that was.
[void][SokoulPeak]::Read(); Start-Sleep -Milliseconds $GapMs
$values = 1..$Readings | ForEach-Object { [SokoulPeak]::Read(); Start-Sleep -Milliseconds $GapMs }
$max = ($values | Measure-Object -Maximum).Maximum
$nonZero = ($values | Where-Object { $_ -gt 0.001 }).Count
"{""max"":$([math]::Round($max, 3).ToString([Globalization.CultureInfo]::InvariantCulture)),""active"":$nonZero,""readings"":$Readings}"
