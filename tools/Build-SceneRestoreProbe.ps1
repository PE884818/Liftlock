param(
    [string]$Jdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$probeRoot = Join-Path $taskRoot 'build\scene-restore-probe'
$classes = Join-Path $probeRoot 'classes'
$dex = Join-Path $probeRoot 'dex'
New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
$apiArchive = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\io.github.libxposed\api\101.0.0" -Recurse -Filter '*.aar' | Select-Object -First 1
if (!$apiArchive) { throw 'Build the project first to resolve the pinned libxposed API 101 artifact.' }
$apiJar = Join-Path $probeRoot 'api.jar'
$zip = [System.IO.Compression.ZipFile]::OpenRead($apiArchive.FullName)
try {
    $sourceStream = $zip.GetEntry('classes.jar').Open()
    $destinationStream = [System.IO.File]::Create($apiJar)
    try { $sourceStream.CopyTo($destinationStream) } finally { $sourceStream.Dispose();$destinationStream.Dispose() }
} finally { $zip.Dispose() }
$androidJar = Join-Path $Sdk 'platforms\android-37\android.jar'
& (Join-Path $Jdk 'bin\javac.exe') '-J-Duser.language=en' -encoding UTF-8 --release 17 -cp $androidJar -d $classes (Join-Path $PSScriptRoot 'device\SceneRestoreProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Scene restore probe compilation failed.' }
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
$env:JAVA_HOME = $Jdk
& (Join-Path $Sdk 'build-tools\37.0.0\d8.bat') --release --min-api 37 --lib $androidJar --output $dex $apiJar @classFiles
if ($LASTEXITCODE -ne 0) { throw 'Scene restore probe DEX failed.' }
$outputJar = Join-Path $probeRoot 'scene-restore-probe.jar'
& (Join-Path $Jdk 'bin\jar.exe') --create --file $outputJar -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Scene restore probe package failed.' }
$outputJar
