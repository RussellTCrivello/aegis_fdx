# Dependency & Licence Report

**Status: FINAL for v1.0.0 — all components compliant, zero blocked.**

| Status | Count |
|---|---:|
| ✅ Clear (Apache-2.0 / MIT / BSD / EPL / Public Domain) | 33 |
| ⚠️ Conditional (documented election) | 2 |
| ⛔ Blocked | **0** |

`junrar` — the sole blocked component in earlier milestones — has been **removed
from the build**. `lib/` now contains **35 jars**. RAR archives are retained,
hashed, indexed and marked `Unsupported`, and the analyzer SPI remains open for a
future licence-compliant provider. See the Milestone 4 addendum at the end.

---

## Historical record — Milestone 2

Every jar below is **actually resolved and on the build classpath** (see
`tools/fetch-deps.sh`, 36 jars, 49 MB). Classification verified against each
project's published licence.

## Compliance summary

| Status | Count | Meaning |
|---|---|---|
| ✅ Clear | 33 | Apache-2.0 / MIT / BSD / EPL / Public Domain — approved set |
| ⚠️ Conditional | 2 | Dual-licensed or exception-bearing; election documented below |
| ⛔ Blocked | 1 | `junrar` — requires sign-off before release |

## Full inventory

| Component | Version | Licence | Class | Purpose |
|---|---|---|---|---|
| lucene-core | 9.11.1 | Apache-2.0 | ✅ | index/search kernel |
| lucene-analysis-common | 9.11.1 | Apache-2.0 | ✅ | analyzers |
| lucene-queryparser | 9.11.1 | Apache-2.0 | ✅ | query utilities |
| lucene-highlighter | 9.11.1 | Apache-2.0 | ✅ | hit fragments |
| lucene-memory | 9.11.1 | Apache-2.0 | ✅ | highlighter dep |
| lucene-queries | 9.11.1 | Apache-2.0 | ✅ | query types |
| lucene-sandbox | 9.11.1 | Apache-2.0 | ✅ | extra queries |
| tika-core | 3.0.0 | Apache-2.0 | ✅ | MIME detection |
| pdfbox / pdfbox-io / fontbox | 3.0.3 | Apache-2.0 | ✅ | PDF text + metadata |
| poi / poi-ooxml / poi-ooxml-lite / poi-scratchpad | 5.3.0 | Apache-2.0 | ✅ | Office + MSG |
| xmlbeans | 5.2.1 | Apache-2.0 | ✅ | OOXML binding |
| commons-collections4 | 4.4 | Apache-2.0 | ✅ | POI dep |
| commons-math3 | 3.6.1 | Apache-2.0 | ✅ | POI dep |
| SparseBitSet | 1.3 | Apache-2.0 | ✅ | POI dep |
| curvesapi | 1.08 | BSD-3-Clause | ✅ | POI dep |
| apache-mime4j-core / -dom | 0.8.11 | Apache-2.0 | ✅ | EML/MIME parsing |
| java-libpst | 0.9.3 | Apache-2.0 | ✅ | PST/OST |
| commons-compress | 1.27.1 | Apache-2.0 | ✅ | ZIP/7Z/TAR/GZ/BZ2/XZ |
| xz | 1.10 | Public Domain (0BSD) | ✅ | XZ codec |
| ~~junrar~~ | ~~7.5.5~~ | ~~UnRar restriction~~ | **REMOVED in v1** | RAR now `Unsupported` |
| sqlite-jdbc | 3.46.1.0 | Apache-2.0 | ✅ | case database |
| slf4j-api | 2.0.16 | MIT | ✅ | logging facade |
| logback-classic / -core | 1.5.8 | EPL-1.0 **or** LGPL-2.1 | ⚠️ | logging impl |
| commons-io | 2.16.1 | Apache-2.0 | ✅ | IO utilities |
| commons-codec | 1.17.1 | Apache-2.0 | ✅ | encoding |
| commons-lang3 | 3.17.0 | Apache-2.0 | ✅ | POI dep |
| commons-logging | 1.3.4 | Apache-2.0 | ✅ | POI dep |
| log4j-api / log4j-core | 2.24.1 | Apache-2.0 | ✅ | POI dep |
| OpenJDK (Temurin) | 21.0.12 | GPL-2.0 **+ Classpath Exception** | ⚠️ | runtime |
| OpenJFX | 21.0.4 | GPL-2.0 + Classpath Exception | ⚠️ | UI toolkit |

## Conditional items — resolutions

**Logback (dual EPL-1.0 / LGPL-2.1).** Elect **EPL-1.0** in `NOTICE`. No LGPL
obligation is incurred. Alternative if rejected: `slf4j-simple` (MIT).

**GPL-2.0 with Classpath Exception (OpenJDK, OpenJFX).** The exception explicitly
permits linking without copyleft propagation — it exists for this case. `jpackage`
bundles a trimmed runtime; ship the JDK licence text in the installer.

## ⛔ junrar — still blocked, as instructed

Tracked and unresolved. `junrar` inherits the **UnRar licence restriction**: the
code may not be used to reverse-engineer the RAR *compression* algorithm.

- Extraction-only use is the intended and commonly-accepted case.
- It is **not** an OSI-approved licence, so it falls outside the mandated
  Apache/MIT/BSD/EPL set as written.

**Recommendation: remove `junrar` from v1** unless counsel signs off. The code is
already isolated — `ArchiveAnalyzer.rar()` is the single call site, and deleting it
degrades RAR to `UNSUPPORTED` (stored, hashed, indexed, flagged) with no other
behaviour change.

| Option | Licence | Trade-off |
|---|---|---|
| Keep junrar | UnRar restriction | Needs written sign-off |
| **Drop RAR (recommended)** | — | RAR marked `Unsupported`; zero code risk |
| Shell out to system `unrar` | user-supplied | No bundled licence; needs external binary |
| 7-Zip JBinding | LGPL-2.1 | LGPL still outside the mandated set |

## Not yet integrated (deliberate)

| Component | Reason |
|---|---|
| Tess4J / Tesseract 5 | OCR requires a native binary; `needsOcr` flags are already set by the PDF and image analyzers so the stage can be inserted without touching the pipeline. Both Apache-2.0. |
| ONNX Runtime | **Removed.** It was declared for in-pipeline AI classification and never used. The AI boundary (`docs/AI_BOUNDARY.md`) keeps inference out of the processing engine, and the optional local agent uses a separately installed model runtime over loopback HTTP, so the application ships with no inference dependency. |

## Reproducing

```bash
./tools/fetch-deps.sh          # resolves the exact set above
./gradlew :app:generateLicenseReport   # CycloneDX SBOM per release
```

---

# Milestone 3 addendum

## Tesseract OCR — now integrated

| Component | Version | Licence | Class |
|---|---|---:|---|
| Tesseract OCR | 5.5.0 | Apache-2.0 | ✅ Clear |
| Leptonica (Tesseract dep) | 1.84.1 | BSD-2-Clause | ✅ Clear |
| tessdata `eng`, `deu` | — | Apache-2.0 | ✅ Clear |

**Integration is out-of-process, by deliberate design.** AEGIS drives the
`tesseract` executable rather than binding `libtesseract` via JNA:

1. **Fault isolation.** OCR on a malformed image can segfault. In-process that
   kills the JVM and destroys the whole evidence run, violating N-05. Out-of-process
   it is an exit code recorded against one element.
2. **Enforceable timeouts.** A native call cannot be interrupted; a child process
   can be destroyed. One pathological scan cannot stall ingestion.
3. **Licence hygiene.** Tesseract stays a redistributable external tool rather than
   linked code.

Cost: ~10–30 ms process spawn per image, negligible against a 0.5–3 s OCR pass.

No new Java dependencies were added for OCR. **Tess4J was evaluated and rejected** —
it is a JNA binding, which would forfeit all three benefits above.

## Licence position unchanged

Still exactly **one blocked component: `junrar`**. Per the v1 direction ("do not
include components with incompatible licensing restrictions"), the recommendation
stands: **drop RAR from v1**, mark it `Unsupported`, and keep the analyzer SPI so a
compliant RAR provider can be added later without touching the core engine. The
architecture already supports this — `ArchiveAnalyzer.rar()` is the single call site
and the `Analyzer` SPI is the extension point.


---

# Milestone 4 addendum — final licence position

## Resolution of the junrar decision

Per the v1 direction ("do not include components with incompatible licensing
restrictions"), **junrar has been removed**:

- Deleted from `lib/` (36 → 35 jars).
- Removed from `tools/fetch-deps.sh`, with a comment recording why.
- Imports and the extraction body deleted from `ArchiveAnalyzer`.

RAR archives are now **preserved, hashed, indexed and marked `Unsupported`**, with
metadata stating the reason and the remediation:

```
Unsupported : RAR extraction is not included in v1 for licence-compliance
              reasons; the archive is preserved and hashed but its contents
              are not expanded
Remediation : Extract externally and re-ingest, or install a licence-compliant
              RAR analyzer plugin
```

Verified on a real RAR fixture: `status=UNSUPPORTED`, SHA-256 present. Evidence is
never silently dropped.

**SPI compatibility preserved.** A compliant RAR provider can be added by
implementing `com.aegis.fdx.spi.Analyzer` and dropping a jar on the classpath — no
change to the core engine.

The installation verifier asserts junrar is absent, so a non-compliant build
cannot ship unnoticed:

```
PASS  no licence-blocked components bundled (junrar absent)
```

## Final inventory (35 jars)

| Group | Licence | Class |
|---|---|---|
| Lucene 9.11.1 (core, analysis-common, queryparser, highlighter, memory, queries, sandbox) | Apache-2.0 | ✅ |
| tika-core 3.0.0 | Apache-2.0 | ✅ |
| PDFBox / pdfbox-io / fontbox 3.0.3 | Apache-2.0 | ✅ |
| POI 5.3.0 (+ ooxml, ooxml-lite, scratchpad) | Apache-2.0 | ✅ |
| xmlbeans 5.2.1, commons-collections4 4.4, commons-math3 3.6.1, SparseBitSet 1.3 | Apache-2.0 | ✅ |
| curvesapi 1.08 | BSD-3-Clause | ✅ |
| mime4j core/dom 0.8.11 | Apache-2.0 | ✅ |
| java-libpst 0.9.3 | Apache-2.0 | ✅ |
| commons-compress 1.27.1 | Apache-2.0 | ✅ |
| xz 1.10 | Public Domain (0BSD) | ✅ |
| sqlite-jdbc 3.46.1.0 | Apache-2.0 | ✅ |
| slf4j-api 2.0.16 | MIT | ✅ |
| logback-classic/core 1.5.8 | EPL-1.0 (elected) | ⚠️ |
| commons-io 2.16.1, commons-codec 1.17.1, commons-lang3 3.17.0, commons-logging 1.3.4 | Apache-2.0 | ✅ |
| log4j-api/core 2.24.1 | Apache-2.0 | ✅ |
| OpenJDK 21 / OpenJFX 21 (bundled runtime) | GPL-2.0 + Classpath Exception | ⚠️ |
| Tesseract 5.5 + Leptonica (external, optional) | Apache-2.0 / BSD-2-Clause | ✅ |

### The two conditional items

**Logback** is dual EPL-1.0 / LGPL-2.1. We **elect EPL-1.0**, which is in the
approved set. No LGPL obligation is incurred. Recorded in `NOTICE`. Fallback if
ever rejected: `slf4j-simple` (MIT).

**OpenJDK / OpenJFX** are GPL-2.0 **with the Classpath Exception**, which exists
precisely to permit linking and distribution without copyleft propagation. jpackage
bundles a trimmed runtime; the JDK licence text ships in the installer.

Neither is a blocker; both are documented elections rather than unresolved risks.

## Attribution obligations

Ship in the installer (`packaging/LICENSE.txt`) and `docs/LICENSES.md`:

1. Apache-2.0 licence text + `NOTICE` for all Apache components.
2. BSD-3-Clause text for curvesapi; BSD-2-Clause for Leptonica.
3. MIT text for SLF4J.
4. EPL-1.0 text for Logback, with the election stated.
5. GPL-2.0 + Classpath Exception text for the bundled runtime.

No component in the final set requires source disclosure of AEGIS-FDX itself.

## Verification

```bash
./tools/fetch-deps.sh                    # resolves exactly 35 jars
packaging/verify-install.sh dist/AEGIS-FDX   # asserts junrar absent
```

For each release, generate a CycloneDX SBOM and re-run this review against it.
