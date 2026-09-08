# Final Acceptance Result

**Generated:** 2026-09-08 10:10:29 UTC  
**Environment:** Linux 6.1.158+ x86_64 · 2 cores · 1 GB  
**Class:** DEVELOPMENT ENVIRONMENT

## Summary

| | Count |
|---|---:|
| Passed | **56** |
| Failed | **0** |
| Skipped / deferred | 3 |

**All executed checks passed.**

> **Performance figures are development indicators, not certified.**
> Re-run on 8-core / 16 GB / NVMe hardware to certify. See
> `docs/PERFORMANCE.md` §5 for the procedure.

## Detail

| Check | Result | Detail |
|---|---|---|
| Query parser (M1) | **PASS** | 33 passed, 0 failed |
| Pipeline acceptance AT-01..AT-10 (M2) | **PASS** |  56 passed, 0 failed  |
| M3 acceptance (OCR/export/reports/integrity) | **PASS** |  80 passed, 0 failed  |
| Total assertions | **PASS** | 266 passed, 0 failed |
| AT-01 mixed-format ingest | **PASS** | in pipeline suite |
| Corrupted file handling | **PASS** | errors contained, run continued |
| Encrypted archive handling | **PASS** | marked Locked, run continued |
| Nested container validation | **PASS** | multi-level extraction |
| Duplicate validation | **PASS** | SHA-256 grouping, nothing deleted |
| Resume validation | **PASS** | recovered case == uninterrupted run |
| Export validation | **PASS** | native/EML/PDF/CSV + manifest |
| Hash verification | **PASS** | verified post-write; tamper detected |
| Windows compatibility (N-01) | **PASS** | 18 checks: reserved names, trailing dots, MAX_PATH, CRLF/BOM |
| F-01 drag-and-drop import | **PASS** | handlers wired + 10 intake checks (file/folder/container/multi-select) |
| Gradle build scripts compile | **PASS** | build.gradle.kts evaluates cleanly |
| Source tree complete | **PASS** | matches packaging/SOURCE-MANIFEST.txt |
| UI stylesheet present | **PASS** | fas.css with all reference design tokens |
| UI screens complete | **PASS** | 33 destination classes, one per audited destination |
| Integrated schema complete | **PASS** | source/aspect/word/category/keyword/hash/path/content/alert |
| Single case database | **PASS** | integrated tables share the engine connection |
| Registry linked to engine | **PASS** | path.element_id is a foreign key onto item(id) |
| Engine schema intact | **PASS** | item/queue/audit still owned by CaseDatabase |
| Java-native interface layer | **PASS** | facades documented as Java interfaces |
| AI is local-only | **PASS** | no cloud AI endpoint referenced in the agent |
| AI refuses remote endpoints | **PASS** | non-loopback endpoints are rejected at construction |
| AI has no escape hatch | **PASS** | agent reaches the application only via facades |
| AI model is replaceable | **PASS** | runtime and model selected by configuration |
| AI write actions gated | **PASS** | data-changing tools require confirmation |
| Drill-through navigation | **PASS** | Router wired into the shell |
| Native charts | **PASS** | canvas-drawn donut and bar charts |
| Analytics aggregates | **PASS** | statistics, relationships, trees and error report |
| Records are editable | **PASS** | source and aspect edit persist |
| Batch analysis is distinct | **PASS** | own destination with persisted run history |
| Keyword hits are computed | **PASS** | produced by BatchAnalysisFacade over real text |
| Contextual AI actions | **PASS** | 7 destinations expose Analyze |
| Full CRUD on entities | **PASS** | source, aspect, keyword and word all editable |
| Gradle mainClass is Launcher | **PASS** | classpath-safe entry point |
| Gradle test discovery bridge | **PASS** | main() harnesses bridged into JUnit |
| Query validation | **PASS** | 69 checks: unknown fields, dates, fuzzy, wildcards, regex |
| OCR validation | **SKIP** | Tesseract not installed in this environment |
| Application image build | **PASS** | dist/AEGIS-FDX |
| Installation validation | **PASS** |  20 passed, 0 failed  |
| Packaged engine smoke test | **PASS** | ingest+index+search on bundled runtime |
| Windows MSI installer | **SKIP** | requires a Windows host (N-01 primary target) |
| Dependency inventory | **PASS** | 43 jars |
| No licence-blocked components | **PASS** | junrar removed; RAR marked Unsupported |
| Licence report | **PASS** | docs/DEPENDENCY_REPORT.md |
| docs/USER_MANUAL.md | **PASS** | 376 lines |
| docs/ADMIN_GUIDE.md | **PASS** | 184 lines |
| docs/INSTALL.md | **PASS** | 191 lines |
| docs/BUILD.md | **PASS** | 470 lines |
| docs/FORMATS.md | **PASS** | 134 lines |
| docs/PERFORMANCE.md | **PASS** | 170 lines |
| docs/DEPENDENCY_REPORT.md | **PASS** | 240 lines |
| docs/ARCHITECTURE.md | **PASS** | 260 lines |
| Ingest throughput | **PASS** | 401153 items/h (target 8000) |
| Search p95 | **PASS** | 4.98 ms (target 2000) |
| UI responsiveness | **PASS** | 3.711 ms max (target 500) |
| Performance certification | **SKIP** | development environment — indicator only |

## Raw logs

```
total 32
drwxr-xr-x 2 user user   128 Sep  8 10:07 .
drwxr-xr-x 8 user user   128 Sep  8 10:10 ..
-rw-r--r-- 1 user user   675 Sep  8 10:11 package.log
-rw-r--r-- 1 user user 22944 Sep  8 10:10 tests.log
-rw-r--r-- 1 user user  1026 Sep  8 10:11 verify.log
```
