param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Apk = (Join-Path $PSScriptRoot '..\dist\FlymeMotionPort-0.3.5-stage1.apk')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Target-Adb.ps1')
$fingerprint = Assert-PortTarget $Adb $Serial
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$install = Invoke-PortAdb $Adb $Serial @('install', '-r', '--user', '0', $apkPath)
$packagePath = Invoke-PortAdb $Adb $Serial @('shell', 'pm', 'path', '--user', '0', 'com.zhuanz.flymemotion')
$report = [ordered]@{
    checkedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    serial = $Serial; fingerprint = $fingerprint; apk = $apkPath; sha256 = $hash
    install = $install; installedPath = $packagePath
    lsposedEnabledByThisScript = $false; processesReloadedByThisScript = $false
    screenInputPerformed = $false; hookLoaded = $null; feelAccepted = $false
}
$destination = Join-Path $PSScriptRoot '..\evidence\installation.json'
$report | ConvertTo-Json | Set-Content -LiteralPath $destination -Encoding utf8
$report | ConvertTo-Json
