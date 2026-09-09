# PowerShell Installation Verification for AEGIS-FDX (Windows 10/11 x64)
# Verifies a packaged/installed AEGIS-FDX directory structure, bundled JRE, dependencies, and engine execution.
param(
    [string]$AppDir = "C:\Program Files\AEGIS Forensics\AEGIS-FDX",
    [string]$JdkPath = $env:JAVA_HOME
)

$ErrorActionPreference = "Continue"
$PassCount = 0
$FailCount = 0

function Report-Check([string]$name, [bool]$passed) {
    if ($passed) {
        Write-Host "  PASS  $name" -ForegroundColor Green
        $script:PassCount++
    } else {
        Write-Host "  FAIL  $name" -ForegroundColor Red
        $script:FailCount++
    }
}

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " AEGIS-FDX Windows Installation Verification        " -ForegroundColor Cyan
Write-Host " App Directory : $AppDir                            " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 1. Directory Structure
Report-Check "Application directory exists" (Test-Path $AppDir)

$Launcher = Join-Path $AppDir "AEGIS-FDX.exe"
$AppSubDir = Join-Path $AppDir "app"
$RuntimeDir = Join-Path $AppDir "runtime"

Report-Check "Native executable (AEGIS-FDX.exe) present" (Test-Path $Launcher)
Report-Check "Bundled Java runtime directory present" (Test-Path $RuntimeDir)
Report-Check "App dependency directory present" (Test-Path $AppSubDir)

# 2. Bundled Runtime
$RtJava = Join-Path $RuntimeDir "bin\java.exe"
$JavaPresent = Test-Path $RtJava
Report-Check "Runtime contains java.exe" $JavaPresent

if ($JavaPresent) {
    $JavaVerOutput = & $RtJava -version 2>&1 | Out-String
    $IsJava21 = $JavaVerOutput -match '"(2[1-9]|[3-9][0-9])'
    Report-Check "Runtime version is Java 21+ ($($JavaVerOutput.Split("`n")[0].Trim()))" $IsJava21
}

# 3. Bundled Dependencies
if (Test-Path $AppSubDir) {
    $Jars = Get-ChildItem -Path $AppSubDir -Filter "*.jar"
    Report-Check "Dependency JARs bundled ($($Jars.Count) found)" ($Jars.Count -ge 20)

    $AppJar = Get-ChildItem -Path $AppSubDir -Filter "aegis-fdx-*.jar"
    Report-Check "Application JAR present" ($AppJar.Count -gt 0)

    $LuceneJar = Get-ChildItem -Path $AppSubDir -Filter "lucene-core-*.jar"
    Report-Check "Lucene bundled" ($LuceneJar.Count -gt 0)

    $PdfJar = Get-ChildItem -Path $AppSubDir -Filter "pdfbox-*.jar"
    Report-Check "PDFBox bundled" ($PdfJar.Count -gt 0)

    $SqliteJar = Get-ChildItem -Path $AppSubDir -Filter "sqlite-jdbc-*.jar"
    Report-Check "SQLite-JDBC bundled" ($SqliteJar.Count -gt 0)

    $FxJar = Get-ChildItem -Path $AppSubDir -Filter "javafx.controls*.jar"
    Report-Check "JavaFX bundled" ($FxJar.Count -gt 0)

    $Dlls = Get-ChildItem -Path $AppSubDir -Filter "*.dll"
    if ($Dlls.Count -eq 0) {
        $Dlls = Get-ChildItem -Path (Join-Path $RuntimeDir "bin") -Filter "*.dll"
    }
    Report-Check "Native DLLs bundled ($($Dlls.Count) found)" ($Dlls.Count -gt 0)

    $JunrarJar = Get-ChildItem -Path $AppSubDir -Filter "junrar-*.jar"
    Report-Check "No license-restricted components (junrar absent)" ($JunrarJar.Count -eq 0)

    $CfgFile = Join-Path $AppSubDir "AEGIS-FDX.cfg"
    if (Test-Path $CfgFile) {
        $CfgContent = Get-Content $CfgFile -Raw
        Report-Check "Launcher main class configured in CFG" ($CfgContent -match "com.aegis.fdx.Launcher")
        Report-Check "Heap size (-Xmx) configured in CFG" ($CfgContent -match "-Xmx")
        Report-Check "ZGC garbage collector configured in CFG" ($CfgContent -match "UseZGC")
    }
}

# 4. Engine Smoke Test
if ((Test-Path $AppSubDir) -and $JavaPresent) {
    Write-Host "`n-- Running Engine Smoke Test..." -ForegroundColor Yellow
    $SmokeDir = Join-Path $env:TEMP ("aegis-smoke-" + [System.IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path (Join-Path $SmokeDir "evidence") -Force | Out-Null
    
    $TestMemo = Join-Path $SmokeDir "evidence\memo.txt"
    "Settlement agreement. Invoice INV-99001. Wire transfer 12,500 EUR." | Out-File -FilePath $TestMemo -Encoding utf8

    $AllJars = (Get-ChildItem -Path $AppSubDir -Filter "*.jar").FullName -join ";"
    
    $SmokeCode = @"
import com.aegis.fdx.engine.*;
import com.aegis.fdx.index.*;
import com.aegis.fdx.store.*;
import java.nio.file.*;
public class Smoke {
    public static void main(String[] a) throws Exception {
        Path work = Path.of(a[0]);
        CaseFolder cf = CaseFolder.createOrOpen(work.resolve("case"), "SMOKE");
        CaseSettings st = new CaseSettings();
        st.ocrEnabled(false);
        try (CaseDatabase db = new CaseDatabase(cf.database());
             LuceneIndex ix = new LuceneIndex(cf.index(), 32)) {
            var r = new IngestPipeline(cf, db, ix, st, e -> { })
                        .ingest(work.resolve("evidence"), "Verifier");
            ix.commit();
            int hits = ix.search(
                LuceneQueryBuilder.build("INV-99001", ix.analyzer()), 10, null).size();
            int phrase = ix.search(
                LuceneQueryBuilder.build("\"wire transfer\"", ix.analyzer()), 10, null).size();
            System.out.println("SMOKE processed=" + r.processed()
                + " indexed=" + ix.count() + " termHits=" + hits + " phraseHits=" + phrase);
            if (r.processed() < 1 || ix.count() < 1 || hits < 1 || phrase < 1) {
                System.out.println("SMOKE FAILED");
                System.exit(1);
            }
            System.out.println("SMOKE OK");
        }
    }
}
"@
    $SmokeJava = Join-Path $SmokeDir "Smoke.java"
    $SmokeCode | Out-File -FilePath $SmokeJava -Encoding utf8

    $Javac = if ($JdkPath) { Join-Path $JdkPath "bin\javac.exe" } else { "javac.exe" }
    
    try {
        & $Javac -nowarn -cp $AllJars -d $SmokeDir $SmokeJava
        Report-Check "Smoke harness compiles against bundled JARs" ($LASTEXITCODE -eq 0)

        $RunOut = & $RtJava -cp "$SmokeDir;$AllJars" Smoke $SmokeDir 2>&1 | Out-String
        $Passed = $RunOut -match "SMOKE OK"
        Report-Check "Packaged engine ingests, indexes, and searches evidence ($($RunOut.Trim()))" $Passed
    } catch {
        Report-Check "Packaged engine smoke execution" $false
    } finally {
        if (Test-Path $SmokeDir) { Remove-Item -Recurse -Force $SmokeDir }
    }
}

Write-Host "`n====================================================" -ForegroundColor Cyan
Write-Host " Verification Summary: $PassCount passed, $FailCount failed" -ForegroundColor $(if ($FailCount -eq 0) { "Green" } else { "Red" })
Write-Host "====================================================" -ForegroundColor Cyan

if ($FailCount -gt 0) { exit 1 } else { exit 0 }
