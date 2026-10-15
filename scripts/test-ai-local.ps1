param([switch]$AllowPaidRequests, [switch]$Comparison, [switch]$MonthlyReport, [string]$KeyFile)

$ErrorActionPreference = 'Stop'
if (-not $AllowPaidRequests) { throw 'Live API tests use account credits. Re-run with -AllowPaidRequests to enable them.' }
if ($Comparison -and $MonthlyReport) { throw 'Choose either -Comparison or -MonthlyReport.' }
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $KeyFile) { $KeyFile = Join-Path $taskRoot '.tools\secrets\siliconflow-api-key.txt' }
if (-not (Test-Path -LiteralPath $KeyFile -PathType Leaf)) { throw 'Create the local Key file first. Do not put the Key in command arguments.' }
$keyPath = (Resolve-Path -LiteralPath $KeyFile).Path
if ((Get-Item -LiteralPath $keyPath).Length -eq 0) { throw 'The local Key file is empty. Save the Key as one line and try again.' }
# Only a file path is passed to Gradle. The JVM reads the credential without logging it.
$testClass = if ($MonthlyReport) { 'com.example.aiaccounting.LiveMonthlyReportJvmTest' } elseif ($Comparison) { 'com.example.aiaccounting.LiveComparisonJvmTest' } else { 'com.example.aiaccounting.LiveInquiryJvmTest' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') ':app:testDebugUnitTest' '--tests' $testClass '-PallowLiveInquiry=true' "-PliveKeyFile=$keyPath" '--rerun-tasks'
$buildExit = $LASTEXITCODE
$reportPrefix = if ($MonthlyReport) { 'live-monthly-report-local-' } elseif ($Comparison) { 'live-comparison-local-' } else { 'live-inquiry-local-' }
$savedReport = Join-Path $taskRoot ('.tools\test-results\' + $reportPrefix + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $savedReport -Force | Out-Null
foreach ($source in @('app\build\test-results\testDebugUnitTest', 'app\build\reports\tests\testDebugUnitTest')) {
    $sourcePath = Join-Path $taskRoot $source
    if (Test-Path -LiteralPath $sourcePath) { Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $savedReport $(if ($source -like '*\reports\*') { 'html' } else { 'xml' })) -Recurse }
}
if ($buildExit -ne 0) { throw "Live API tests failed. Results: $savedReport" }
$report = Join-Path $taskRoot "app\build\test-results\testDebugUnitTest\TEST-$testClass.xml"
[xml]$result = Get-Content -LiteralPath $report -Encoding UTF8 -Raw
if ([int]$result.testsuite.tests -ne 1 -or [int]$result.testsuite.skipped -ne 0 -or [int]$result.testsuite.failures -ne 0 -or [int]$result.testsuite.errors -ne 0) { throw 'The live API test did not complete successfully.' }
Write-Host "Live API tests passed. Synthetic data only; no phone installation or ledger changes. Results: $savedReport"
