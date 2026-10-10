param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$OutputDirectory = & python -B "$PSScriptRoot/benchmark_layout.py" --group runs --path $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Output must belong to benchmarks/runs or OS temporary scratch.' }
$package = if ($env:MESHLIVE_APP_ID) { $env:MESHLIVE_APP_ID } else { 'io.github.meshlive.app' }
$remote = "/sdcard/Android/data/$package/files/session-benchmarks/."
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "Output directory already exists: $OutputDirectory"
}
$state = (& adb -s $Serial get-state 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
    throw "ADB device $Serial is not available."
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
& adb -s $Serial pull $remote $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Could not pull session metrics.' }
