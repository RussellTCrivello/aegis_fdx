# AEGIS-FDX Clean-Machine Packaging Acceptance Runbook

Status: **Production Qualification Procedure**  
Target Architecture: Windows 10 / Windows 11 Enterprise x64  
Artifact: `AEGIS-FDX-1.0.0.msi` (SHA-256 validated against manifest)  
Release Candidate Baseline: Commit `b923930` (Pre-packaging baseline: `64f7cb5`)

---

## 1. Prerequisites for Clean Test Machine

- Clean Windows 10 (21H2+) or Windows 11 (22H2+) installation.
- No pre-installed Java JRE/JDK on `PATH` or in environment variables.
- Standard user and Administrator test accounts configured.
- Test evidence dataset: Standard test corpus with multi-format files (PDF, DOCX, XLSX, TXT, MSG/EML).

---

## 2. Step-by-Step Execution Lifecycle

```text
  [1. Clean Machine] ──> [2. Install MSI] ──> [3. Launch EXE] ──> [4. Create Case]
         │
         ▼
  [5. Ingest Evidence] ──> [6. Background Process] ──> [7. Lucene Search]
         │
         ▼
  [8. Relationship Graph] ──> [9. Comprehensive Dashboard] ──> [10. Settings & I18n Switch]
         │
         ▼
  [11. Close Application] ──> [12. Reopen & State Check] ──> [13. Uninstall / Upgrade]
```

### Stage 1: Clean Machine Verification
1. Ensure no existing `AEGIS-FDX` directories exist under `C:\Program Files\AEGIS Forensics` or `%LOCALAPPDATA%\AEGIS-FDX`.
2. Confirm `where java` returns `INFO: Could not find files for the given pattern(s).`

### Stage 2: MSI Installation
1. Run installer:
   ```cmd
   msiexec /i AEGIS-FDX-1.0.0.msi /l*v install.log
   ```
2. Verify:
   - Program files installed to `C:\Program Files\AEGIS Forensics\AEGIS-FDX\`.
   - Desktop shortcut and Start Menu entry created.
   - Registry entries properly registered with UpgradeCode `6f3a1c92-4d7b-4f2e-9a15-8c0b7e2d4a63`.
   - Run automated verification:
     ```powershell
     powershell -ExecutionPolicy Bypass -File packaging\verify-install.ps1
     ```

### Stage 3: Launch
1. Execute `C:\Program Files\AEGIS Forensics\AEGIS-FDX\AEGIS-FDX.exe`.
2. Observe startup time (< 2.5 seconds on SSD).
3. Confirm JavaFX hardware-accelerated Direct3D / Prism rendering initialized.

### Stage 4: Create Case
1. Click **New Case**.
2. Set Case Name: `Matter-2026-Alpha`.
3. Select Case Storage Path: `D:\ForensicCases\Matter-2026-Alpha`.
4. Verify directory structure initialized:
   - `case.db` (SQLite database with WAL mode)
   - `index/` (Lucene directory)
   - `evidence/` (Read-only evidence vault)

### Stage 5: Ingest Evidence
1. Drag and drop test evidence folder (containing PDF, DOCX, TXT, CSV).
2. Assign Source: `Custodian-HR`.
3. Assign Aspect: `Defendant`.
4. Initiate Ingestion.

### Stage 6: Background Processing & Status
1. Observe background ingestion pipeline (`Background.job()`) executing on worker threads.
2. Verify UI remains completely responsive (smooth scrolling, zero UI freezes).
3. Confirm status badges update: `Unread` -> `Read` upon completion.
4. Verify SHA-256 and MD5 hash values calculated and recorded in `case.db`.

### Stage 7: Lucene Full-Text Search
1. Open **Search** screen.
2. Query for exact terms (e.g. `"wire transfer"`, `INV-*`, boolean operators).
3. Verify persistent identity resolver navigates directly to the exact file detail record via `path_id`.

### Stage 8: Relationship Graph & Retrospective Indexing
1. Navigate to **Keywords** screen.
2. Create new Keyword (3+ words): `"unauthorized fund transfer"`.
3. Verify retrospective indexing immediately scans existing case content in background.
4. Confirm hit counters update across Keyword, Category, and File screens.

### Stage 9: Comprehensive Dashboard (7 Tabs)
1. Open **Comprehensive Dashboard**.
2. Click through all 7 tabs:
   - **Files**: Aggregated status counts and size distribution.
   - **Categories**: Category file coverage.
   - **Keywords**: Term frequency and match counts.
   - **Sources**: Evidence breakdown by custodian.
   - **Sides**: Prosecution vs Defense distribution.
   - **Words**: Word cloud and frequency table.
   - **Similar Files**: Near-duplicate detection.
3. Confirm all charts and tables render with 0 errors.

### Stage 10: Settings & I18n Language Switch
1. Navigate to **Settings** screen -> **General** tab.
2. Select **Nederlands (nl)** in the Language dropdown.
3. Verify UI labels update immediately (e.g., *Bestanden*, *Sleutelzinnen*, *Categorieën*, *Bronnen*).
4. Select **Deutsch (de)**; verify UI updates to *Dateien*, *Schlüsselphrasen*, *Kategorien*, *Quellen*.
5. Select **Français (fr)** and **Español (es)**; verify clean transitions.
6. Return to **English (en)**.
7. Confirm that term normalization (`Terms.normalize()`) and database queries remained invariant.

### Stage 11: Close Application
1. Close the application via the window close button (`X`) or **File -> Exit**.
2. Verify all background worker threads terminate cleanly within 1 second.
3. Verify SQLite connection cleanly checkpoints WAL logs (`case.db-wal` cleared).

### Stage 12: Reopen & State Integrity
1. Re-launch `AEGIS-FDX.exe`.
2. Select and open `Matter-2026-Alpha`.
3. Verify:
   - All ingested files, keywords, categories, and relationships load accurately.
   - Lucene search returns identical hit counts.
   - Settings persist (active language, display preferences).

### Stage 13: Uninstall / Upgrade
1. Run uninstaller:
   ```cmd
   msiexec /x AEGIS-FDX-1.0.0.msi /qn
   ```
2. Verify:
   - `C:\Program Files\AEGIS Forensics\AEGIS-FDX\` cleanly deleted.
   - Desktop shortcuts and Start Menu entries removed.
   - Registry entries cleaned up.
   - **Evidence Safety**: Forensic case data in `D:\ForensicCases\Matter-2026-Alpha` remains 100% untouched and intact.

---

## 3. Acceptance Sign-off Matrix

| Test Step | Expected Result | Acceptance Criteria |
|---|---|---|
| **Clean Machine** | No Java dependencies needed | Self-contained execution |
| **MSI Install** | Exit code 0, files registered | Standard WiX installer behavior |
| **App Launch** | Splash/Main screen < 2.5s | Direct3D hardware acceleration |
| **Case Creation** | `case.db` + `index/` created | Single authoritative SQLite database |
| **Ingestion** | Multi-type evidence extracted | Immutability + SHA-256 hashing |
| **Full-Text Search** | Sub-50ms query latency | Exact hit highlighting |
| **Retrospective Indexing** | Dynamic edge generation | Zero manual re-scans required |
| **7-Tab Dashboard** | Background asynchronous loading | FX Thread 60 FPS responsiveness |
| **I18n Language Switch** | 5-language switching | 0 translation drift in forensic core |
| **Reopen State** | Full state recovery | Crash resilience & WAL checkpoint |
| **Clean Uninstall** | Binaries removed, case data intact | Non-destructive forensic isolation |
