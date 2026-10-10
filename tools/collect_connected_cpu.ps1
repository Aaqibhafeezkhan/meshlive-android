param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 60,
    [ValidateRange(1, 60)]
    [int]$IntervalSeconds = 2,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$OutputPath = & python -B "$PSScriptRoot/benchmark_layout.py" --group runs --path $OutputPath
if ($LASTEXITCODE -ne 0) { throw 'Output must belong to benchmarks/runs or OS temporary scratch.' }
$package = if ($env:MESHLIVE_APP_ID) { $env:MESHLIVE_APP_ID } else { 'io.github.meshlive.app' }
if (Test-Path -LiteralPath $OutputPath) {
    throw "Output file already exists: $OutputPath"
}
$state = (& adb -s $Serial get-state 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
    throw "ADB device $Serial is not available."
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
'elapsed_s,app_cpu_percent,codec_cpu_percent,audio_cpu_percent,bluetooth_cpu_percent' |
    Set-Content -LiteralPath $OutputPath -Encoding utf8

$watch = [Diagnostics.Stopwatch]::StartNew()
while ($watch.Elapsed.TotalSeconds -lt $DurationSeconds) {
    $groups = [ordered]@{ app = 0.0; codec = 0.0; audio = 0.0; bluetooth = 0.0 }
    $lines = & adb -s $Serial shell top -b -n 1 -o PID,CPU,ARGS
    if ($LASTEXITCODE -ne 0) { throw 'Could not collect CPU sample.' }
    foreach ($line in $lines) {
        if ($line -notmatch '^\s*\d+\s+([0-9.]+)\s+(.+?)\s*$') { continue }
        $cpu = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        $process = $Matches[2]
        if ($process -match [regex]::Escape($package)) { $groups.app += $cpu }
        elseif ($process -match 'media\.swcodec|media\.codec|codec2|android\.hardware\.media') { $groups.codec += $cpu }
        elseif ($process -match 'audioserver|audioflinger|android\.hardware\.audio') { $groups.audio += $cpu }
        elseif ($process -match 'com\.android\.bluetooth|android\.hardware\.bluetooth|bluetooth-service|bluetooth@') { $groups.bluetooth += $cpu }
    }
    $row = [string]::Format([Globalization.CultureInfo]::InvariantCulture,
        '{0:F3},{1:F2},{2:F2},{3:F2},{4:F2}', @(
        $watch.Elapsed.TotalSeconds,
        $groups.app,
        $groups.codec,
        $groups.audio,
        $groups.bluetooth
    ))
    Add-Content -LiteralPath $OutputPath -Value $row -Encoding utf8
    Start-Sleep -Seconds $IntervalSeconds
}

Write-Output "Wrote connected CPU samples to $OutputPath"
