param(
    [string]$Serial = '192.168.1.54:39221',
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidateSet('before', 'after')][string]$Label = 'after'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Target-Adb.ps1')
$fingerprint = Assert-PortTarget $Adb $Serial
$folder = Join-Path $PSScriptRoot '..\evidence\frames'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
# Read-only. Capture before/after a gesture performed by the user; never reset gfxinfo or inject input.
$display = Invoke-PortAdb $Adb $Serial @('shell', 'dumpsys', 'display')
$display | Set-Content -LiteralPath (Join-Path $folder "$Label-display.txt") -Encoding utf8
$frames = Invoke-PortAdb $Adb $Serial @('shell', 'dumpsys', 'gfxinfo', 'com.android.systemui', 'framestats')
$frames | Set-Content -LiteralPath (Join-Path $folder "$Label-framestats.txt") -Encoding utf8
$uptime = Invoke-PortAdb $Adb $Serial @('shell', 'cat', '/proc/uptime')
[ordered]@{ fingerprint = $fingerprint; collectedAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); uptime = $uptime; label = $Label; screenInputPerformed = $false } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder "$Label-session.json") -Encoding utf8
"Saved read-only frame capture: $folder"
