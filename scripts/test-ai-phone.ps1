param([switch]$AllowPaidRequests, [switch]$Inquiry, [string]$Serial)

$ErrorActionPreference = 'Stop'
if (-not $AllowPaidRequests) { throw 'Live AI tests use account credits. Run with -AllowPaidRequests after saving a Key in the phone app.' }
. (Join-Path $PSScriptRoot 'environment.ps1')
$phones = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^(?!emulator-)(\S+)\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $Serial) {
    if ($phones.Count -ne 1) { throw 'Connect exactly one authorized USB phone, or provide -Serial.' }
    $Serial = $phones[0]
}
if ($Serial -notin $phones) { throw 'The selected serial is not a connected, authorized physical phone.' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') ':app:assembleDebug' ':app:assembleDebugAndroidTest'
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
# Replace APKs while retaining app data, encrypted Key and the existing signing identity.
& (Join-Path $PSScriptRoot 'install-phone-apk.ps1') -Serial $Serial -Apk (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Package 'com.example.aiaccounting'
& (Join-Path $PSScriptRoot 'install-phone-apk.ps1') -Serial $Serial -Apk (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') -Package 'com.example.aiaccounting.test'
$modeName = if ($Inquiry) { 'live-inquiry-' } else { 'live-ai-' }
$testClass = if ($Inquiry) { 'com.example.aiaccounting.LiveInquirySmokeTest' } else { 'com.example.aiaccounting.LiveAiSmokeTest' }
$report = Join-Path $projectRoot ('.tools\test-results\' + $modeName + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $report -Force | Out-Null
$stdout = Join-Path $report 'instrumentation.txt'
$stderr = Join-Path $report 'instrumentation-stderr.txt'
$testProcess = Start-Process -FilePath (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') -ArgumentList @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass, '-e', 'allowLiveAi', 'true', '-e', 'timeout_msec', '300000', 'com.example.aiaccounting.test/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$null = $testProcess.Handle
if (-not $testProcess.WaitForExit(330000)) {
    $testProcess.Kill()
    & adb -s $Serial shell am force-stop com.example.aiaccounting
    throw "Live AI tests timed out. See $report"
}
$testProcess.WaitForExit()
$result = @(Get-Content -LiteralPath $stdout)
$result | Write-Host
if ($testProcess.ExitCode -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)' -or ($result -join "`n") -match '(FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed)') { throw "Live AI tests failed. See $report" }
& adb -s $Serial shell am start -W -n com.example.aiaccounting/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host "Live AI tests passed. No ledger records were saved. Results: $report"
