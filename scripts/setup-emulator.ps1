param([switch]$AcceptSdkLicenses)

. (Join-Path $PSScriptRoot 'environment.ps1')
$image = 'system-images;android-31;default;x86_64'
$avdName = 'Accounting_API_31'
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\19.0\bin\sdkmanager.bat'
$avdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\19.0\bin\avdmanager.bat'
if (-not $AcceptSdkLicenses) { throw 'Review the Android SDK licenses and rerun with -AcceptSdkLicenses.' }
$networkArguments = @()
if (Test-Path -LiteralPath $networkPath) {
    $networkArguments = @('--proxy=http', "--proxy_host=$($network.host)", "--proxy_port=$($network.port)")
}
& $sdkManager "--sdk_root=$env:ANDROID_HOME" @networkArguments 'emulator' $image
if ($LASTEXITCODE -ne 0) { throw 'Emulator or system image installation failed.' }

$avdPath = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
if (-not (Test-Path -LiteralPath (Join-Path $avdPath 'config.ini'))) {
    'no' | & $avdManager create avd --name $avdName --package $image --path $avdPath --device pixel_5
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
    $configPath = Join-Path $avdPath 'config.ini'
    $config = Get-Content -LiteralPath $configPath
    $overrides = [ordered]@{
        'hw.ramSize' = '2048'; 'hw.cpu.ncore' = '2'; 'hw.keyboard' = 'no'
        'hw.lcd.width' = '720'; 'hw.lcd.height' = '1280'; 'hw.lcd.density' = '320'
        'disk.dataPartition.size' = '4G'; 'showDeviceFrame' = 'no'
    }
    foreach ($key in $overrides.Keys) {
        $config = @($config | Where-Object { $_ -notmatch ('^' + [regex]::Escape($key) + '\s*=') })
        $config += "$key=$($overrides[$key])"
    }
    [IO.File]::WriteAllLines($configPath, $config, [Text.UTF8Encoding]::new($false))
}
Write-Host "Ready: $avdName. Start with scripts\start-emulator.ps1."
& (Join-Path $env:ANDROID_HOME 'emulator\emulator.exe') -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Hardware acceleration is not ready. See scripts\enable-emulator-acceleration.ps1; a Windows restart may be required.' }
