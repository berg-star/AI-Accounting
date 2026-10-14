param(
    [switch]$AcceptSdkLicenses,
    [string]$ProxyUrl
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
$downloadRoot = Join-Path $toolRoot 'downloads'
$jdkRoot = Join-Path $toolRoot 'jdk17'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$gradleVersion = '8.13'
$gradleChecksum = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
$sdkToolsChecksum = '54a582f3bf73e04253602f2d1c80bd5868aac115'
$utf8 = [System.Text.UTF8Encoding]::new($false)

if (-not $AcceptSdkLicenses) {
    throw 'Review the Android SDK licenses, then rerun with -AcceptSdkLicenses to authorize acceptance.'
}

New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
$sdkNetworkArguments = @()
if ($ProxyUrl) {
    $proxyUri = [Uri]$ProxyUrl
    if ($proxyUri.Scheme -ne 'http' -or $proxyUri.UserInfo -or $proxyUri.Port -lt 1) {
        throw 'Use a local HTTP proxy URL without credentials, such as http://127.0.0.1:7890.'
    }
    $networkConfig = @{ type = 'http'; host = $proxyUri.Host; port = $proxyUri.Port } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $toolRoot 'network.json'), $networkConfig, $utf8)
}
if (Test-Path -LiteralPath (Join-Path $toolRoot 'network.json')) {
    $network = Get-Content -LiteralPath (Join-Path $toolRoot 'network.json') -Raw | ConvertFrom-Json
    $ProxyUrl = "http://$($network.host):$($network.port)"
    $sdkNetworkArguments = @('--proxy=http', "--proxy_host=$($network.host)", "--proxy_port=$($network.port)")
    $gradleCacheRoot = Join-Path $toolRoot 'gradle-user-home'
    New-Item -ItemType Directory -Path $gradleCacheRoot -Force | Out-Null
    $proxyProperties = "systemProp.http.proxyHost=$($network.host)`nsystemProp.http.proxyPort=$($network.port)`nsystemProp.https.proxyHost=$($network.host)`nsystemProp.https.proxyPort=$($network.port)`nsystemProp.http.nonProxyHosts=localhost|127.*`n"
    [IO.File]::WriteAllText((Join-Path $gradleCacheRoot 'gradle.properties'), $proxyProperties, $utf8)
}

function Get-OfficialFile {
    param([string]$Url, [string]$Destination, [string]$Checksum, [string]$Algorithm = 'SHA256')
    if (Test-Path -LiteralPath $Destination) {
        if (-not $Checksum -or (Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -eq $Checksum) {
            Write-Host "Using cached $([IO.Path]::GetFileName($Destination))"
            return
        }
    }
    Write-Host "Downloading $([IO.Path]::GetFileName($Destination))"
    # Certificate chain/hostname checks remain enabled. Windows CRL access is unavailable on this connection.
    $curlNetworkArguments = if ($ProxyUrl) { @('--proxy', $ProxyUrl) } else { @() }
    & curl.exe @curlNetworkArguments --ipv4 --ssl-no-revoke --location --fail --retry 2 --connect-timeout 20 --max-time 1200 --silent --show-error --output $Destination $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ($Checksum -and (Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $Checksum) {
        throw "Checksum mismatch: $Destination. The archive will not be extracted."
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\java.exe'))) {
    $assetPath = Join-Path $downloadRoot 'jdk-assets.json'
    Get-OfficialFile -Url 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse' -Destination $assetPath
    $assets = Get-Content -LiteralPath $assetPath -Raw | ConvertFrom-Json
    $asset = @($assets)[0]
    $jdkZip = Join-Path $downloadRoot $asset.binary.package.name
    Get-OfficialFile -Url $asset.binary.package.link -Destination $jdkZip -Checksum $asset.binary.package.checksum
    $jdkUnpack = Join-Path $toolRoot 'unpack-jdk'
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkUnpack -Force
    $jdkPackage = Get-ChildItem -LiteralPath $jdkUnpack -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if (-not $jdkPackage) { throw 'The JDK archive contains no Java executable.' }
    Copy-Item -LiteralPath $jdkPackage.FullName -Destination $jdkRoot -Recurse -Force
}

$sdkManager = Join-Path $sdkRoot 'cmdline-tools\19.0\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $sdkZip = Join-Path $downloadRoot 'commandlinetools-win-13114758_latest.zip'
    Get-OfficialFile -Url 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -Destination $sdkZip -Checksum $sdkToolsChecksum -Algorithm SHA1
    $sdkUnpack = Join-Path $toolRoot 'unpack-sdk'
    Expand-Archive -LiteralPath $sdkZip -DestinationPath $sdkUnpack -Force
    New-Item -ItemType Directory -Path (Join-Path $sdkRoot 'cmdline-tools') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $sdkUnpack 'cmdline-tools') -Destination (Join-Path $sdkRoot 'cmdline-tools\19.0') -Recurse -Force
}

. (Join-Path $PSScriptRoot 'environment.ps1')
& (Join-Path $jdkRoot 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'Java verification failed.' }
& $sdkManager --version
if ($LASTEXITCODE -ne 0) { throw 'SDK manager verification failed.' }

# Installing only these packages prompts for the licenses they actually require.
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" @sdkNetworkArguments 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }

$gradleZip = Join-Path $downloadRoot "gradle-$gradleVersion-bin.zip"
$gradleBootstrap = Join-Path $toolRoot "bootstrap-gradle\gradle-$gradleVersion\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradleBootstrap)) {
    Get-OfficialFile -Url "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -Destination $gradleZip -Checksum $gradleChecksum
    Expand-Archive -LiteralPath $gradleZip -DestinationPath (Join-Path $toolRoot 'bootstrap-gradle') -Force
}

$wrapperBootstrap = Join-Path $toolRoot 'wrapper-bootstrap'
New-Item -ItemType Directory -Path $wrapperBootstrap -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $wrapperBootstrap 'settings.gradle.kts'), 'rootProject.name = "wrapper-bootstrap"', $utf8)
& $gradleBootstrap -p $wrapperBootstrap wrapper --gradle-version $gradleVersion --distribution-type bin --gradle-distribution-sha256-sum $gradleChecksum --network-timeout 60000 --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle Wrapper generation failed.' }

foreach ($wrapperFile in @('gradlew', 'gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $wrapperBootstrap $wrapperFile) -Destination (Join-Path $projectRoot $wrapperFile) -Force
}
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'gradle\wrapper') -Force | Out-Null
foreach ($wrapperFile in @('gradle-wrapper.jar', 'gradle-wrapper.properties')) {
    Copy-Item -LiteralPath (Join-Path $wrapperBootstrap "gradle\wrapper\$wrapperFile") -Destination (Join-Path $projectRoot "gradle\wrapper\$wrapperFile") -Force
}

[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$($sdkRoot.Replace('\', '/').Replace(':', '\:'))`n", $utf8)
& (Join-Path $sdkRoot 'platform-tools\adb.exe') version
if ($LASTEXITCODE -ne 0) { throw 'ADB verification failed.' }
Write-Host 'Project tools and Gradle Wrapper are ready.'
