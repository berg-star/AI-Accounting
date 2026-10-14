param([switch]$ShowWindow)

. (Join-Path $PSScriptRoot 'environment.ps1')
$avdName = 'Accounting_API_31'
$serial = 'emulator-5556'
$process = $null
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
if (-not (Test-Path -LiteralPath $emulator)) { throw 'Run scripts\setup-emulator.ps1 -AcceptSdkLicenses first.' }
& $emulator -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Hardware acceleration is unavailable. Enable Windows Hypervisor Platform, then restart Windows if required.' }
& adb start-server | Out-Null
$devices = & adb devices
if ($devices -match "^$serial\s+offline") { throw "$serial is offline; wait for the existing emulator or close it before retrying." }
if ($devices -match "^$serial\s+device") {
    $runningName = (& adb -s $serial emu avd name | Select-Object -First 1).Trim()
    if ($runningName -ne $avdName) { throw "Port 5556 is used by a different AVD: $runningName" }
} else {
    $logDirectory = Join-Path $projectRoot '.tools\logs'
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $arguments = @('-avd', $avdName, '-port', '5556', '-gpu', 'software', '-no-audio', '-no-boot-anim', '-no-snapshot')
    if (-not $ShowWindow) { $arguments += '-no-window' }
    $windowStyle = if ($ShowWindow) { 'Normal' } else { 'Hidden' }
    $process = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle $windowStyle -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'emulator-stdout.log') -RedirectStandardError (Join-Path $logDirectory 'emulator-stderr.log')
    Write-Host "Starting $avdName (PID $($process.Id))..."
}
$deadline = (Get-Date).AddMinutes(4)
do {
    try { $ready = (& adb -s $serial shell getprop sys.boot_completed 2>$null) } catch { $ready = $null }
    if ($ready -eq '1') {
        & adb -s $serial shell input keyevent 82
        & adb -s $serial shell settings put secure show_ime_with_hard_keyboard 1
        Write-Host "Ready: $serial ($avdName)"
        return
    }
    if ($process -and $process.HasExited) { throw 'Emulator exited. Check .tools/logs/emulator-stderr.log.' }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
throw 'Emulator boot timed out. Check .tools/logs/emulator-stderr.log.'
