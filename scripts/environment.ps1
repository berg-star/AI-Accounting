$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
$jdkRoot = Join-Path $toolRoot 'jdk17'
$sdkRoot = Join-Path $toolRoot 'android-sdk'

if (-not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\java.exe'))) {
    throw 'Project JDK 17 is missing. Run scripts\setup-tools.ps1 first.'
}

$env:JAVA_HOME = $jdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user-home'
$env:ANDROID_EMULATOR_HOME = Join-Path $toolRoot 'emulator-user-home'
$env:ANDROID_AVD_HOME = Join-Path $toolRoot 'avd'
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-user-home'
$env:TEMP = Join-Path $toolRoot 'tmp'
$env:TMP = $env:TEMP
# Avoid the emulator's optional crash-report helper launching a Windows JIT debugger.
# Console logs and instrumentation results are retained under .tools instead.
$env:ANDROID_EMU_ENABLE_CRASH_REPORTING = '0'
$env:Path = "$jdkRoot\bin;$sdkRoot\platform-tools;$sdkRoot\emulator;$sdkRoot\cmdline-tools\19.0\bin;$env:Path"

foreach ($taskDir in @($env:ANDROID_USER_HOME, $env:ANDROID_EMULATOR_HOME, $env:ANDROID_AVD_HOME, $env:GRADLE_USER_HOME, $env:TEMP)) {
    New-Item -ItemType Directory -Path $taskDir -Force | Out-Null
}

# Kotlin otherwise keeps daemon discovery files in the Windows user profile.
# These settings belong only to this project's already-isolated Gradle home.
$kotlinRunDirectory = Join-Path $toolRoot 'kotlin-daemon'
New-Item -ItemType Directory -Path $kotlinRunDirectory -Force | Out-Null
$localGradleProperties = Join-Path $env:GRADLE_USER_HOME 'gradle.properties'
$localProperties = if (Test-Path -LiteralPath $localGradleProperties) { @(Get-Content -LiteralPath $localGradleProperties) } else { @() }
$localProperties = @($localProperties | Where-Object { $_ -notmatch '^systemProp\.kotlin\.daemon\.options=' })
$localProperties += 'systemProp.kotlin.daemon.options=runFilesPath=' + $kotlinRunDirectory.Replace('\', '/')
[IO.File]::WriteAllLines($localGradleProperties, $localProperties, [Text.UTF8Encoding]::new($false))

$networkPath = Join-Path $toolRoot 'network.json'
if (Test-Path -LiteralPath $networkPath) {
    $network = Get-Content -LiteralPath $networkPath -Raw | ConvertFrom-Json
    $projectProxy = "http://$($network.host):$($network.port)"
    $env:HTTP_PROXY = $projectProxy
    $env:HTTPS_PROXY = $projectProxy
    $env:ALL_PROXY = $projectProxy
    $env:JAVA_OPTS = "$env:JAVA_OPTS -Dhttp.proxyHost=$($network.host) -Dhttp.proxyPort=$($network.port) -Dhttps.proxyHost=$($network.host) -Dhttps.proxyPort=$($network.port)"
}
