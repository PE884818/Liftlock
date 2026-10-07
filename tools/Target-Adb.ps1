function Invoke-PortAdb {
    param([string]$Adb, [string]$Serial, [string[]]$Arguments)
    $output = (& $Adb -s $Serial @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $output" }
    return $output
}
function Assert-PortTarget {
    param([string]$Adb, [string]$Serial)
    # No device/build/SystemUI gate: any device the tester names is accepted. The serial must still
    # be explicit so a command never lands on whichever device adb happens to pick.
    if ([string]::IsNullOrWhiteSpace($Serial)) { throw 'Explicit device serial required.' }
    return Invoke-PortAdb $Adb $Serial @('shell', 'getprop', 'ro.build.fingerprint')
}
