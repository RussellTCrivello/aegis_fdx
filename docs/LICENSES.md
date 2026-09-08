# Third-Party Licence Report (D-05)

Constraint: only Apache-2.0 / MIT / BSD / EPL. Verified against the dependency set
declared in `app/build.gradle.kts`.

| Component | Version | Licence | Use |
|---|---|---|---|
| OpenJDK (Temurin) | 21 LTS | GPL-2.0 **with Classpath Exception** | runtime; CPE permits distribution |
| OpenJFX / JavaFX | 21.0.4 | GPL-2.0 with Classpath Exception | UI toolkit |
| Apache Lucene (core, analysis, queryparser, highlighter) | 9.11.1 | Apache-2.0 | index & search |
| Apache Tika (core, standard parsers) | 3.0.0 | Apache-2.0 | detection & extraction |
| Apache PDFBox | 3.0.3 | Apache-2.0 | PDF text/metadata |
| Apache POI (poi-ooxml) | 5.3.0 | Apache-2.0 | DOC(X)/XLS(X)/PPT(X) |
| Eclipse Angus / Jakarta Mail | 2.0.3 | EPL-2.0 / GPL-2.0-CPE | EML/MIME |
| Apache mime4j (core, dom) | 0.8.11 | Apache-2.0 | MBOX/MIME |
| java-libpst | 0.9.3 | Apache-2.0 | PST/OST |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | ZIP/TAR/7Z/GZ/BZ2/XZ |
| junrar | 7.5.5 | UnRar/BSD-style **(review)** | RAR |
| XZ for Java | 1.10 | Public Domain (0BSD-equivalent) | XZ/LZMA |
| Tess4J | 5.13.0 | Apache-2.0 | JNA binding to Tesseract |
| Tesseract OCR | 5.x | Apache-2.0 | OCR engine (bundled binary) |
| SQLite JDBC (xerial) | 3.46.1.0 | Apache-2.0 | case database |
| SQLite | 3.x | Public Domain | embedded engine |
| SLF4J API | 2.0.16 | MIT | logging facade |
| Logback Classic | 1.5.8 | EPL-1.0 **or** LGPL-2.1 (dual) | logging — elect **EPL-1.0** |
| ONNX Runtime (Java) | 1.19.2 | MIT | local document classification |
| JUnit Jupiter | 5.11.0 | EPL-2.0 | tests (not distributed) |

## Notes and actions

1. **Logback** is dual-licensed. Elect **EPL-1.0** in `NOTICE` to stay inside the
   allowed set; no LGPL obligation is then incurred.
2. **junrar** carries the UnRar heritage clause: the code may not be used to
   re-create the RAR *compression* algorithm. Extraction-only use is compliant, but
   this is the one item that needs counsel sign-off before shipping. Fallback if
   rejected: drop RAR support, or shell out to a separately-installed `unrar`.
3. **GPL-2.0 with Classpath Exception** (OpenJDK, OpenJFX, Angus) does not impose
   copyleft on this application — the exception exists precisely for linking.
   `jpackage` bundles a trimmed runtime; ship the JDK licence text alongside.
4. **Tesseract language data** (`tessdata`) is Apache-2.0; `tessdata_best` models
   are also Apache-2.0. Confirm per-language before bundling beyond `eng`.
5. No component phones home. The build has no telemetry dependency (N-06).

## Generating the machine-readable report

```bash
./gradlew :app:generateLicenseReport   # CycloneDX SBOM + HTML, per release
```
