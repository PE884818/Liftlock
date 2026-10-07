param(
    [string]$Jdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$probeRoot = Join-Path $taskRoot 'build\scene-presentation-probe'
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
$stubClasses = Join-Path $taskRoot 'build\target-api\classes'
New-Item -ItemType Directory -Force -Path $stubClasses | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') '-J-Duser.language=en' -encoding UTF-8 --release 17 -d $stubClasses (Join-Path $PSScriptRoot 'target-api\android\view\SurfaceControl$OnSurfaceStatsListener.java')
if ($LASTEXITCODE -ne 0) { throw 'Target signature stub compilation failed.' }
$productionClasses = Join-Path $taskRoot 'app\build\intermediates\javac\release\compileReleaseJavaWithJavac\classes'
$coreClasses = Join-Path $taskRoot 'motion-core\build\classes\java\main'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'device') -Filter 'Scene*Probe.java' | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot 'device\ReturnScopeHelper.java'
$sources += Join-Path $taskRoot 'app\src\main\java\com\zhuanz\flymemotion\hook\NativeSurfaceFrame.java'
& (Join-Path $Jdk 'bin\javac.exe') '-J-Duser.language=en' -encoding UTF-8 --release 17 -cp "$androidJar;$stubClasses;$productionClasses;$coreClasses" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Scene producer probe compilation failed.' }
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
$env:JAVA_HOME = $Jdk
& (Join-Path $Sdk 'build-tools\37.0.0\d8.bat') --release --min-api 37 --lib $androidJar --classpath $stubClasses --output $dex $apiJar @classFiles
if ($LASTEXITCODE -ne 0) { throw 'Scene presentation probe DEX failed.' }
$outputJar = Join-Path $probeRoot 'scene-presentation-probe.jar'
& (Join-Path $Jdk 'bin\jar.exe') --create --file $outputJar -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Scene presentation probe package failed.' }
$outputJar
