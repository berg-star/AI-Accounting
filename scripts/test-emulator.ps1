param([switch]$ShowWindow)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'start-emulator.ps1') -ShowWindow:$ShowWindow
. (Join-Path $PSScriptRoot 'environment.ps1')
$serial = 'emulator-5556'
# All device commands explicitly target this project's AVD, even when a phone is connected.
$runningName = (& adb -s $serial emu avd name | Select-Object -First 1).Trim()
if ($runningName -ne 'Accounting_API_31') { throw 'Unexpected emulator; no APK will be installed.' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') ':app:assembleDebug' ':app:assembleDebugAndroidTest' ':app:testDebugUnitTest' ':app:lintDebug'
if ($LASTEXITCODE -ne 0) { throw 'Build or local checks failed.' }
& adb -s $serial install -r -t (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& adb -s $serial install -r -t (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportDirectory = Join-Path $projectRoot ".tools\test-results\emulator-$stamp"
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$deviceOutput = "/sdcard/Android/media/com.example.aiaccounting/test-results-$stamp"
$stdoutPath = Join-Path $reportDirectory 'instrumentation.txt'
$stderrPath = Join-Path $reportDirectory 'instrumentation-stderr.txt'
# Windows PowerShell 5 treats native stderr as a terminating error under Stop.
# Emulator graphics diagnostics can use stderr even when the tests pass.
$testProcess = Start-Process -FilePath (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') -ArgumentList @('-s', $serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'timeout_msec', '60000', '-e', 'additionalTestOutputDir', $deviceOutput, 'com.example.aiaccounting.test/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
# Keep the process handle open so Windows PowerShell can read ExitCode after exit.
$null = $testProcess.Handle
if (-not $testProcess.WaitForExit(360000)) {
    $testProcess.Kill()
    & adb -s $serial shell am force-stop com.example.aiaccounting
    throw "Device tests timed out. See $reportDirectory"
}
$testProcess.WaitForExit()
$result = @(Get-Content -LiteralPath $stdoutPath)
$result | Write-Host
$instrumentationExit = $testProcess.ExitCode
& adb -s $serial pull $deviceOutput $reportDirectory
if ($instrumentationExit -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests?\)' -or ($result -join "`n") -match '(FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed)') { throw "Device tests failed. See $reportDirectory" }
& adb -s $serial shell am start -W -n com.example.aiaccounting/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host "Emulator tests passed. Results: $reportDirectory"
