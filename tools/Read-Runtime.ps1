param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Target-Adb.ps1')
$fingerprint = Assert-PortTarget $Adb $Serial
$receipts = Invoke-PortAdb $Adb $Serial @('shell', 'content', 'query', '--uri', 'content://com.zhuanz.flymemotion.runtime')
$receipts | Set-Content -LiteralPath (Join-Path $PSScriptRoot '..\evidence\runtime-receipts.txt') -Encoding utf8
$receipts
