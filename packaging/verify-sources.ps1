<#
.SYNOPSIS
    Verifies that an AEGIS-FDX checkout is COMPLETE and CONSISTENT before building.

.DESCRIPTION
    A partial copy produces a baffling "cannot find symbol" error against a method
    that plainly exists in the repository, because javac blames the calling file
    rather than the absent one. This script names the missing files directly.

    PowerShell equivalent of packaging/verify-sources.sh, for Windows hosts with no
    bash available.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File packaging\verify-sources.ps1
#>

$ErrorActionPreference = 'Stop'

# Resolve the repo root from this script's location, so it works from any cwd.
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$manifestPath = Join-Path $root 'packaging\SOURCE-MANIFEST.txt'

if (-not (Test-Path $manifestPath)) {
    Write-Host ''
    Write-Host 'ERROR: packaging\SOURCE-MANIFEST.txt not found.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'That file is itself part of the source tree, so its absence is strong'
    Write-Host 'evidence that this copy is incomplete. Re-copy the entire aegis-fdx'
    Write-Host 'folder rather than individual files.'
    exit 2
}

Write-Host "-- Verifying source tree against packaging\SOURCE-MANIFEST.txt"

$matched   = 0
$changed   = [System.Collections.Generic.List[string]]::new()
$missing   = [System.Collections.Generic.List[string]]::new()
$untracked = [System.Collections.Generic.List[string]]::new()
$tracked   = [System.Collections.Generic.HashSet[string]]::new()

foreach ($line in Get-Content -LiteralPath $manifestPath) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line.TrimStart().StartsWith('#'))   { continue }

    $parts = $line.Trim() -split '\s+', 2
    if ($parts.Count -lt 2) { continue }

    $want = $parts[0].ToLowerInvariant()
    # Manifest stores forward slashes; Windows needs backslashes.
    $rel  = $parts[1].Trim()
    [void]$tracked.Add(($rel -replace '/', '\'))
    $full = Join-Path $root ($rel -replace '/', '\')

    if (-not (Test-Path -LiteralPath $full)) {
        $missing.Add($rel)
        Write-Host ("  MISSING   {0}" -f $rel) -ForegroundColor Red
        continue
    }

    $got = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($got -ne $want) {
        $changed.Add($rel)
        Write-Host ("  CHANGED   {0}" -f $rel) -ForegroundColor Yellow
    } else {
        $matched++
    }
}

# Files present on disk that the manifest does not know about.
$srcDir = Join-Path $root 'app\src'
if (Test-Path $srcDir) {
    Get-ChildItem -LiteralPath $srcDir -Recurse -Filter *.java -File | ForEach-Object {
        $rel = $_.FullName.Substring($root.Length).TrimStart('\')
        if (-not $tracked.Contains($rel)) {
            $untracked.Add($rel)
            Write-Host ("  UNTRACKED {0}" -f $rel) -ForegroundColor Yellow
        }
    }
}

Write-Host ''
Write-Host ("  {0} matched | {1} changed | {2} missing | {3} untracked" -f `
    $matched, $changed.Count, $missing.Count, $untracked.Count)

if ($missing.Count -gt 0) {
    Write-Host ''
    Write-Host 'RESULT: INCOMPLETE CHECKOUT.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'Files the build needs are absent. This is the usual cause of a'
    Write-Host "'cannot find symbol' error on a method that plainly exists in the repo:"
    Write-Host 'javac blames the file doing the calling, not the file that is missing.'
    Write-Host ''
    Write-Host 'Re-copy the whole aegis-fdx folder. Do not copy individual files.'
    exit 1
}

if ($changed.Count -gt 0 -or $untracked.Count -gt 0) {
    Write-Host ''
    Write-Host 'RESULT: TREE DIFFERS FROM MANIFEST.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'STOP - do NOT regenerate the manifest yet.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'On a machine you only COPY files to, CHANGED means those files are stale:'
    Write-Host 'they arrived from an older transfer. Regenerating the manifest would'
    Write-Host 'record the stale content as correct and destroy the only evidence of'
    Write-Host 'what is out of date.'
    Write-Host ''
    Write-Host 'Re-copy these files from the source machine, then re-run this script:'
    foreach ($f in $changed) { Write-Host ("    {0}" -f $f) -ForegroundColor Yellow }
    foreach ($f in $untracked) { Write-Host ("    {0}  (untracked)" -f $f) -ForegroundColor Yellow }
    Write-Host ''
    Write-Host 'Only run make-manifest.ps1 if you edited these files ON THIS MACHINE'
    Write-Host 'and intend those edits to become the new baseline.'
    exit 1
}

Write-Host 'RESULT: source tree complete and consistent.' -ForegroundColor Green
exit 0
