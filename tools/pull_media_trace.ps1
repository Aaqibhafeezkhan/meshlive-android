param(
    [Parameter(Mandatory = $true)][string]$SenderSerial,
    [Parameter(Mandatory = $true)][string]$ReceiverSerial,
    [Parameter(Mandatory = $true)][string]$SessionName,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$OutputDirectory = & python -B "$PSScriptRoot/benchmark_layout.py" --group runs --path $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Output must belong to benchmarks/runs or OS temporary scratch.' }
$package = if ($env:MESHLIVE_APP_ID) { $env:MESHLIVE_APP_ID } else { 'io.github.meshlive.app' }
if ($SessionName -notmatch '^[A-Za-z0-9_.-]{1,80}$') {
    throw 'SessionName may contain only letters, digits, dot, underscore, and hyphen'
}

$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
}
if (Test-Path -LiteralPath $resolvedOutput) {
    throw "Output directory already exists: $resolvedOutput"
}
New-Item -ItemType Directory -Path $resolvedOutput | Out-Null
$remoteDirectory = "/sdcard/Android/data/$package/files/media-traces"

function Pull-TraceFile {
    param([string]$Serial, [string]$Role, [string]$Extension)
    $name = "$SessionName-$Role.$Extension"
    $destination = Join-Path $resolvedOutput $name
    adb -s $Serial pull "$remoteDirectory/$name" $destination | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Could not pull $Role trace file: $name" }
    if (-not (Test-Path -LiteralPath $destination)) {
        throw "Missing $Role trace file: $name"
    }
    return $destination
}

adb -s $SenderSerial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Sender device is not available.' }
adb -s $ReceiverSerial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Receiver device is not available.' }
$senderCsv = Pull-TraceFile $SenderSerial 'sender' 'csv'
$senderIvf = Pull-TraceFile $SenderSerial 'sender' 'ivf'
$receiverCsv = Pull-TraceFile $ReceiverSerial 'receiver' 'csv'
$receiverIvf = Pull-TraceFile $ReceiverSerial 'receiver' 'ivf'

$senderRows = @(Import-Csv -LiteralPath $senderCsv)
$receiverRows = @(Import-Csv -LiteralPath $receiverCsv)
$senderByPts = @{}
foreach ($row in $senderRows) {
    if ($senderByPts.ContainsKey($row.pts_us)) {
        throw "Sender trace contains duplicate video PTS $($row.pts_us)"
    }
    $senderByPts[$row.pts_us] = $row
}

$mismatches = 0
$missingAtSender = 0
$verified = 0
$merged = foreach ($receiver in $receiverRows) {
    $sender = $senderByPts[$receiver.pts_us]
    $matches = $null -ne $sender -and $sender.sha256 -eq $receiver.sha256
    if ($null -eq $sender) {
        $missingAtSender++
    } elseif (-not $matches) {
        $mismatches++
    } else {
        $verified++
    }
    [pscustomobject]@{
        pts_us = $receiver.pts_us
        sender_frame_id = if ($sender) { $sender.frame_id } else { '' }
        receiver_frame_id = $receiver.frame_id
        bytes = $receiver.bytes
        sender_sha256 = if ($sender) { $sender.sha256 } else { '' }
        receiver_sha256 = $receiver.sha256
        integrity = $receiver.integrity
        exact_match = $matches
    }
}

$receiverPts = @{}
foreach ($row in $receiverRows) { $receiverPts[$row.pts_us] = $true }
$missingAtReceiver = @($senderRows | Where-Object { -not $receiverPts.ContainsKey($_.pts_us) }).Count
$mergedPath = Join-Path $resolvedOutput "$SessionName-merged.csv"
$merged | Export-Csv -LiteralPath $mergedPath -NoTypeInformation
$summary = [ordered]@{
    session = $SessionName
    sender_frames = $senderRows.Count
    receiver_frames = $receiverRows.Count
    exact_matches = $verified
    hash_mismatches = $mismatches
    missing_at_sender = $missingAtSender
    missing_at_receiver = $missingAtReceiver
    receiver_live_verified = @($receiverRows | Where-Object { $_.integrity -eq 'verified' }).Count
    sender_ivf = $senderIvf
    receiver_ivf = $receiverIvf
    merged_csv = $mergedPath
}
$summaryPath = Join-Path $resolvedOutput "$SessionName-integrity.json"
$summary | ConvertTo-Json | Set-Content -LiteralPath $summaryPath -Encoding utf8
$summary | Format-List | Out-Host

if ($mismatches -gt 0 -or $missingAtSender -gt 0 -or $summary.receiver_live_verified -ne $receiverRows.Count) {
    throw "Access-unit integrity failed; inspect $summaryPath"
}
