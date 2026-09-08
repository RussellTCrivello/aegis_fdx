#!/usr/bin/env bash
#
# AEGIS-FDX installer builder (Windows / macOS / Linux).
#
# Produces a self-contained application image with a trimmed Java runtime, then
# wraps it in the platform's native installer format via jpackage.
#
#   Windows : .msi  (primary target, N-01)
#   macOS   : .dmg
#   Linux   : .deb
#
# Usage:
#   packaging/build-installer.sh [--type msi|exe|dmg|deb|app-image] [--sign]
#
# Requires JDK 21+ (jpackage is part of the JDK). On Windows an MSI additionally
# requires the WiX Toolset v3 on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_NAME="AEGIS-FDX"
APP_VERSION="${AEGIS_VERSION:-1.0.0}"
VENDOR="AEGIS Forensics"
MAIN_CLASS="com.aegis.fdx.Launcher"
DESCRIPTION="Digital forensics and e-discovery evidence processing platform"

JDK="${AEGIS_JDK:-$HOME/.cache/tools/jdk21/bin}"
FX="${AEGIS_FX:-$HOME/.cache/tools/javafx-sdk-21.0.4/lib}"
BUILD="build"
CLASSES="$BUILD/classes"
JARS="$BUILD/jars"
RUNTIME="$BUILD/runtime"
IMAGE="$BUILD/image"
DIST="dist"

TYPE=""
SIGN=0
PER_USER=0
while [ $# -gt 0 ]; do
    case "$1" in
        --type) TYPE="$2"; shift 2 ;;
        --sign) SIGN=1; shift ;;
        # Per-user MSI: installs under %LOCALAPPDATA% and needs no administrator
        # rights. Required for locked-down forensic workstations.
        --per-user) PER_USER=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# ---- platform defaults -----------------------------------------------------
UNAME="$(uname -s)"
case "$UNAME" in
    MINGW*|MSYS*|CYGWIN*|Windows_NT) PLATFORM=windows; DEFAULT_TYPE=msi ;;
    Darwin)                          PLATFORM=mac;     DEFAULT_TYPE=dmg ;;
    *)                               PLATFORM=linux;   DEFAULT_TYPE=deb ;;
esac
TYPE="${TYPE:-$DEFAULT_TYPE}"

echo "== AEGIS-FDX packaging =="
echo "   platform : $PLATFORM"
echo "   type     : $TYPE"
echo "   version  : $APP_VERSION"
echo

# ---- 0. toolchain preflight ------------------------------------------------
# Packaging needs tools that the test battery does not: jlink and jpackage ship
# only with a full JDK. Saying so plainly, once, is far more useful than a
# "No such file or directory" from somewhere in the middle of the build. Exit
# status 3 means "this host cannot package", which the acceptance suite reports
# as a skip rather than as a product failure.
MISSING=""
for tool in javac jar jlink jpackage; do
    if [ ! -x "$JDK/$tool" ] && ! command -v "$tool" >/dev/null 2>&1; then
        MISSING="$MISSING $tool"
    fi
done
if [ ! -d "$FX" ]; then
    MISSING="$MISSING javafx-sdk($FX)"
fi
if [ -n "$MISSING" ]; then
    echo "TOOLCHAIN UNAVAILABLE — missing:$MISSING" >&2
    echo "Packaging requires a full JDK 21+ (javac, jar, jlink, jpackage) and the" >&2
    echo "JavaFX SDK. Set AEGIS_JDK=<jdk>/bin and AEGIS_FX=<javafx-sdk>/lib and retry." >&2
    exit 3
fi

# ---- 1. compile ------------------------------------------------------------
echo "-- compiling"
rm -rf "$CLASSES" "$JARS" "$RUNTIME" "$IMAGE"
mkdir -p "$CLASSES" "$JARS" "$DIST"
# jpackage refuses to overwrite an existing app-image.
rm -rf "${DIST:?}/$APP_NAME"
CP="$(ls lib/*.jar | tr '\n' ':')"
"$JDK/javac" -nowarn --module-path "$FX" --add-modules javafx.controls \
    -cp "$CP" -d "$CLASSES" $(find app/src/main/java -name '*.java')
cp -r app/src/main/resources/* "$CLASSES/" 2>/dev/null || true

# ---- 2. application jar ----------------------------------------------------
echo "-- building application jar"
"$JDK/jar" --create --file "$JARS/aegis-fdx-$APP_VERSION.jar" \
    --main-class "$MAIN_CLASS" -C "$CLASSES" .
cp lib/*.jar "$JARS/"
# JavaFX platform jars AND their native libraries must travel with the app.
# Shipping only the jars produces an image that fails at startup.
cp "$FX"/*.jar "$JARS/" 2>/dev/null || true
case "$PLATFORM" in
    windows) cp "$FX"/*.dll     "$JARS/" 2>/dev/null || true ;;
    mac)     cp "$FX"/*.dylib   "$JARS/" 2>/dev/null || true ;;
    linux)   cp "$FX"/*.so      "$JARS/" 2>/dev/null || true ;;
esac

# ---- 3. trimmed runtime ----------------------------------------------------
# Only the modules actually used. Keeps the installer near ~90 MB instead of
# shipping a full JDK.
echo "-- linking trimmed runtime"
MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.xml,java.prefs,jdk.crypto.ec,jdk.unsupported,jdk.zipfs"
FX_JMODS="${AEGIS_FX_JMODS:-}"
if [ -n "$FX_JMODS" ] && [ -d "$FX_JMODS" ]; then
    "$JDK/jlink" --module-path "$FX_JMODS" \
        --add-modules "$MODULES,javafx.controls,javafx.graphics,javafx.swing" \
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
        --output "$RUNTIME"
else
    # JavaFX shipped as classpath jars rather than modules.
    "$JDK/jlink" --add-modules "$MODULES" \
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
        --output "$RUNTIME"
fi

# ---- 4. jpackage -----------------------------------------------------------
echo "-- running jpackage"
ARGS=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --vendor "$VENDOR"
    --description "$DESCRIPTION"
    --input "$JARS"
    --main-jar "aegis-fdx-$APP_VERSION.jar"
    --main-class "$MAIN_CLASS"
    --runtime-image "$RUNTIME"
    --dest "$DIST"
    --type "$TYPE"
    # N-04: 4 GB default heap, ZGC for sub-50ms pauses under load.
    --java-options "-Xmx4g"
    --java-options "-XX:+UseZGC"
    --java-options "-XX:MaxGCPauseMillis=50"
    --java-options "-Dfile.encoding=UTF-8"
    # JavaFX on the classpath needs these opens for the SW pipeline fallback.
    --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED"
)

# Installer-only options are rejected outright when building a bare app-image,
# so they are applied only for real installer types.
INSTALLER=1
[ "$TYPE" = "app-image" ] && INSTALLER=0

# The licence page is a release requirement, not an optional extra: a silent
# skip here would ship an installer with no licence shown.
if [ "$INSTALLER" = "1" ]; then
    if [ -f packaging/LICENSE.txt ]; then
        ARGS+=(--license-file packaging/LICENSE.txt)
    else
        echo "ERROR: packaging/LICENSE.txt is required for installer builds." >&2
        exit 1
    fi
fi

case "$PLATFORM" in
    windows)
        [ "$INSTALLER" = "1" ] && ARGS+=(
            --win-dir-chooser
            --win-menu
            --win-menu-group "$VENDOR"
            --win-shortcut
            --win-shortcut-prompt
            # Stable UpgradeCode so 1.0.1 upgrades 1.0.0 rather than
            # installing a second copy alongside it.
            --win-upgrade-uuid "6f3a1c92-4d7b-4f2e-9a15-8c0b7e2d4a63"
        )
        # A per-machine MSI requires elevation. --per-user produces a build that a
        # standard user can install without an administrator.
        if [ "$INSTALLER" = "1" ] && [ "$PER_USER" = "1" ]; then
            ARGS+=(--win-per-user-install)
        fi
        [ -f packaging/aegis.ico ] && ARGS+=(--icon packaging/aegis.ico)
        ;;
    mac)
        ARGS+=(--mac-package-identifier com.aegis.fdx)
        [ -f packaging/aegis.icns ] && ARGS+=(--icon packaging/aegis.icns)
        if [ "$SIGN" = "1" ]; then
            ARGS+=(--mac-sign)
            [ -n "${AEGIS_MAC_IDENTITY:-}" ] && \
                ARGS+=(--mac-signing-key-user-name "$AEGIS_MAC_IDENTITY")
        fi
        ;;
    linux)
        [ "$INSTALLER" = "1" ] && ARGS+=(--linux-shortcut --linux-menu-group Office)
        [ -f packaging/aegis.png ] && ARGS+=(--icon packaging/aegis.png)
        ;;
esac

"$JDK/jpackage" "${ARGS[@]}"

# ---- 5. Windows signing ----------------------------------------------------
# jpackage does not sign on Windows; signtool runs afterwards.
if [ "$PLATFORM" = "windows" ] && [ "$SIGN" = "1" ]; then
    echo "-- signing"
    : "${AEGIS_CERT:?set AEGIS_CERT to the .pfx path}"
    : "${AEGIS_CERT_PASS:?set AEGIS_CERT_PASS}"
    for artifact in "$DIST"/*.msi "$DIST"/*.exe; do
        [ -e "$artifact" ] || continue
        signtool sign /fd SHA256 /f "$AEGIS_CERT" /p "$AEGIS_CERT_PASS" \
            /tr http://timestamp.digicert.com /td SHA256 "$artifact"
        signtool verify /pa /v "$artifact"
    done
fi

echo
echo "== artifacts =="
ls -la "$DIST"
echo
echo "Verify the installation with: packaging/verify-install.sh"
