<#
.SYNOPSIS
    Regenerates packaging\SOURCE-MANIFEST.txt after deliberate source edits.

.DESCRIPTION
    PowerShell equivalent of packaging/make-manifest.sh.

    Paths are written with forward slashes and sorted ordinally so the manifest is
    byte-identical whether generated on Windows or Linux.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File packaging\make-manifest.ps1
#>

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$out    = Join-Path $root 'packaging\SOURCE-MANIFEST.txt'
$srcDir = Join-Path $root 'app\src'

if (-not (Test-Path $srcDir)) {
    Write-Host "ERROR: app\src not found - run this from an AEGIS-FDX checkout." -ForegroundColor Red
    exit 2
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# AEGIS-FDX source manifest - regenerate with: packaging\make-manifest.ps1')
$lines.Add('# Verifies a checkout is complete and self-consistent before building.')
$lines.Add("# Generated: $stamp")

# Cover build files and scripts too, not just Java sources: a stale
# app/build.gradle.kts caused a jpackage failure the source-only manifest missed.
$paths = [System.Collections.Generic.List[string]]::new()

Get-ChildItem -LiteralPath $srcDir -Recurse -Filter *.java -File | ForEach-Object {
    $paths.Add(($_.FullName.Substring($root.Length).TrimStart('\') -replace '\\', '/'))
}

foreach ($extra in @(
        'build.gradle.kts', 'settings.gradle.kts', 'app/build.gradle.kts',
        'run-tests.sh', 'run-tests.ps1', 'final-acceptance.sh')) {
    if (Test-Path (Join-Path $root ($extra -replace '/', '\'))) { $paths.Add($extra) }
}

$pkgDir = Join-Path $root 'packaging'
if (Test-Path $pkgDir) {
    Get-ChildItem -LiteralPath $pkgDir -File | Where-Object {
        $_.Extension -in @('.sh', '.ps1')
    } | ForEach-Object {
        $paths.Add(($_.FullName.Substring($root.Length).TrimStart('\') -replace '\\', '/'))
    }
}

$files = $paths | Sort-Object -Unique -CaseSensitive

foreach ($rel in $files) {
    $full = Join-Path $root ($rel -replace '/', '\')
    $hash = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    $lines.Add("$hash  $rel")
}

# LF endings + no BOM, so the file matches the Linux-generated manifest exactly.
$text = ($lines -join "`n") + "`n"
[System.IO.File]::WriteAllText($out, $text, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ("wrote packaging\SOURCE-MANIFEST.txt ({0} files)" -f $files.Count)
