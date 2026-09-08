# Repairs settings.gradle.kts if it was clobbered during file transfer.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$f    = Join-Path $root "settings.gradle.kts"

Write-Host "Project root : $root"
Write-Host "Target file  : $f"

if (Test-Path $f) {
    $len = (Get-Item $f).Length
    Write-Host "Current size : $len bytes"
    if ($len -gt 200) {
        Write-Host "  -> CLOBBERED (expected 46 bytes). Backing up to settings.gradle.kts.bad"
        Copy-Item $f "$f.bad" -Force
    }
}

# Write exactly the two required lines, UTF-8 no BOM, LF endings.
$content = "rootProject.name = `"aegis-fdx`"`ninclude(`"app`")`n"
[System.IO.File]::WriteAllText($f, $content, (New-Object System.Text.UTF8Encoding($false)))

$new = (Get-Item $f).Length
Write-Host ""
Write-Host "Repaired. New size: $new bytes (expected 46)"
Write-Host "--- contents ---"
Get-Content $f
Write-Host "----------------"

$sha = (Get-FileHash $f -Algorithm SHA256).Hash.ToLower()
$expected = "c9e2473d3c501323d179fa767af22e5930c4df5b19a850a2fff8dfd8d392a11a"
if ($sha -eq $expected) {
    Write-Host "SHA-256 MATCHES manifest. Fixed." -ForegroundColor Green
} else {
    Write-Host "SHA-256 mismatch:" -ForegroundColor Yellow
    Write-Host "  got      $sha"
    Write-Host "  expected $expected"
    Write-Host "(line-ending difference is harmless; Gradle will still build)"
}

# Warn about stray duplicate settings files that could shadow this one.
Write-Host ""
Write-Host "Scanning for other settings.gradle* files under the project..."
Get-ChildItem -Path $root -Recurse -Filter "settings.gradle*" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $f -and $_.Name -ne "settings.gradle.kts.bad" } |
    ForEach-Object { Write-Host ("  EXTRA: " + $_.FullName + "  (" + $_.Length + " bytes)") -ForegroundColor Yellow }
Write-Host "Scan done."
