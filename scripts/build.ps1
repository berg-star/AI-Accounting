param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @(':app:assembleDebug')
)

. (Join-Path $PSScriptRoot 'environment.ps1')
$downloadArguments = @('-Dorg.gradle.internal.http.connectionTimeout=15000', '-Dorg.gradle.internal.http.socketTimeout=30000')
$buildNetworkPath = Join-Path $projectRoot '.tools\network.json'
if (Test-Path -LiteralPath $buildNetworkPath) {
    $buildNetwork = Get-Content -LiteralPath $buildNetworkPath -Raw | ConvertFrom-Json
    $buildProxyClient = [System.Net.Sockets.TcpClient]::new()
    try {
        $buildProxyConnect = $buildProxyClient.ConnectAsync([string]$buildNetwork.host, [int]$buildNetwork.port)
        $buildProxyReady = $buildProxyConnect.Wait(700) -and $buildProxyClient.Connected
    } catch { $buildProxyReady = $false }
    finally { $buildProxyClient.Dispose() }
    if (-not $buildProxyReady) {
        Write-Host 'Configured proxy is unavailable; using direct downloads with the public Google Maven mirror for this build.'
        $downloadArguments += @('-Dhttp.proxyHost=', '-Dhttps.proxyHost=', '-PuseGoogleMirror=true')
    }
}
$wrapperPath = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapperPath)) {
    throw 'Gradle Wrapper is missing. Run scripts\setup-tools.ps1 first.'
}

Push-Location $projectRoot
try {
    & $wrapperPath @GradleArguments @downloadArguments --console=plain
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $buildExitCode
