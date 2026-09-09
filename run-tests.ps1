<#
.SYNOPSIS
    Runs the full AEGIS-FDX validation battery on Windows without bash.

.DESCRIPTION
    PowerShell equivalent of run-tests.sh. Verifies the source tree, compiles main
    and test sources, then runs every suite and reports a combined total.

    Requires only a JDK 21 and a JavaFX 21 SDK - no bash, no Gradle.

.PARAMETER Multiplier
    Corpus size multiplier for the benchmark. Default 2. Use 2000 for performance
    certification on reference hardware.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File run-tests.ps1
    powershell -ExecutionPolicy Bypass -File run-tests.ps1 -Multiplier 2000
#>

param(
    [int]$Multiplier = 2,
    [string]$JdkBin  = $env:AEGIS_JDK,
    [string]$FxLib   = $env:AEGIS_FX
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Fail($msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

# ---- toolchain -------------------------------------------------------------
if (-not $JdkBin) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $JdkBin = Split-Path -Parent $javac.Source }
}
if (-not $JdkBin) {
    Fail "No JDK found. Set AEGIS_JDK to your JDK 21 bin directory, or put javac on PATH."
}
$javacExe = Join-Path $JdkBin 'javac.exe'
$javaExe  = Join-Path $JdkBin 'java.exe'
if (-not (Test-Path $javacExe)) { Fail "javac.exe not found in $JdkBin" }

# Locate the JavaFX SDK: explicit setting, then common install roots, then any
# javafx-sdk-* beside or under the project. Hardcoding one path was unhelpful.
if (-not $FxLib) {
    $candidates = @()
    foreach ($base in @('C:\', 'C:\Program Files\', 'C:\tools\',
                        "$env:USERPROFILE\", "$root\", "$root\..\")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem -LiteralPath $base -Directory -Filter 'javafx-sdk*' `
                              -ErrorAction SilentlyContinue |
                           ForEach-Object { Join-Path $_.FullName 'lib' }
        }
    }
    $FxLib = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
# Fallback: Gradle already downloads JavaFX from Maven Central, so a Gradle-built
# machine has the jars cached even with no SDK installed. Reuse them rather than
# demanding a separate manual download.
if (-not $FxLib -or -not (Test-Path $FxLib)) {
    $cacheRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.openjfx'
    if (Test-Path $cacheRoot) {
        $fxJars = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter '*win*.jar' -File -ErrorAction SilentlyContinue |
                  Where-Object { $_.Name -notmatch 'sources|javadoc' }
        if ($fxJars) {
            $fxStage = Join-Path $root 'build\fx-lib'
            New-Item -ItemType Directory -Force -Path $fxStage | Out-Null
            $fxJars | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $fxStage -Force }
            $FxLib = $fxStage
            Write-Host "JavaFX:  (from Gradle cache) $FxLib" -ForegroundColor DarkGray
        }
    }
}

if (-not $FxLib -or -not (Test-Path $FxLib)) {
    Write-Host ''
    Write-Host 'JavaFX SDK not found.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'Download the JavaFX 21 SDK for Windows x64 from:'
    Write-Host '    https://gluonhq.com/products/javafx/'
    Write-Host 'Unzip it, then point AEGIS_FX at its lib directory, e.g.:'
    Write-Host '    set AEGIS_FX=C:\javafx-sdk-21.0.4\lib'
    Write-Host ''
    Write-Host 'Searched: C:\, C:\Program Files\, C:\tools\, your home directory,'
    Write-Host 'the project folder for javafx-sdk*, and the Gradle module cache.'
    Write-Host ''
    Write-Host 'Alternatively just use Gradle, which resolves JavaFX automatically:'
    Write-Host '    .\gradlew :app:test'
    exit 1
}

Write-Host "JDK:     $JdkBin"
Write-Host "JavaFX:  $FxLib"

# ---- source integrity ------------------------------------------------------
Write-Host ''
Write-Host '== verifying source tree =='
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'packaging\verify-sources.ps1')
if ($LASTEXITCODE -ne 0) {
    Fail "Source verification failed. Fix the tree before building - see output above."
}

# ---- classpath -------------------------------------------------------------
$libDir = Join-Path $root 'lib'
if (-not (Test-Path $libDir)) { Fail "lib\ not found. The dependency jars are missing from this copy." }
$cp = (Get-ChildItem -LiteralPath $libDir -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'

$outMain = Join-Path $root 'build\classes'
$outTest = Join-Path $root 'build\test-classes'
$work    = Join-Path $root 'build\test-work'
New-Item -ItemType Directory -Force -Path $outMain, $outTest, $work | Out-Null

# ---- compile ---------------------------------------------------------------
Write-Host ''
Write-Host '== compiling main =='
$mainSrc = Get-ChildItem -LiteralPath (Join-Path $root 'app\src\main\java') -Recurse -Filter *.java |
           ForEach-Object { $_.FullName }
$mainList = Join-Path $env:TEMP 'aegis-main-sources.txt'
[System.IO.File]::WriteAllLines($mainList, $mainSrc, (New-Object System.Text.UTF8Encoding($false)))
& $javacExe -nowarn --module-path $FxLib --add-modules javafx.controls `
    -cp $cp -d $outMain "@$mainList"
if ($LASTEXITCODE -ne 0) { Fail 'Main compilation failed.' }

# Resources (aegis.css and friends) must sit alongside the classes.
$resDir = Join-Path $root 'app\src\main\resources'
if (Test-Path $resDir) { Copy-Item -Path (Join-Path $resDir '*') -Destination $outMain -Recurse -Force }

Write-Host '== compiling tests =='
$testSrc = Get-ChildItem -LiteralPath (Join-Path $root 'app\src\test\java') -Recurse -Filter *.java |
           ForEach-Object { $_.FullName }
$testList = Join-Path $env:TEMP 'aegis-test-sources.txt'
[System.IO.File]::WriteAllLines($testList, $testSrc, (New-Object System.Text.UTF8Encoding($false)))
& $javacExe -nowarn -proc:none --module-path $FxLib --add-modules javafx.controls `
    -cp "$outMain;$cp" -d $outTest "@$testList"
if ($LASTEXITCODE -ne 0) { Fail 'Test compilation failed.' }

# ---- suites ----------------------------------------------------------------
$runCp = "$outTest;$outMain;$cp"
$totalPassed = 0
$totalFailed = 0
$suiteCount  = 0

function Invoke-Suite($title, $class, [string[]]$suiteArgs) {
    Write-Host ''
    Write-Host "== $title ==" -ForegroundColor Cyan
    $errFile = Join-Path $env:TEMP 'aegis-suite-err.txt'
    if (Test-Path $errFile) { Remove-Item -LiteralPath $errFile -Force }
    $out = & $javaExe -Xmx900m -cp $runCp $class @suiteArgs 2> $errFile
    $code = $LASTEXITCODE
    $out | ForEach-Object { Write-Host $_ }
    if (Test-Path $errFile) {
        Get-Content -LiteralPath $errFile | ForEach-Object { Write-Host $_ }
        Remove-Item -LiteralPath $errFile -Force
    }
    if ($code -ne 0) { Fail "$title failed (exit $code)." }
    foreach ($line in $out) {
        if ("$line" -match '^\s*(?:===\s*)?(\d+)\s+passed,\s*(\d+)\s+failed(?:\s*===)?\s*$') {
            $script:totalPassed += [int]$Matches[1]
            $script:totalFailed += [int]$Matches[2]
            $script:suiteCount++
        }
    }
}

Invoke-Suite 'query parser unit tests (M1)' 'com.aegis.fdx.QueryParserTest' @()
Invoke-Suite 'query validation (unknown fields / dates / regex)' 'com.aegis.fdx.QueryValidationTest' @()

Write-Host ''
Write-Host '== generating test dataset (D-04) ==' -ForegroundColor Cyan
& $javaExe -Xmx900m -cp $runCp com.aegis.fdx.TestDataset (Join-Path $work 'testdata')

Invoke-Suite 'drag-and-drop intake (F-01)' 'com.aegis.fdx.DragDropIngestTest' @((Join-Path $work 'dnd'))
Invoke-Suite 'AI boundary (B-01..B-07)' 'com.aegis.fdx.AiBoundaryTest' @((Join-Path $work 'aiboundary'))
Invoke-Suite 'windows compatibility (N-01)' 'com.aegis.fdx.WindowsCompatibilityTest' @((Join-Path $work 'win'))
Invoke-Suite 'pipeline acceptance AT-01..AT-10 (M2)' 'com.aegis.fdx.PipelineAcceptanceTest' @((Join-Path $work 'at'))
Invoke-Suite 'milestone 3 acceptance (OCR / export / reports / integrity)' 'com.aegis.fdx.M3AcceptanceTest' @((Join-Path $work 'm3'))

Invoke-Suite 'case settings persistence' 'com.aegis.fdx.SettingsPersistenceTest' @()
Invoke-Suite 'host metrics' 'com.aegis.fdx.HostMetricsTest' @()

function Invoke-JUnit($title, [string[]]$classes, [string[]]$jvmArgs) {
    Write-Host ''
    Write-Host "== $title ==" -ForegroundColor Cyan
    & $javaExe @jvmArgs com.aegis.fdx.JUnitRunner @classes
    if ($LASTEXITCODE -ne 0) { Fail "$title failed (exit $LASTEXITCODE)." }
}

Invoke-JUnit 'JUnit suites (facade, agent, batch, model, scenario, resolver)' @(
    'com.aegis.fdx.FacadeParityTest',
    'com.aegis.fdx.AiAgentTest',
    'com.aegis.fdx.BatchAnalysisTest',
    'com.aegis.fdx.IntegrationModelTest',
    'com.aegis.fdx.EndToEndScenarioTest',
    'com.aegis.fdx.SettingsPersistenceTest',
    'com.aegis.fdx.HostMetricsTest',
    'com.aegis.fdx.FailureRecoveryTest',
    'com.aegis.fdx.AegisCharsetProviderTest',
    'com.aegis.fdx.SearchResultResolverTest'
) @('-Xmx900m', '-cp', $runCp)

Invoke-Suite 'architecture invariants' 'com.aegis.fdx.ArchitectureInvariantsTest' @((Join-Path $work 'arch'))
Invoke-Suite 'failure and recovery' 'com.aegis.fdx.ResilienceTest' @((Join-Path $work 'resilience'))
Invoke-Suite 'coverage inventory' 'com.aegis.fdx.CoverageMatrixTest' @()
Invoke-Suite 'interface-function matrix' 'com.aegis.fdx.InterfaceFunctionMatrixTest' @()

$fxControls = Join-Path $FxLib 'javafx.controls.jar'
if (Test-Path $fxControls) {
    $uiJvm = @('-Xmx900m', '--module-path', $FxLib, '--add-modules', 'javafx.controls,javafx.graphics', '-cp', $runCp)
} else {
    $uiJvm = @('-Xmx900m', '-cp', $runCp)
}
Invoke-JUnit 'interface suites (parity, destinations, relationships, bridge)' @(
    'com.aegis.fdx.UiParityTest',
    'com.aegis.fdx.DestinationCoverageTest',
    'com.aegis.fdx.RelationshipModelTest',
    'com.aegis.fdx.SuiteBridgeTest'
) $uiJvm

Write-Host ''
Write-Host '== benchmark (N-02 / F-18 / N-03) ==' -ForegroundColor Cyan
& $javaExe -Xmx900m -cp $runCp com.aegis.fdx.Benchmark (Join-Path $work 'bench') $Multiplier

# ---- summary ---------------------------------------------------------------
Write-Host ''
Write-Host '==================================================================='
Write-Host (" {0} suites: {1} assertions, {2} failures" -f $suiteCount, $totalPassed, $totalFailed)
Write-Host '==================================================================='

if ($totalFailed -gt 0) { exit 1 }
if ($suiteCount -lt 12) {
    Write-Host "WARNING: expected 12 footer-counted suites, saw $suiteCount - a suite may not have run." -ForegroundColor Yellow
    exit 1
}
Write-Host 'ALL SUITES COMPLETED' -ForegroundColor Green
exit 0
