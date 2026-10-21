# Runs one heavy host build (Gradle) under the machine-wide build lock shared with the Windows port's
# packaging\build_gate.ps1, so Gradle and cargo builds queue one at a time instead of polling for a quiet gap
# (2026-10-21: back-to-back cargo runs starved every Gradle build that waited for "no cargo running").
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\gradle_gate.ps1 -Log D:\...\build.log -Command "./gradlew -q :app:testGithubDebugUnitTest"
# -Command runs through Git Bash (explicit path; the log is UTF-8) from the current directory. Exit code is the command's; 75 = gate timeout.
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string]$Log = '',
    [int]$TimeoutMinutes = 240
)
$lockPath = 'D:\StudioProjects\agent-scratch\cargo-build.lock'
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$lock = $null
while (-not $lock) {
    try {
        $lock = [System.IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
    } catch {
        if ((Get-Date) -gt $deadline) { Write-Output "gate_timeout lock"; exit 75 }
        Start-Sleep -Seconds 5
    }
}
try {
    while ($true) {
        $os = Get-CimInstance Win32_OperatingSystem
        $used = 100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize)
        if ($used -lt 85) { break }
        if ((Get-Date) -gt $deadline) { Write-Output "gate_timeout ram"; exit 75 }
        Start-Sleep -Seconds 10
    }
    Write-Output ("gate_acquired " + (Get-Date -Format 'HH:mm:ss'))
    # Git Bash explicitly: in Windows PowerShell the first `bash` on PATH is WSL's (System32), which cannot run this.
    $bash = 'C:\Program Files\Git\bin\bash.exe'
    if ($Log) {
        & $bash -c "$Command" 2>&1 | Out-File -FilePath $Log -Encoding utf8
    } else {
        & $bash -c "$Command"
    }
    $code = $LASTEXITCODE
    Write-Output ("gate_released " + (Get-Date -Format 'HH:mm:ss') + " exit=$code")
    exit $code
} finally {
    $lock.Dispose()
}
