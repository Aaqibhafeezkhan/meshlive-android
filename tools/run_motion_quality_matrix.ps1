param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$OutputDirectory = & python -B "$PSScriptRoot/benchmark_layout.py" --group runs --path $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Output must belong to benchmarks/runs or OS temporary scratch.' }
$source = if ([IO.Path]::IsPathRooted($SourcePath)) { [IO.Path]::GetFullPath($SourcePath) } else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location) $SourcePath))
}
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) { [IO.Path]::GetFullPath($OutputDirectory) } else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
}
if (Test-Path -LiteralPath $output) { throw "Output directory already exists: $output" }
if (-not (Test-Path -LiteralPath $source)) { throw "Missing source: $source" }
if ((Get-Item -LiteralPath $source).Length -ne 155520000) {
    throw 'The matrix source must contain exactly 300 I420 720x480 frames'
}
New-Item -ItemType Directory -Path $output | Out-Null

$source15 = Join-Path $output 'reference-720x480-150.yuv'
$source360 = Join-Path $output 'reference-640x360-300.yuv'
if (-not (Test-Path -LiteralPath $source15)) {
    ffmpeg -n -v error -f rawvideo -pix_fmt yuv420p -s 720x480 -r 30 -i $source `
        -vf fps=15 -frames:v 150 -pix_fmt yuv420p -f rawvideo $source15
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare 15 FPS comparison input.' }
}
if (-not (Test-Path -LiteralPath $source360)) {
    ffmpeg -n -v error -f rawvideo -pix_fmt yuv420p -s 720x480 -r 30 -i $source `
        -vf 'crop=720:405:0:37,scale=640:360:flags=lanczos' -frames:v 300 `
        -pix_fmt yuv420p -f rawvideo $source360
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare 360p comparison input.' }
}

$cases = @(
    @{ Name = 'vp9_480p30_1100_cbr_gop2'; Codec = 'vp9'; Encoder = 'c2.android.vp9.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 2; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'vp9_480p30_1100_cbr_gop5'; Codec = 'vp9'; Encoder = 'c2.android.vp9.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 5; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'vp9_480p30_1100_cbr_gop10'; Codec = 'vp9'; Encoder = 'c2.android.vp9.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 10; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'vp9_480p30_1100_vbr_gop5'; Codec = 'vp9'; Encoder = 'c2.android.vp9.encoder'; Complexity = 0; Rate = 'vbr'; Gop = 5; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'av1_480p30_1100_cbr_gop2'; Codec = 'av1'; Encoder = 'c2.android.av1.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 2; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'av1_480p30_1100_cbr_gop5'; Codec = 'av1'; Encoder = 'c2.android.av1.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 5; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'av1_480p30_1100_cbr_gop10'; Codec = 'av1'; Encoder = 'c2.android.av1.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 10; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'av1_480p30_1100_vbr_gop5'; Codec = 'av1'; Encoder = 'c2.android.av1.encoder'; Complexity = 0; Rate = 'vbr'; Gop = 5; Width = 720; Height = 480; Fps = 30; Frames = 300; Input = $source },
    @{ Name = 'vp9_480p15_1100_cbr_gop5'; Codec = 'vp9'; Encoder = 'c2.android.vp9.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 5; Width = 720; Height = 480; Fps = 15; Frames = 150; Input = $source15 },
    @{ Name = 'vp9_360p30_1100_cbr_gop5'; Codec = 'vp9'; Encoder = 'c2.android.vp9.encoder'; Complexity = 0; Rate = 'cbr'; Gop = 5; Width = 640; Height = 360; Fps = 30; Frames = 300; Input = $source360 }
)

foreach ($case in $cases) {
    Write-Host "Running $($case.Name)"
    & "$PSScriptRoot\run_android_encoder_case.ps1" `
        -CaseName $case.Name -Codec $case.Codec -CodecName $case.Encoder `
        -DeviceSerial $DeviceSerial -InputName ([IO.Path]::GetFileName($case.Input)) `
        -InputPath $case.Input -ReferencePath $case.Input `
        -Width $case.Width -Height $case.Height -Fps $case.Fps -Frames $case.Frames `
        -BitrateKbps 1100 -Complexity $case.Complexity -RateMode $case.Rate `
        -KeyFrameSeconds $case.Gop -MotionStartSeconds 1 -MotionDurationSeconds 8 `
        -OutputDirectory (Join-Path $output $case.Name)
}

$summary = foreach ($case in $cases) {
    $caseOutput = Join-Path $output $case.Name
    $device = Get-Content -LiteralPath (Join-Path $caseOutput "$($case.Name)-device.json") -Raw |
        ConvertFrom-Json
    $quality = Get-Content -LiteralPath (Join-Path $caseOutput "scores/$($case.Name)-result.json") -Raw |
        ConvertFrom-Json
    [pscustomobject]@{
        case = $case.Name
        encoder = $device.encoder_name
        rate_mode = $device.rate_mode
        gop_seconds = $device.keyframe_interval_seconds
        fps = $device.fps
        width = $device.width
        height = $device.height
        actual_kbps = [math]::Round($device.actual_kbps, 1)
        vmaf = [math]::Round($quality.vmaf, 3)
        motion_vmaf = [math]::Round($quality.motion_section.vmaf, 3)
        ssim = [math]::Round($quality.ssim, 6)
        encode_fps = [math]::Round($device.encode_fps, 1)
        realtime_margin = [math]::Round($device.realtime_margin, 2)
        keyframe_bytes = $device.keyframe_size_bytes.mean
        qp_samples = $device.qp_samples
        battery_before_c = $device.battery_temperature_c_before
        battery_after_c = $device.battery_temperature_c_after
    }
}
$summaryPath = Join-Path $output 'matrix-summary.csv'
$summary | Export-Csv -LiteralPath $summaryPath -NoTypeInformation
$summary | Format-Table -AutoSize
Write-Host "Matrix summary: $summaryPath"
