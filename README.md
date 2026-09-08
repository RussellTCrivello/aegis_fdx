# File Analysis System

Cross-platform desktop application for reading, processing, extracting, indexing,
searching and organising files of any type — with a **fully local AI assistant**.

Java 21 · JavaFX 21 · Lucene 9.11 · SQLite · Tesseract. All source is original.
No cloud services; the application and its assistant run entirely offline.

![Dashboard](docs/screens-fas/01-dashboard.png)

---

## Status

```
82 automated tests            0 failures
266 battery assertions        0 failures
56 release-gate checks        0 failures · 3 environmental skips
33/33 audited destinations    33 figures rendered from the running application
145/148 functions complete    3 explained, none silently missing
```

Full detail, including honest limitations: **[VERIFICATION_REPORT.md](docs/VERIFICATION_REPORT.md)**

---

## What it does

**Processing** — reads files of many types including archives and mailboxes,
recursively; extracts text and metadata through a pluggable analyzer interface; OCRs
images and scanned documents; hashes with MD5 and SHA-256; indexes into Lucene. Source
material is opened read-only, no file is silently dropped, duplicates are marked rather
than deleted, and a crash resumes from a durable queue.

**Organisation** — Sources, Aspects, Categories, Keywords and Contents, all first-class
and related to processed material by foreign key inside the case database.

**Search** — phrases, wildcards, fuzzy, proximity, boolean expressions, field-scoped
terms and regular expressions, with filtering by type, source, aspect and date.

**Assistant** — a local AI agent that investigates the case in multiple steps by
calling controlled application tools, and cites the records behind every answer.

---

## Quick start

```bash
./run-prototype.sh                 # run from source
./gradlew :app:run                 # File Analysis System interface
./gradlew :app:run --args=--forensic   # original review interface

./run-tests.sh                     # functional battery
./gradlew :app:test                # unit, integration and AI tests
./final-acceptance.sh              # release gate → docs/ACCEPTANCE-RESULT.md
```

### Enabling the assistant

Optional. Without it, everything else works and the Assistant screen explains what to
install.

```bash
ollama pull qwen2.5:7b-instruct    # or any local model you prefer
./gradlew :app:run
```

```bash
java -Daegis.ai.model=qwen2.5:14b-instruct -jar app.jar   # stronger model
java -Daegis.ai.enabled=false -jar app.jar                # turn it off
```

A non-loopback AI endpoint is refused at construction: the agent is local-only by
design. See **[AI_AGENT.md](docs/AI_AGENT.md)**.

---

## Interface

Twenty-five navigable destinations plus seven reached by drilling through:

| Group | Destinations |
|---|---|
| Overview | Dashboard · Analysis · Charts · Comprehensive · Path Analysis · Batch Analysis · Search · Advanced Search |
| Entities | Sources · Aspects · Email Words · Keywords · Words · Categories |
| Files | Upload Files · File Library · Archives · Import / Export |
| Assistant | Assistant |
| System | Saved Searches · Notifications · Processing · Errors · Performance · Setup · Settings |
| Drill-through | Source Detail · Aspect Detail · Source/Aspect Relationships · File Detail · Full Content · Word Detail · Keyword Detail |

The destination list comes from a full audit of the reference project:
**[REFERENCE_AUDIT.md](docs/REFERENCE_AUDIT.md)**

Walkthrough with figures: **[UI_GUIDE.md](docs/UI_GUIDE.md)**

---

## Architecture

```
  JavaFX interface  ──►  Java facades  ──►  existing engine  ──►  case.db
                             ▲
                             │
                   local AI agent (tool gateway)
```

One database. The five added concepts live in `case.db` alongside the engine's own
tables, sharing its connection and transaction scope, with `path.element_id` a real
foreign key onto `item(id)`.

The agent is an operator layer, not a second implementation: it reads the same data
through the same facades a human's clicks would, and has no shell, SQL, filesystem or
network access.

Processing never calls it. The ingest path — read, extract, metadata, OCR, hash, index,
store — contains no AI, and the application runs normally with the assistant disabled,
unavailable or removed. The assistant runs only when you click an AI action, is
read-only by default, and changes nothing by inspecting it. The rule and its mechanical
enforcement: **[AI_BOUNDARY.md](docs/AI_BOUNDARY.md)**

Diagrams: **[DIAGRAMS.md](docs/DIAGRAMS.md)**

---

## Documentation

| Document | Contents |
|---|---|
| [UI_GUIDE](docs/UI_GUIDE.md) | Every screen, with rendered figures |
| [AI_AGENT](docs/AI_AGENT.md) | Local model, agent loop, tools, safety, offline operation |
| [AI_BOUNDARY](docs/AI_BOUNDARY.md) | The normative rule: AI is optional, manually invoked and read-only |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Layering and design decisions |
| [DIAGRAMS](docs/DIAGRAMS.md) | Architecture, ERD, navigation, data flow, sequences |
| [INTERFACE_INVENTORY](docs/INTERFACE_INVENTORY.md) | Screen → facade → backend → test |
| [REFERENCE_AUDIT](docs/REFERENCE_AUDIT.md) | Destination-by-destination audit and gap closure |
| [FUNCTION_INVENTORY](docs/FUNCTION_INVENTORY.md) | Function-level audit and discrepancy report |
| [VERIFICATION_REPORT](docs/VERIFICATION_REPORT.md) | Final verification and limitations |
| [USER_MANUAL](docs/USER_MANUAL.md) | Task-oriented guide |
| [BUILD](docs/BUILD.md) | Building, running, regenerating screenshots |
| [PERFORMANCE](docs/PERFORMANCE.md) | Benchmark methodology and figures |
| [FORMATS](docs/FORMATS.md) | Supported format matrix |
| [DEPENDENCY_REPORT](docs/DEPENDENCY_REPORT.md) | Third-party licences |
| [PRESENTATION](docs/PRESENTATION.md) | Demonstration material |

---

## Licence

Original source. Third-party dependencies are Apache-2.0, MIT, BSD or EPL; see the
dependency report for the full inventory.
