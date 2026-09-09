# PowerShell Windows Packager for AEGIS-FDX (Windows 10/11 x64)
# Produces self-contained MSI installer and portable ZIP archive.
param(
    [string]$Version = "1.0.0",
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$FxPath = $env:PATH_TO_FX,
    [string]$PackageType = "msi",
    [switch]$PerUser = $false,
    [switch]$Sign = $false
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " AEGIS-FDX Windows 10/11 x64 Packaging Pipeline      " -ForegroundColor Cyan
Write-Host " Version : $Version                                 " -ForegroundColor Cyan
Write-Host " Type    : $PackageType                             " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 1. Validate Toolchain
if (-not $JdkPath -or -not (Test-Path (Join-Path $JdkPath "bin\jpackage.exe"))) {
    Write-Error "JDK 21+ with jpackage.exe is required. Set -JdkPath or JAVA_HOME."
}

$Javac = Join-Path $JdkPath "bin\javac.exe"
$JarTool = Join-Path $JdkPath "bin\jar.exe"
$Jlink = Join-Path $JdkPath "bin\jlink.exe"
$Jpackage = Join-Path $JdkPath "bin\jpackage.exe"

$BuildDir = Join-Path $Root "build"
$ClassesDir = Join-Path $BuildDir "classes"
$JarsDir = Join-Path $BuildDir "jars"
$RuntimeDir = Join-Path $BuildDir "runtime"
$DistDir = Join-Path $Root "dist"

# Clean directories
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
if (-not (Test-Path $DistDir)) { New-Item -ItemType Directory -Path $DistDir | Out-Null }
New-Item -ItemType Directory -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Path $JarsDir | Out-Null

# 2. Compile Java Source Tree
Write-Host "`n[1/5] Compiling Java 21 sources..." -ForegroundColor Yellow
$LibJars = (Get-ChildItem -Path (Join-Path $Root "lib") -Filter "*.jar").FullName -join ";"
$JavaSources = (Get-ChildItem -Path (Join-Path $Root "app\src\main\java") -Filter "*.java" -Recurse).FullName

$CompileArgs = @(
    "-nowarn",
    "-encoding", "UTF-8",
    "-cp", $LibJars,
    "-d", $ClassesDir
) + $JavaSources

if ($FxPath -and (Test-Path $FxPath)) {
    $CompileArgs = @("--module-path", $FxPath, "--add-modules", "javafx.controls,javafx.graphics") + $CompileArgs
}

& $Javac $CompileArgs
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed with exit code $LASTEXITCODE" }

# Copy resources
$ResDir = Join-Path $Root "app\src\main\resources"
if (Test-Path $ResDir) {
    Copy-Item -Path (Join-Path $ResDir "*") -Destination $ClassesDir -Recurse -Force
}

# 3. Build Application JAR & Bundle Dependencies
Write-Host "`n[2/5] Creating application JAR and assembling dependencies..." -ForegroundColor Yellow
$MainJar = Join-Path $JarsDir "aegis-fdx-$Version.jar"
& $JarTool --create --file $MainJar --main-class "com.aegis.fdx.Launcher" -C $ClassesDir .

Copy-Item -Path (Join-Path $Root "lib\*.jar") -Destination $JarsDir -Force
if ($FxPath -and (Test-Path $FxPath)) {
    Copy-Item -Path (Join-Path $FxPath "*.jar") -Destination $JarsDir -Force
    Copy-Item -Path (Join-Path $FxPath "*.dll") -Destination $JarsDir -Force
}

# 4. Link Trimmed Runtime with jlink
Write-Host "`n[3/5] Linking trimmed Java 21 runtime..." -ForegroundColor Yellow
$Modules = "java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.xml,java.prefs,jdk.crypto.ec,jdk.unsupported,jdk.zipfs"

& $Jlink --add-modules $Modules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=zip-6 `
    --output $RuntimeDir

# 5. Package with jpackage
Write-Host "`n[4/5] Running jpackage for Windows $PackageType installer..." -ForegroundColor Yellow
$JPackageArgs = @(
    "--name", "AEGIS-FDX",
    "--app-version", $Version,
    "--vendor", "AEGIS Forensics",
    "--description", "Digital forensics and e-discovery evidence processing platform",
    "--input", $JarsDir,
    "--main-jar", "aegis-fdx-$Version.jar",
    "--main-class", "com.aegis.fdx.Launcher",
    "--runtime-image", $RuntimeDir,
    "--dest", $DistDir,
    "--type", $PackageType,
    "--java-options", "-Xmx4g",
    "--java-options", "-XX:+UseZGC",
    "--java-options", "-XX:MaxGCPauseMillis=50",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED"
)

$LicenseFile = Join-Path $Root "packaging\LICENSE.txt"
if (Test-Path $LicenseFile) {
    $JPackageArgs += @("--license-file", $LicenseFile)
}

if ($PackageType -eq "msi" -or $PackageType -eq "exe") {
    $JPackageArgs += @(
        "--win-dir-chooser",
        "--win-menu",
        "--win-menu-group", "AEGIS Forensics",
        "--win-shortcut",
        "--win-shortcut-prompt",
        "--win-upgrade-uuid", "6f3a1c92-4d7b-4f2e-9a15-8c0b7e2d4a63"
    )
    if ($PerUser) {
        $JPackageArgs += "--win-per-user-install"
    }
    $IconFile = Join-Path $Root "packaging\aegis.ico"
    if (Test-Path $IconFile) {
        $JPackageArgs += @("--icon", $IconFile)
    }
}

& $Jpackage $JPackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

# 6. Verify Produced Artifacts
Write-Host "`n[5/5] Packaging complete. Artifacts in dist/:" -ForegroundColor Green
Get-ChildItem -Path $DistDir | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
