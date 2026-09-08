# AEGIS-FDX — Installation Guide

Version 1.0.0

---

## 1. Requirements

### Primary platform — Windows (N-01)

| | Minimum | Recommended |
|---|---|---|
| OS | Windows 10 x64 (1809+) | Windows 11 x64 |
| CPU | 4 cores | 8+ cores |
| RAM | 8 GB | 16 GB+ |
| Disk | SSD, 3× evidence volume free | NVMe SSD |
| Display | 1366×768 | 1920×1080+ |

**No Java installation is required.** A trimmed Java 21 runtime is bundled.

### Also supported

macOS 12+ (Intel/Apple Silicon) and Linux x64 with glibc 2.31+ (Ubuntu 20.04+,
RHEL 8+). Windows is the primary validation target.

### Disk planning

Budget roughly **3× the evidence volume**: originals stay where they are, plus the
index (~15% of text volume), extracted text (~10%), preserved embedded elements,
and room for exports.

---

## 2. Installing on Windows

1. Run `AEGIS-FDX-1.0.0.msi`.
2. Choose an installation directory (default `C:\Program Files\AEGIS-FDX`).
3. Choose shortcuts, then Install. Administrator rights are required.
4. Launch from the Start menu.

### Silent / managed deployment

```bat
msiexec /i AEGIS-FDX-1.0.0.msi /qn INSTALLDIR="C:\Program Files\AEGIS-FDX"
msiexec /x AEGIS-FDX-1.0.0.msi /qn
```

Upgrades install in place — the MSI carries a stable UpgradeCode, so 1.0.1 replaces
1.0.0 rather than installing alongside it.

### If SmartScreen appears

Unsigned builds trigger "Windows protected your PC" → **More info** → **Run anyway**.
Signed release builds do not. See §6.

---

## 3. Installing on macOS and Linux

**macOS:** open `AEGIS-FDX-1.0.0.dmg`, drag to Applications. If Gatekeeper blocks an
unsigned build, right-click → Open, or:
```bash
xattr -dr com.apple.quarantine /Applications/AEGIS-FDX.app
```

**Linux (Debian/Ubuntu):**
```bash
sudo dpkg -i aegis-fdx_1.0.0_amd64.deb
sudo apt-get install -f      # if dependencies are missing
```

---

## 4. Verifying the installation

Do this before processing real evidence:

```bash
packaging/verify-install.sh "/c/Program Files/AEGIS-FDX"   # Windows (Git Bash)
packaging/verify-install.sh /Applications/AEGIS-FDX.app    # macOS
packaging/verify-install.sh /opt/aegis-fdx                 # Linux
```

This checks the launcher, bundled runtime, dependency jars, JavaFX native
libraries, heap/GC configuration, and licence compliance — then runs a **live
engine smoke test** that creates a case, ingests a file, indexes it and searches
it using the installed binaries.

Expected: `=== 20 passed, 0 failed ===`

---

## 5. Installing OCR (optional)

OCR requires Tesseract. Without it the application runs normally, skips OCR, and
says so in the processing report.

**Windows:** install the UB Mannheim Tesseract build, then either add it to `PATH`
or point the app at it:
```
-Daegis.tesseract="C:\Program Files\Tesseract-OCR\tesseract.exe"
-Daegis.tessdata="C:\Program Files\Tesseract-OCR\tessdata"
```

**macOS:** `brew install tesseract tesseract-lang`
**Linux:** `sudo apt install tesseract-ocr tesseract-ocr-eng`

Confirm in **Settings** — the OCR row shows the detected version and installed
languages. Add languages by installing the matching `tessdata` files.

---

## 6. Code signing (release builds)

Unsigned installers trigger SmartScreen/Gatekeeper warnings. For distribution:

**Windows** — an EV or OV code-signing certificate (`.pfx`):
```bash
export AEGIS_CERT="C:/certs/aegis.pfx"
export AEGIS_CERT_PASS="..."
packaging/build-installer.sh --type msi --sign
```
Signs with SHA-256 and an RFC-3161 timestamp so signatures outlive the certificate,
then verifies. EV certificates establish SmartScreen reputation immediately.

**macOS** — a Developer ID Application certificate:
```bash
export AEGIS_MAC_IDENTITY="Developer ID Application: Example (TEAMID)"
packaging/build-installer.sh --type dmg --sign
xcrun notarytool submit dist/AEGIS-FDX-1.0.0.dmg --apple-id ... --wait
xcrun stapler staple dist/AEGIS-FDX-1.0.0.dmg
```

Never commit certificates or passwords; inject them from the CI secret store.

---

## 7. Configuration

Runtime options live in the launcher config
(`app/AEGIS-FDX.cfg` on Windows, `lib/app/AEGIS-FDX.cfg` on Linux):

```
-Xmx4g                    # heap — raise to 8g+ for very large cases
-XX:+UseZGC               # low-pause collector
-XX:MaxGCPauseMillis=50
```

| Property | Purpose |
|---|---|
| `aegis.caseDir` | Default case location |
| `aegis.tesseract` | Path to the Tesseract executable |
| `aegis.tessdata` | Path to tessdata |
| `aegis.ocrLibPath` | Extra native library path for OCR |

---

## 8. Security and network

The application is **fully offline**. It makes no network connections, sends no
telemetry, and requires no licence server. It can run on an air-gapped machine.

Recommended for evidence handling:
- Store cases on encrypted volumes (case-folder AES-256 is on by default).
- Keep evidence on read-only media or shares where possible; the application never
  writes to source evidence, and **Verify** proves it.
- Restrict case-folder access to the review team.

---

## 9. Uninstalling

Windows: Settings → Apps → AEGIS-FDX → Uninstall (or `msiexec /x`).
macOS: drag the app to Trash. Linux: `sudo apt remove aegis-fdx`.

**Case folders are never removed by uninstalling.** Delete them separately if
required by your retention policy.

---

## 10. Installation troubleshooting

| Symptom | Cause and fix |
|---|---|
| "JavaFX runtime components are missing" | Incomplete image — the JavaFX native libraries are absent. Reinstall from an official installer; verify with `verify-install.sh`. |
| Installer needs administrator rights | Expected for a per-machine install. |
| SmartScreen / Gatekeeper warning | Unsigned build — see §6. |
| Application starts then exits | Check the case folder is writable and disk space is available. |
| Out of memory on large cases | Raise `-Xmx` in the launcher config. |
| OCR not detected | Confirm the Tesseract path in Settings; check `aegis.tesseract`. |
| Slow ingestion | Verify evidence and case folder are on SSD, not a network share. |
