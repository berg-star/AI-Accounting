param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][ValidateSet('com.example.aiaccounting','com.example.aiaccounting.test')][string]$Package
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
$adbExe = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
function Get-InstalledHash {
    $apkPath = (& $adbExe -s $Serial shell pm path $Package | Where-Object { $_ -match '/base\.apk$' } | Select-Object -First 1) -replace '^package:', ''
    if ($apkPath -notmatch '^/data/app/[A-Za-z0-9_~=/+.-]+/base\.apk$') { return $null }
    try {
        $hashLine = & $adbExe -s $Serial shell sha256sum $apkPath 2>$null
        if ($hashLine -match '^([a-fA-F0-9]{64})\s') { return $Matches[1].ToUpperInvariant() }
    } catch { }
    return $null
}
$expectedHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
if ((Get-InstalledHash) -eq $expectedHash) {
    Write-Host "APK unchanged; keeping installed copy: $Package"
    return
}
$logRoot = Join-Path $projectRoot '.tools\logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $logRoot "install-$Package-$stamp.txt"
$stderr = Join-Path $logRoot "install-$Package-$stamp-stderr.txt"
$process = Start-Process -FilePath $adbExe -ArgumentList @('-s',$Serial,'install','-r','-t',('"'+$Apk+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$null = $process.Handle
Write-Host 'Updating the APK. If the phone shows an installation security prompt, allow it on the phone.'
if (-not $process.WaitForExit(120000)) {
    $process.Kill()
    throw 'Phone installation timed out. Allow the system prompt on the phone, then retry; app data was not cleared.'
}
$process.WaitForExit()
$output = @(Get-Content -LiteralPath $stdout)
$errors = @(Get-Content -LiteralPath $stderr)
if ($process.ExitCode -ne 0 -or ($output -join "`n") -notmatch '(?m)^Success\s*$') {
    $output | Write-Host
    $errors | Write-Host
    throw 'Installation was not completed. If the phone rejected or timed out a security prompt, allow this installation on the phone. Never uninstall to bypass a signing conflict.'
}
$installedHash = Get-InstalledHash
if ($installedHash -and $installedHash -ne $expectedHash) { throw 'Installed APK does not match this build.' }
Write-Host "Installed and retained app data: $Package"
