# Final Acceptance Result

**Generated:** 2026-09-08 12:28:07 UTC  
**Environment:** Linux 6.1.158+ x86_64 · 2 cores · 3 GB  
**Runtime:** openjdk version "25.0.2" 2026-01-20 LTS  
**Compiler:** Eclipse Compiler for Java(TM) v20260224-0835, 3.45.0, Copyright IBM Corp 2000, 2020. All rights reserved.  
**JavaFX:** javafx.base.jar javafx.controls.jar javafx.fxml.jar   
**Class:** DEVELOPMENT ENVIRONMENT

## Summary

| | Count |
|---|---:|
| Passed | **65** |
| Failed | **0** |
| Skipped / deferred | 5 |

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
| Total assertions | **PASS** | 1066 passed, 0 failed |
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
| Gradle build scripts compile | **SKIP** | gradle not installed in this environment |
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
| AI outside the processing pipeline | **PASS** | 9 pipeline packages reference no AI code |
| AI cannot trigger processing | **PASS** | no ingest, extraction, OCR or index API in ai/ |
| AI is manually invoked | **PASS** | Assistant screen and Analyze buttons only |
| AI boundary suite (B-01..B-08) | **PASS** | 48 checks passed, 0 failed |
| Architecture invariants | **PASS** | 13 structural rules hold |
| Coverage inventory | **PASS** | 68 rows classified, every symbol resolves |
| JUnit suites (facade, agent, batch, model) | **PASS** |  65 tests, 65 passed, 0 failed, 0 skipped (4.2 s)  |
| Interface suites | **PASS** |  30 tests, 29 passed, 0 failed, 0 skipped, 1 not runnable on this machine (5.6 s)  |
| AI does not load at startup | **PASS** | provider built on first invocation; the window holds no model class |
| Settings persist with the case | **PASS** | written to settings.properties and reloaded on open |
| Host meters are measured | **PASS** | CPU, memory and disk read from the OS and the filesystem |
| Unmeasured figures are declared | **PASS** | missing counters render as text, not as 0% |
| AI boundary documented | **PASS** | docs/AI_BOUNDARY.md states the rule and its evidence |
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
| Application image build | **SKIP** | no packaging toolchain here — missing: jar jlink jpackage |
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
| docs/ARCHITECTURE.md | **PASS** | 301 lines |
| Ingest throughput | **PASS** | 323470 items/h (target 8000) |
| Search p95 | **PASS** | 5.88 ms (target 2000) |
| UI responsiveness | **PASS** | 6.578 ms max (target 500) |
| Performance certification | **SKIP** | development environment — indicator only |

## Raw logs

```
total 72
drwxr-xr-x 2 user user    60 Sep  8 12:15 .
drwxr-xr-x 5 user user    60 Sep  8 12:28 ..
-rw-r--r-- 1 user user   294 Sep  8 12:28 package.log
-rw-r--r-- 1 user user 66492 Sep  8 12:28 tests.log
```
