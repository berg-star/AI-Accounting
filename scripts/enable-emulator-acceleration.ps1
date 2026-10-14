# Run in an elevated PowerShell. This never restarts Windows automatically.
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $projectDirectory '.tools\logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Start-Transcript -Path (Join-Path $logDirectory 'hypervisor-setup.log') -Force
try {
$feature = Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform
Write-Host "Windows Hypervisor Platform: $($feature.State)"
if ($feature.State -eq 'Enabled') {
    Write-Host 'The feature is already enabled. Check emulator -accel-check after restarting Windows if needed.'
} else {
    $result = Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
    Write-Host "Restart needed: $($result.RestartNeeded)"
    Write-Host 'Save your work and restart Windows when convenient, then run scripts\test-emulator.ps1.'
}
$state = Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform
@{ feature = 'HypervisorPlatform'; state = [string]$state.State; restartNeeded = [bool]$result.RestartNeeded } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logDirectory 'hypervisor-result.json') -Encoding UTF8
} finally { Stop-Transcript }
