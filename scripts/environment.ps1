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
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-user-home'
$env:Path = "$jdkRoot\bin;$sdkRoot\platform-tools;$sdkRoot\cmdline-tools\19.0\bin;$env:Path"

foreach ($taskDir in @($env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME)) {
    New-Item -ItemType Directory -Path $taskDir -Force | Out-Null
}

$networkPath = Join-Path $toolRoot 'network.json'
if (Test-Path -LiteralPath $networkPath) {
    $network = Get-Content -LiteralPath $networkPath -Raw | ConvertFrom-Json
    $projectProxy = "http://$($network.host):$($network.port)"
    $env:HTTP_PROXY = $projectProxy
    $env:HTTPS_PROXY = $projectProxy
    $env:ALL_PROXY = $projectProxy
    $env:JAVA_OPTS = "$env:JAVA_OPTS -Dhttp.proxyHost=$($network.host) -Dhttp.proxyPort=$($network.port) -Dhttps.proxyHost=$($network.host) -Dhttps.proxyPort=$($network.port)"
}
