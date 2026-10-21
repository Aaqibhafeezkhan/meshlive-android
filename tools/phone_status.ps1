# Live list of the test phones connected over USB (adb), by name, with battery and charging state.
# Double-click "Phones.bat" on the Desktop to open it; close the window to stop. Read-only: it never changes a phone.
$adb = "C:\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$names = [ordered]@{
    "RZCX9288N4N"      = "S24"
    "RZCT91WGMXF"      = "S22"
    "RZ8R10L2QXT"      = "M31"
    "K6AIGF001968E8P"  = "ASUS"
    "cb0e29be7d73"     = "Xiaomi"
    "U8UOWKQKPZIJFM8D" = "A15s"
    "f64af0"           = "Redmi Note 9 Pro"
    "RZCXC0KKBRZ"      = "A55"
}
$host.UI.RawUI.WindowTitle = "MeshLive phones"
while ($true) {
    $lines = & $adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "\S" }
    $seen = @{}
    foreach ($l in $lines) { $p = $l -split "\s+"; $seen[$p[0]] = $p[1] }
    Clear-Host
    Write-Host ("MeshLive test phones   " + (Get-Date -Format "HH:mm:ss") + "   (refreshes every 5 s)`n")
    foreach ($serial in $names.Keys) {
        $name = $names[$serial].PadRight(18)
        if ($seen[$serial] -eq "device") {
            $bat = & $adb -s $serial shell dumpsys battery 2>$null
            $level = ($bat | Select-String "^\s*level:\s*(\d+)" | Select-Object -First 1).Matches.Groups[1].Value
            $charging = if ($bat -match "(AC|USB|Wireless) powered: true") { "charging" } else { "NOT charging" }
            Write-Host ("  OK       " + $name + ("{0,3}% " -f $level) + $charging) -ForegroundColor Green
        } elseif ($seen.ContainsKey($serial)) {
            Write-Host ("  CHECK    " + $name + "connected but " + $seen[$serial] + " (tap Allow on the phone?)") -ForegroundColor Yellow
        } else {
            Write-Host ("  missing  " + $name) -ForegroundColor DarkGray
        }
    }
    $unknown = $seen.Keys | Where-Object { -not $names.Contains($_) }
    foreach ($u in $unknown) { Write-Host ("  NEW      " + $u + " (" + $seen[$u] + ")") -ForegroundColor Cyan }
    Start-Sleep -Seconds 5
}
