# AEGIS-FDX — Build Instructions

Version 1.0.0

---



## Running with the local AI assistant

The assistant is optional. Without a local runtime the application works normally and
the Assistant screen explains what to install.

```bash
# 1. install a local inference runtime that serves an HTTP API on loopback
# 2. pull a model
ollama pull qwen2.5:7b-instruct
# 3. start the runtime, then launch the application
./gradlew :app:run
```

Configuration — all optional, all system properties:

| Property | Default |
|---|---|
| `aegis.ai.enabled` | `true` |
| `aegis.ai.endpoint` | `http://127.0.0.1:11434` (must be loopback) |
| `aegis.ai.model` | `qwen2.5:7b-instruct` |
| `aegis.ai.embedModel` | `nomic-embed-text` |
| `aegis.ai.timeoutSeconds` | `120` |
| `aegis.ai.contextTokens` | `8192` |

```bash
# a stronger local model
java -Daegis.ai.model=qwen2.5:14b-instruct -jar app.jar
# a runtime on another port
java -Daegis.ai.endpoint=http://127.0.0.1:8080 -jar app.jar
# disable the assistant
java -Daegis.ai.enabled=false -jar app.jar
```

A non-loopback endpoint is refused at construction: the agent is local-only by design.

Needs roughly 6 GB of free RAM for the default 7B model. Details and alternatives in
`AI_AGENT.md`.

### Running the AI tests

They use a scripted loopback runtime and need no model installed:

```bash
./gradlew :app:test --tests '*AiAgentTest'
```


## Running the two interfaces

The project ships two front ends over the same engine.

| Interface | Main class | Command |
|---|---|---|
| File Analysis System (default) | `com.aegis.fdx.ui.FasApp` | `./gradlew :app:run` |
| Review interface | `com.aegis.fdx.ui.AegisApp` | `./gradlew :app:run --args="--forensic"` |

Manual launch (no Gradle), from the project root:

```bash
JDK=$HOME/.cache/tools/jdk21/bin
FX=$HOME/.cache/tools/javafx-sdk-21.0.4/lib
CP=$(ls lib/*.jar | tr '\n' ':')

# compile, and copy the stylesheet onto the classpath
$JDK/javac -nowarn --module-path $FX --add-modules javafx.controls,javafx.swing \
    -cp "$CP" -d build/classes $(find app/src/main/java -name '*.java')
cp -r app/src/main/resources/* build/classes/

$JDK/java --module-path $FX --add-modules javafx.controls,javafx.swing \
    -cp "build/classes:$CP" com.aegis.fdx.ui.FasApp
```

**Do not forget the resource copy.** `fas.css` lives in
`app/src/main/resources/com/aegis/fdx/ui/`. If it is missing from the classpath the
window still opens but renders unstyled — this was a real defect caught during
development, and it looks like a layout bug rather than a missing file.

### Regenerating the interface screenshots

```bash
# populate a workspace using the real ingest pipeline
$JDK/java -cp "/tmp/shot:build/classes:$CP" FasSeedHarness

# drive the real app headlessly (needs openjfx-monocle on the graphics module path)
$JDK/java -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
    --module-path $FX --add-modules javafx.controls,javafx.swing \
    --patch-module javafx.graphics=/path/to/openjfx-monocle-21.0.2.jar \
    -cp "/tmp/shot:build/classes:$CP" FasShotHarness docs/screens-fas
```

The JavaFX SDK does **not** bundle Monocle; fetch
`org.testfx:openjfx-monocle:21.0.2` for headless rendering.


## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | Temurin recommended. Supplies `javac`, `jlink`, `jpackage`. |
| JavaFX SDK | 21.0.4 | Gluon distribution. |
| WiX Toolset | v3.11+ | Windows MSI only. |
| Tesseract | 5.x | Optional, for OCR tests. |

No network access is needed after dependencies are fetched — `lib/` holds all 35
jars and can be committed for reproducible offline builds.

```bash
export AEGIS_JDK=/path/to/jdk-21/bin
export AEGIS_FX=/path/to/javafx-sdk-21.0.4/lib
```

---

## 2. Build and test in one command

```bash
./run-tests.sh
```

Compiles main and test sources, generates the dataset, and runs every suite:

```
33 passed, 0 failed          query parser
=== 56 passed, 0 failed ===  pipeline acceptance AT-01..AT-10
=== 90 passed, 0 failed ===  M3 acceptance (OCR / export / reports / integrity)
N-02 PASS · F-18 PASS · N-03 PASS
```

**179 assertions total.** Pass a corpus multiplier to scale the benchmark:
`./run-tests.sh 2000`.

---

## 3. Fetching dependencies

```bash
./tools/fetch-deps.sh        # → lib/, 35 jars, ~48 MB
```

Pulls exact pinned versions from Maven Central. `junrar` is deliberately excluded —
see `docs/DEPENDENCY_REPORT.md`.

---

## 4. Building installers

```bash
packaging/build-installer.sh                    # native default for this OS
packaging/build-installer.sh --type msi         # Windows
packaging/build-installer.sh --type dmg         # macOS
packaging/build-installer.sh --type deb         # Linux
packaging/build-installer.sh --type app-image   # unpacked, no installer
packaging/build-installer.sh --type msi --sign  # signed release
```

Steps performed: compile → application jar → `jlink` trimmed runtime → `jpackage`
native package → optional signing.

Output lands in `dist/`. Installers must be built **on** the target platform;
jpackage does not cross-compile.

Then always:

```bash
packaging/verify-install.sh dist/AEGIS-FDX      # 20 checks incl. a live smoke test
```

---

## 5. Running from source

```bash
./run-prototype.sh
```

Or manually:

```bash
CP=$(ls lib/*.jar | tr '\n' ':')
$AEGIS_JDK/java --module-path $AEGIS_FX --add-modules javafx.controls \
    -cp "build/classes:$CP" com.aegis.fdx.ui.AegisApp
```

Useful properties: `-Daegis.caseDir=...`, `-Daegis.tesseract=...`,
`-Daegis.tessdata=...`.

> `AegisApp` is the development entry point. Installers use `com.aegis.fdx.Launcher`,
> which does **not** extend `Application` — required when JavaFX is on the classpath
> rather than the module path, otherwise the JVM refuses to start with "JavaFX
> runtime components are missing".

---

## 6. Project layout

```
app/src/main/java/com/aegis/fdx/
  Launcher.java      release entry point
  model/             Item, ItemStatus, Tag
  spi/               Analyzer, AnalyzerRegistry  (extension point)
  analyzers/         PDF, Office, EML, MSG, PST, MBOX, archive, text, image
  index/             LuceneIndex, LuceneQueryBuilder, StoredTextRegexQuery
  store/             CaseFolder, CaseDatabase
  engine/            IngestPipeline, LiveCase, IntegrityVerifier, QueryParser
  ocr/               OcrEngine, OcrStage
  export/            Exporter, ExportRequest, Reports
  ui/                AegisApp, UiParts
app/src/test/java/com/aegis/fdx/
  QueryParserTest, TestDataset, PipelineAcceptanceTest,
  M3AcceptanceTest, Benchmark
packaging/           build-installer.sh, verify-install.sh
tools/               fetch-deps.sh, ShotHarness.java
lib/                 35 dependency jars
docs/                manuals, format matrix, licence and performance reports
```

---

## 7. Adding a format analyzer

Implement the SPI — no core changes:

```java
public final class DwgAnalyzer implements Analyzer {
    public String id() { return "dwg"; }

    public double claim(byte[] header, String fileName) {
        return Magic.extIn(fileName, "dwg") ? 0.8 : 0;
    }

    public void analyze(Item item, InputStream in, ChildSink sink) throws Exception {
        item.extractedText(extractText(in));
        item.addMetadata("CAD-Version", version);
        // sink.emit(child, bytes) for embedded elements
    }
}
```

Register in `IngestPipeline.defaultRegistry(...)`, or ship it as a
`META-INF/services/com.aegis.fdx.spi.Analyzer` provider and drop the jar on the
classpath. Highest `claim()` wins; header bytes are matched before extensions.

---

## 8. Continuous integration

```yaml
name: build
on: [push, pull_request]
jobs:
  test:
    strategy:
      matrix:
        os: [windows-latest, ubuntu-latest, macos-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: ./tools/fetch-deps.sh
      - run: ./run-tests.sh
      - run: packaging/build-installer.sh --type app-image
      - run: packaging/verify-install.sh dist/AEGIS-FDX
      - uses: actions/upload-artifact@v4
        with: { name: installer-${{ matrix.os }}, path: dist/ }
```

The suites exit non-zero on failure, so CI fails correctly.

---

## 9. Test dataset

`TestDataset.java` generates 44 files covering every supported format plus
deliberate edge cases:

| Fixture | Purpose |
|---|---|
| `corrupt.pdf`, `corrupt.docx`, `truncated.zip` | Error handling (N-05) |
| `encrypted.zip` | `Locked` status (F-04) |
| `nested_evidence.zip` | 4-level nesting (F-03) |
| `dupe_1/2/3.txt` across custodians | Dedup (F-05) |
| `scanned_memo.png`, `scanned_contract.pdf` | OCR canaries — text exists **only** as pixels |
| `archive.rar` | Licence-driven `Unsupported` path |
| `arabic.txt`, `cjk.txt`, `cyrillic.txt` | Unicode |
| `interview.mp3`, `diagram.dwg` | Metadata-only / unsupported |

Canary constants (`INV-88213`, `OCR-CANARY-IMAGE`, …) let assertions prove content
was genuinely extracted rather than merely counted.

---

## 10. Troubleshooting the build

| Symptom | Fix |
|---|---|
| `package javafx.* does not exist` | Set `AEGIS_FX` to the JavaFX SDK `lib`. |
| `jpackage: command not found` | Use JDK 21+, not a JRE. |
| MSI build fails on Windows | Install WiX v3 and put `candle.exe`/`light.exe` on `PATH`. |
| "Application destination directory already exists" | Handled by the script; if hit manually, `rm -rf dist/AEGIS-FDX`. |
| OCR tests skip | Install Tesseract or set `AEGIS_OCR_ROOT`. Suite reports honestly either way. |
| `ClassNotFoundException: MonoclePlatformFactory` | Headless screenshots need the `openjfx-monocle` jar patched into `javafx.graphics`. |

## Gradle build on Windows — known issues and fixes

Three failures were reported from a Windows host running Gradle 9.3. All three are
fixed; this section records the causes so they are not reintroduced.

### `:app:test` — "did not discover any tests to execute"

The suite harnesses (`QueryParserTest`, `PipelineAcceptanceTest`, `M3AcceptanceTest`,
`DragDropIngestTest`, `WindowsCompatibilityTest`) are **`main()`-based**, not annotated
JUnit classes. That is deliberate: `run-tests.sh` and `final-acceptance.sh` must run them
without a test framework, and they print their own evidence.

Gradle resolves `:app:test` against the JUnit platform, found nothing to run, and failed
the build. **The harnesses were compiled but never executed** — a green Gradle build would
have proven nothing.

Fixed by `app/src/test/java/com/aegis/fdx/SuiteBridgeTest.java`, a thin JUnit bridge that
invokes each harness in-process, mirrors its output to the build log, and asserts on the
`N passed, M failed` footer. Both entry points now run the same suites:

```bat
gradlew :app:test          REM JUnit bridge
bash run-tests.sh 2        REM shell runner
```

> Footer formats differ between harnesses — most print `=== N passed, M failed ===`,
> `QueryParserTest` prints a bare `N passed, M failed`. The bridge accepts both. It also
> fails if a suite produces **no** footer at all, so a harness that silently stops running
> cannot pass as green.

### `:app:jpackage` — `ResolvedConfiguration.getFiles()`

`org.beryx.jlink` **3.0.1** calls `ResolvedConfiguration.getFiles()`, which **Gradle 9
removed**. `:app:prepareMergedJarsDir` fails immediately.

Fixed by upgrading to **3.1.1**. If a future Gradle release breaks the plugin again,
`packaging/build-installer.sh` is the supported fallback and does not depend on it:

```bat
gradlew :app:jpackage

REM The .sh installer scripts require Git Bash or WSL:
REM   bash packaging/build-installer.sh --type msi
REM   bash packaging/build-installer.sh --type msi --per-user
REM   bash packaging/verify-install.sh "C:\Program Files\AEGIS-FDX"
```

### `mainClass` regression — `com.aegis.fdx.ui.AegisApp`

`app/build.gradle.kts` pointed `mainClass` at `ui.AegisApp`, a
`javafx.application.Application` subclass. On a **classpath** (non-modular) launch that
fails with `Error: JavaFX runtime components are missing`.

This bug was found and fixed once already during packaging; the Gradle build had it again.
`mainClass` must be **`com.aegis.fdx.Launcher`** — a plain class that delegates to
`AegisApp.main`. A comment in the build file now records why.

### Not a defect: `:app:run` sitting at "75% EXECUTING"

```
> Task :app:run
<=========----> 75% EXECUTING [22s]
```

This is the GUI application running normally. Gradle reports the task as executing for as
long as the app is open. Close the window and the task completes.

### Compiler warning

`DragDropIngestTest` accumulated `long processed()` into an `int`
(`[lossy-conversions]`). The accumulator is now `long`. The test tree compiles clean under
`-Xlint:all`.

### `cannot find symbol` on a method that exists in the repo

Reported from Windows:

```
QueryValidationTest.java:104: error: cannot find symbol
        var bad = LuceneQueryBuilder.compile("bogusfield:x", analyzer);
  symbol:   method compile(String,Analyzer)
```

**This is not a code defect.** `LuceneQueryBuilder.compile(String, Analyzer)` and
`QuerySyntaxException` are present in the repository, and a clean-tree build
(`rm -rf build` then full compile) succeeds with both `compileJava` and
`compileTestJava` at exit 0.

**Cause: a partial checkout.** The query-validation fix touched four files that must
travel together:

| File | Role |
|---|---|
| `index/LuceneQueryBuilder.java` | adds `compile()` and `Result` |
| `index/QuerySyntaxException.java` | **new file** |
| `ui/AegisApp.java` | renders rejected queries inline |
| `test/.../QueryValidationTest.java` | exercises all of the above |

The Windows host had the **test** file but not the **main** sources it calls. `javac`
can only report that as a missing symbol, which points at the wrong file entirely.

**Fix — re-sync the whole tree, do not copy individual files:**

```bat
git fetch --all
git reset --hard origin/main
git clean -fd
gradlew --stop
gradlew clean :app:test
```

`gradlew --stop` matters on Windows: a running daemon can hold stale compiled classes and
file locks from the previous build.

**Prevention.** `packaging/verify-sources.sh` checks the tree against
`packaging/SOURCE-MANIFEST.txt` (SHA-256 per source file) and reports a plain diagnosis:

```
  46 matched · 0 changed · 1 missing · 0 untracked

RESULT: INCOMPLETE CHECKOUT.
Files the build needs are absent. This is the usual cause of a
'cannot find symbol' error on a method that plainly exists in the repo.
Re-sync the whole tree — do not copy individual files.
```

It runs automatically before `compileJava` via the `verifySources` Gradle task, and is a
release-gate check. After deliberately editing sources, refresh the manifest with
`powershell -ExecutionPolicy Bypass -File packaging\make-manifest.ps1`
(or `bash packaging/make-manifest.sh` on Linux / Git Bash).

## Windows hosts without bash

Stock Windows PowerShell has no `bash`, so every `.sh` script in this repo is
unusable there:

```
bash : The term 'bash' is not recognized as the name of a cmdlet...
```

PowerShell equivalents are provided and require only a JDK 21 and a JavaFX 21 SDK:

| Task | PowerShell | Shell equivalent |
|---|---|---|
| Verify the checkout | `powershell -ExecutionPolicy Bypass -File packaging\verify-sources.ps1` | `packaging/verify-sources.sh` |
| Refresh the manifest | `powershell -ExecutionPolicy Bypass -File packaging\make-manifest.ps1` | `packaging/make-manifest.sh` |
| Run the full battery | `powershell -ExecutionPolicy Bypass -File run-tests.ps1` | `run-tests.sh` |
| Build the MSI | `gradlew :app:jpackage` | `packaging/build-installer.sh --type msi` |

Set the toolchain first if they are not on `PATH`:

```bat
set AEGIS_JDK=C:\Program Files\Eclipse Adoptium\jdk-21\bin
set AEGIS_FX=C:\javafx-sdk-21.0.4\lib
```

Still needing bash (install **Git for Windows** and use Git Bash, or WSL):
`packaging/verify-install.sh` and `final-acceptance.sh`. The Gradle path covers
building and testing without them.
