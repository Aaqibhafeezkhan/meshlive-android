param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.-]{1,80}$')][string]$CaseName,
    [ValidateSet('av1', 'vp9', 'native_av1', 'libvpx', 'avc', 'hevc')][string]$Codec = 'av1',
    [string]$CodecName = '',
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [string]$InputName = 'reference-720p30-90.yuv',
    [string]$InputPath = '',
    [Parameter(Mandatory = $true)][string]$ReferencePath,
    [int]$Width = 1280,
    [int]$Height = 720,
    [int]$ReferenceWidth = 0,
    [int]$ReferenceHeight = 0,
    [int]$Fps = 30,
    [int]$Frames = 90,
    [int]$BitrateKbps = 400,
    [ValidateRange(0, 10)][int]$Complexity = 0,
    [ValidateSet('cbr', 'vbr', 'default')][string]$RateMode = 'cbr',
    [ValidateRange(1, 30)][int]$KeyFrameSeconds = 4,
    [double]$MotionStartSeconds = -1,
    [double]$MotionDurationSeconds = -1,
    [int]$TimeoutSeconds = 300,
    [ValidateRange(0, 8)][int]$NativeThreads = 0,
    [switch]$NativeRealtimeTools,
    [switch]$SkipScoring,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$OutputDirectory = & python -B "$PSScriptRoot/benchmark_layout.py" --group runs --path $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Output must belong to benchmarks/runs or OS temporary scratch.' }
$package = if ($env:MESHLIVE_APP_ID) { $env:MESHLIVE_APP_ID } else { 'io.github.meshlive.app' }
if ($InputName -notmatch '^[A-Za-z0-9_.-]+$') {
    throw 'InputName may contain only letters, digits, dot, underscore, and hyphen'
}
$remoteDirectory = "/sdcard/Android/data/$package/files/offline-benchmarks"
$remoteInputDirectory = "/data/user/0/$package/files/benchmark-input"
$remoteInput = "$remoteInputDirectory/$InputName"
$remoteTempInput = "/data/local/tmp/bluetooth-video-$InputName"
$remoteResult = "$remoteDirectory/$CaseName-result.json"
$videoExtension = switch ($Codec) { 'avc' { 'h264' } 'hevc' { 'hevc' } default { 'ivf' } }
$remoteVideo = "$remoteDirectory/$CaseName.$videoExtension"
$remoteFrameTrace = "$remoteDirectory/$CaseName-frames.csv"
function Invoke-AdbChecked([string[]]$Arguments) {
    & adb @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed ($LASTEXITCODE): adb $($Arguments -join ' ')"
    }
}
function Resolve-TaskPath([string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

$resolvedOutput = Resolve-TaskPath $OutputDirectory
if (Test-Path -LiteralPath $resolvedOutput) {
    throw "Output directory already exists: $resolvedOutput"
}
$resolvedReference = Resolve-TaskPath $ReferencePath
$resolvedInput = if ($InputPath) {
    Resolve-TaskPath $InputPath
} else {
    $resolvedReference
}

if ($Codec -eq 'libvpx' -and ($Complexity -lt 5 -or $Complexity -gt 9)) {
    throw 'libvpx complexity is VP9 real-time cpu-used and must be 5..9 (Design VX)'
}
if ($Codec -eq 'native_av1' -and $Complexity -lt 4) {
    throw 'Native AV1 complexity is libaom cpu-used and must be 4..10'
}
if ($Codec -notin @('native_av1', 'libvpx') -and $Complexity -gt 5) {
    throw 'Android MediaCodec complexity must be 0..5'
}
if (-not $SkipScoring -and (($MotionStartSeconds -ge 0) -xor ($MotionDurationSeconds -gt 0))) {
    throw 'MotionStartSeconds and MotionDurationSeconds must be supplied together'
}

if (-not (Test-Path -LiteralPath $resolvedReference)) {
    throw "Reference input does not exist: $resolvedReference"
}
if (-not (Test-Path -LiteralPath $resolvedInput)) {
    throw "Encoder input does not exist: $resolvedInput"
}
if ($ReferenceWidth -le 0) { $ReferenceWidth = $Width }
if ($ReferenceHeight -le 0) { $ReferenceHeight = $Height }
New-Item -ItemType Directory -Path $resolvedOutput | Out-Null

Invoke-AdbChecked @('-s', $DeviceSerial, 'get-state')
Invoke-AdbChecked @('-s', $DeviceSerial, 'push', $resolvedInput, $remoteTempInput)
Invoke-AdbChecked @('-s', $DeviceSerial, 'shell', 'run-as', $package, 'mkdir', '-p', $remoteInputDirectory)
Invoke-AdbChecked @('-s', $DeviceSerial, 'shell', 'run-as', $package, 'rm', '-f', $remoteInput)
Invoke-AdbChecked @('-s', $DeviceSerial, 'shell', 'run-as', $package, 'cp', $remoteTempInput, $remoteInput)
Invoke-AdbChecked @('-s', $DeviceSerial, 'shell', 'rm', '-f', $remoteTempInput)
Invoke-AdbChecked @('-s', $DeviceSerial, 'shell', 'rm', '-f', $remoteResult, $remoteVideo, $remoteFrameTrace)
Invoke-AdbChecked @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', $package)

$arguments = @(
    '-s', $DeviceSerial, 'shell', 'am', 'start', '-n', "$package/com.example.bluetoothtest.MainActivity",
    '--ez', 'offline_benchmark', 'true',
    '--es', 'benchmark_case', $CaseName,
    '--es', 'benchmark_codec', $Codec,
    '--es', 'benchmark_input', $InputName,
    '--ei', 'benchmark_width', $Width,
    '--ei', 'benchmark_height', $Height,
    '--ei', 'benchmark_fps', $Fps,
    '--ei', 'offline_frames', $Frames,
    '--ei', 'benchmark_bitrate_kbps', $BitrateKbps,
    '--ei', 'benchmark_complexity', $Complexity,
    '--es', 'benchmark_rate_mode', $RateMode,
    '--ei', 'benchmark_keyframe_seconds', $KeyFrameSeconds
)
if ($CodecName) {
    $arguments += @('--es', 'benchmark_codec_name', $CodecName)
}
if ($Codec -in @('native_av1', 'libvpx')) {
    if ($NativeThreads -gt 0) {
        $arguments += @('--ei', 'benchmark_native_threads', $NativeThreads)
    }
    if ($NativeRealtimeTools) {
        $arguments += @('--ez', 'benchmark_native_realtime_tools', 'true')
    }
}
Invoke-AdbChecked $arguments

$ready = $false
for ($elapsed = 0; $elapsed -lt $TimeoutSeconds; $elapsed += 2) {
    $probe = adb -s $DeviceSerial shell "test -f $remoteResult && echo ready"
    if ($probe -match 'ready') {
        $ready = $true
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    throw "Benchmark $CaseName did not finish within $TimeoutSeconds seconds"
}

$localDeviceResult = Join-Path $resolvedOutput "$CaseName-device.json"
Invoke-AdbChecked @('-s', $DeviceSerial, 'pull', $remoteResult, $localDeviceResult)
$deviceResult = Get-Content -LiteralPath $localDeviceResult -Raw | ConvertFrom-Json
if ($deviceResult.status -ne 'ok') {
    throw "Benchmark failed: $($deviceResult.error)"
}
Invoke-AdbChecked @('-s', $DeviceSerial, 'pull', $remoteVideo, (Join-Path $resolvedOutput "$CaseName.$videoExtension"))
Invoke-AdbChecked @('-s', $DeviceSerial, 'pull', $remoteFrameTrace, (Join-Path $resolvedOutput "$CaseName-frames.csv"))
if (-not $SkipScoring) {
    $scoreArguments = @(
        (Join-Path $PSScriptRoot 'score_encoded_file.py'),
        (Join-Path $resolvedOutput "$CaseName.$videoExtension"),
        $resolvedReference,
        '--width', $Width,
        '--height', $Height,
        '--reference-width', $ReferenceWidth,
        '--reference-height', $ReferenceHeight,
        '--fps', $Fps,
        '--output-directory', (Join-Path $resolvedOutput 'scores')
    )
    if ($MotionStartSeconds -ge 0 -and $MotionDurationSeconds -gt 0) {
        $scoreArguments += @(
            '--motion-start-seconds', $MotionStartSeconds,
            '--motion-duration-seconds', $MotionDurationSeconds
        )
    }
    & python @scoreArguments
    if ($LASTEXITCODE -ne 0) { throw "Scoring failed ($LASTEXITCODE) for $CaseName" }
}
