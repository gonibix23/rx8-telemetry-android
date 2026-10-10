[CmdletBinding()]
param(
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Destination)) {
    $configPath = Join-Path $repositoryRoot 'apk-delivery.local.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw 'Pass -Destination or configure the ignored apk-delivery.local.json file.'
    }
    $Destination = (Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).directory
}
if ([string]::IsNullOrWhiteSpace($Destination)) {
    throw 'The APK delivery directory is empty.'
}

$buildDirectory = Join-Path $repositoryRoot 'app/build/outputs/apk/debug'
$metadata = Get-Content -LiteralPath (Join-Path $buildDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
$element = @($metadata.elements)
if ($element.Count -ne 1 -or $element[0].versionName -notmatch '^[0-9A-Za-z.+-]+$') {
    throw 'Expected one debug APK with a valid version name.'
}
$sourceApk = Join-Path $buildDirectory 'app-debug.apk'
$sourceHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash
$deliveryDirectory = [System.IO.Path]::GetFullPath($Destination)
[System.IO.Directory]::CreateDirectory($deliveryDirectory) | Out-Null
$deliveredApk = Join-Path $deliveryDirectory ('RX8-Telemetry-' + $element[0].versionName + '.apk')
Copy-Item -LiteralPath $sourceApk -Destination $deliveredApk -Force
if ((Get-FileHash -LiteralPath $deliveredApk -Algorithm SHA256).Hash -ne $sourceHash) {
    throw 'The copied APK does not match the build output.'
}
[PSCustomObject]@{
    Version = $element[0].versionName
    File = $deliveredApk
    SHA256 = $sourceHash
}
