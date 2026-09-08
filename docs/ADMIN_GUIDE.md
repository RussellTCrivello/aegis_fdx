# AEGIS-FDX — Administrator Guide

Version 1.0.0

---

## 1. Audience

For those deploying AEGIS-FDX, managing case storage, and maintaining defensibility
of the evidence-handling process.

---

## 2. Deployment models

| Model | Use | Notes |
|---|---|---|
| **Standalone workstation** | Default | Everything local. Fastest; no network exposure. |
| **Shared case storage** | Small teams | Cases on a fast SAN/NAS, app installed locally. One reviewer per case at a time. |
| **Air-gapped** | High-sensitivity | Fully supported — no network calls, no telemetry, no licence server. |

**Not supported in v1:** simultaneous multi-user access to one case. SQLite WAL
tolerates readers, but concurrent writers risk audit-trail interleaving. Assign one
reviewer per case, or split by custodian.

---

## 3. Storage planning

Budget approximately **3× the evidence volume**:

| Component | Typical size |
|---|---|
| Lucene index | ~15% of extracted text volume |
| Extracted text | ~10% of evidence volume |
| Preserved embedded elements | Varies with container density |
| SQLite database | ~2 KB per element |
| Exports | Up to 1× per production |

Put case folders on **local NVMe/SSD**. Network storage is the most common cause of
poor ingest performance.

---

## 4. Case folder layout

```
<case>/
  data/     preserved copies of embedded elements
  index/    Lucene index
  text/     extracted text — allows rebuilding the index
  db/       case.db (SQLite WAL): metadata, queue, audit log
  logs/     processing logs
  exports/  exports and reports
  case.json settings
```

Self-contained and portable — copy the folder to move or archive a case. Copy only
while no ingest is running.

### What to back up

Everything. If space is tight, `db/`, `text/` and `case.json` are the minimum: the
index is rebuildable from stored text, but metadata, tags, notes and the audit log
are not.

---

## 5. Settings that matter operationally

| Setting | Default | Guidance |
|---|---|---|
| Heap (`-Xmx`) | 4 GB | Raise to 8 GB+ above ~1M elements |
| Index memory | 4096 MB | Higher = faster indexing, more RAM |
| Worker threads | cores − 1 | Leave one core for the UI |
| Max archive depth | 20 | Lower only if archive bombs are a concern |
| Dedupe scope | Off | `Global` or `Per custodian` for large collections |
| OCR | On | Adds 0.5–3 s per scanned page |
| Case encryption | On (AES-256) | Leave on for sensitive matters |

Edit runtime options in the launcher config (`app/AEGIS-FDX.cfg` on Windows).

---

## 6. Evidence integrity — operating practice

This is the part that matters if the process is ever challenged.

### At intake
Record where evidence came from, who provided it, and when. Prefer read-only media
or shares. The application never writes to source evidence.

### After processing
Run **Verify**. It re-hashes every original against the value recorded at ingest and
reports anything modified, missing or unreadable. Run it:
- after the initial ingest,
- before any production,
- after moving evidence between volumes,
- whenever chain of custody is questioned.

Verify is **read-only and never repairs**. Any finding is investigated, not
corrected — silently "fixing" evidence destroys the provenance it exists to prove.

### Before producing
Check the export result: `written`, `hash-verified`, `hidden excluded`. Any
**hash mismatch means do not produce that set** — the source changed after ingest.

### The audit log
Append-only, in `db/case.db`, recording ingests, searches, tag changes, notes,
exports and verification runs with user and timestamp. Viewable in-app under the
element's **Audit** tab. Include it when documenting the process.

---

## 7. Exception handling

Every run produces a processing report listing exceptions. These are **expected** in
real evidence, not defects:

| Status | Meaning | Action |
|---|---|---|
| **Error** | Corrupt/truncated | Obtain a clean copy if material; otherwise document |
| **Locked** | Encrypted, no password matched | Add passwords in Settings, re-ingest |
| **Unsupported** | No analyzer (incl. RAR) | Convert or extract externally, re-ingest |

All three are retained, hashed and indexed. Nothing is dropped.

Add candidate passwords in Settings **before** ingesting encrypted collections;
password attempts happen during processing, not retroactively.

---

## 8. Recovery

**Crash or power loss during ingest:** reopen the case and start the same ingest
again. Completed work is recognised by source path and skipped; the result is
identical to an uninterrupted run. This is covered by the automated suite.

**Corrupt index:** the index is rebuildable from `text/`. Metadata, tags and notes
live in SQLite and are unaffected.

**Corrupt database:** restore from backup. This is why `db/` is the backup priority —
tags, notes and the audit log exist nowhere else.

---

## 9. Security

- **Fully offline.** No network connections, telemetry, or licence checks.
- **Case encryption** (AES-256) on by default.
- Restrict case-folder permissions to the review team; the audit log records who
  did what, but the OS controls who can reach the folder.
- Store evidence and cases on encrypted volumes.
- Exports are unencrypted by design — protect the destination.

---

## 10. Maintenance

| Task | Frequency |
|---|---|
| Back up case folders | Daily during active review |
| Run **Verify** | After ingest and before each production |
| Review processing exceptions | After each ingest |
| Archive closed cases | Per retention policy |
| Check disk headroom | Before large ingests |

Uninstalling never deletes case folders; remove them explicitly under your retention
policy.

---

## 11. Escalating a problem

Collect before raising an issue:

1. Version (Settings → About).
2. `logs/` from the case folder.
3. The processing report.
4. OS, core count, RAM, and case size in elements.
5. Whether the case folder is local or network storage.

**Never send evidence content.** Element ids, statuses and error text are enough to
diagnose; they contain no document content.
