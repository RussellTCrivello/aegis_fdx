# The AI Boundary

Version 1.0 · 2026-09-08

This document is the **normative architectural rule** for AI in this application. Where
any other document, comment or future change conflicts with it, this document wins.

---

## 1. The rule

> **AI is an optional intelligent analysis layer over the completed Java application,
> not a component of the application's ingestion, processing, extraction or storage
> engine.**

* The application must remain fully operational without AI.
* The AI must remain fully local when enabled.
* The AI must be manually invoked.
* The AI must use controlled Java-native tools.
* The AI must reason over existing application data.
* The AI must provide evidence-grounded results.
* The AI must not silently modify, or participate in, normal evidence processing.

---

## 2. The two pipelines

The normal application pipeline contains no AI at any point:

```text
Source → Reading → Processing → Extraction → Metadata → OCR → Content
       → Hashing → Indexing → case.db / Lucene
```

The agent is a separate, operator-initiated path that only reads what that pipeline
already produced:

```text
User → explicit AI action → Java AI interface → AgentService → AgentOrchestrator
     → LocalModelProvider → controlled Java tool gateway → existing Java facades
     → existing database / index / processed data → evidence → agent → user
```

**There is no arrow from the processing pipeline into the agent.** Not a hook, not a
callback, not an optional flag. The pipeline packages do not reference the agent's code
at all, which is checked mechanically (§6).

## 3. What the AI does not do

The AI does **not** automatically read files, extract files, OCR files, classify files,
generate metadata, generate keywords, generate categories, modify contents, modify
sources, modify aspects, modify the database, modify the search index, alter evidence,
trigger processing, replace existing processing, or run during ingestion.

It does none of those unless the operator explicitly invokes an AI operation **and**
that operation is specifically designed to perform the action.

The distinction is:

| This | Not this |
|---|---|
| "Ask the application an intelligent question." | "The application uses AI to process everything." |

These pipelines must not be created:

```text
File → AI → extraction        File → AI → metadata      File → AI → categorization
File → AI → keyword generation                          File → AI → storage
```

The architecture stays:

```text
File → Java processing engine → database/index          (ingest)
User → AI → inspect/analyse existing application data   (analysis)
```

## 4. Invocation

AI runs only from an explicit operator action: **Ask Agent**, **Analyze This**,
**Analyze Selection**, **Analyze Search Results**, **Summarize**, **Find Related
Records**, **Compare**, **Investigate**. In this application those are the Assistant
destination and the contextual *Analyze …* buttons on Source, Aspect, Category,
Keyword, Content, Item, File, Search Results and Analysis screens.

The current record or selection becomes the agent's **context** — an identifier the
tools can resolve, so the operator need not paste ids. Carrying context is not
analysis: opening a screen never asks the agent anything. The operator must click.

No automatic invocation occurs during ingestion or processing.

## 5. Read-only by default

The agent has read-only access by default. It has no arbitrary SQL, shell, filesystem,
PowerShell or unrestricted network access, and reaches application functionality only
through explicitly registered Java tools.

A normal analysis request must never mutate original files, extracted evidence,
metadata, database records or the search index.

Two write operations exist, and they are separate, explicitly defined operations that
require confirmation before the tools are even registered:

| Operation | What it changes | What it cannot change |
|---|---|---|
| `classify_file` | attaches a category to a registry row | the file, its bytes, its hashes, its text |
| `set_review_state` | marks a registry row Read/Unread | anything else |

Confirmation is obtained in the interface before the session may use them, and each
tool re-checks the permission itself when called. Any future write must likewise be a
separate, explicitly defined, explicitly confirmed operation.

## 6. How the rule is enforced

Enforcement is mechanical, because a rule nobody measures decays. The suite
`app/src/test/java/com/aegis/fdx/AiBoundaryTest.java` runs inside the functional
battery (`./run-tests.sh`), inside `./gradlew :app:test` via the suite bridge, and on
the release gate (`./final-acceptance.sh`).

| ID | Check | Method |
|---|---|---|
| B-01 | The pipeline never references the agent | Source scan of `engine`, `analyzers`, `ocr`, `index`, `store`, `spi`, `model`, `export`, `facade` and `Launcher`, plus a constant-pool scan of the compiled classes, for `com.aegis.fdx.ai`. Only `ui/` may reference the agent. |
| B-02 | The agent reaches the application only through tools | `ai/` contains no ingest, extraction, OCR or index API; no shell, `ProcessBuilder`, raw JDBC, file writing or classloading; `ai/agent` and `ai/tools` contain no networking at all; only `ai/model` speaks HTTP, and only to loopback. Tool inventory asserted read-only, with no general-purpose capability. |
| B-03 | Invocation is manual | `ask()` is reachable only from `AgentScreen` and `AnalyzeAction`; both fire from a button; `onShow()` runs nothing; no screen calls `AnalyzeAction.run` itself; enabling writes requires a confirmation that reverts when declined. |
| B-04 | The application works with no agent | With `aegis.ai.enabled=false`, with nothing listening, and with a misconfigured remote endpoint: the service still constructs (in milliseconds), reports why it is unavailable, declines cleanly instead of throwing — and search, database and processing statistics all still work. |
| B-05 | Ingestion makes no model call | With a reachable runtime present, a full acquire → extract → hash → index → register → batch-analyse → search → report cycle is run: the runtime receives **zero** chat requests and the agent records **zero** runs. One operator question then produces exactly one recorded run. |
| B-06 | A question changes nothing | A scripted model attempts `classify_file` and `set_review_state` during an ordinary question. Both are refused. A fingerprint of every item (hashes, status, text length, tags, notes), every registry row (review state, category and keyword links), the vocabulary and the case counts is identical afterwards, as are the original files, the stored copies, the extracted text and the Lucene segments. |
| B-07 | Writes are separate, confirmed and evidence-safe | Write tools are absent from a read-only context and present only after `allowingMutations()`; no contextual builder can escalate its own permission; the system prompt declares read-only mode; a write called without confirmation is refused. A *confirmed* write is then performed and the B-06 fingerprint is required to move — otherwise "nothing changed" would prove nothing — while original material and extracted text stay byte-identical. |
| B-08 | Nothing AI-related loads at startup | With an enabled, reachable local runtime configured, `AgentService.fromEnvironment` — exactly what the main window calls while it is being built — constructs no provider (`isModelLoaded()` is false) and the runtime, which counts every request it serves including availability probes, receives **zero**. A full working session (search, dashboard, registry, categories, status counts) keeps both at zero. The first request appears only when a person asks a question. Switched off, even `unavailableReason()` builds and contacts nothing. Finally the window's own source is read and required to contain no model class at all. |

The release gate additionally records the boundary as its own set of criteria: AI
outside the processing pipeline, AI cannot trigger processing, AI is manually invoked,
the boundary suite passed, and this document exists.

## 7. Optionality, lazy loading and local-only operation

**Nothing AI-related loads until someone asks for it.** The window holds an
`AgentService`, but that object is inert: `fromEnvironment` stores *how* to build a
provider and nothing more. No model configuration is read, no provider is constructed,
no HTTP client is created and no runtime is contacted while the application starts,
opens a case, ingests, extracts, indexes, searches or exports. The provider appears on
the first call that genuinely needs a model — opening the Assistant, or pressing an
analyse action — and `AgentService.isModelLoaded()` exists so that this can be asserted
rather than asserted about. B-08 measures it against a runtime that counts every
request, and the release gate additionally requires that `FasApp` mentions no model
class in its source.

If no local model is installed the application still starts, and reading, processing,
extraction, search, database operations and every screen work normally. The AI controls
simply report that the local agent is unavailable and what would enable it. There is no
failure path from a missing model into normal operation.

When enabled, the agent uses a fully local model runtime. No cloud provider is used or
permitted: a non-loopback endpoint is refused at construction, and the release gate
greps the agent for known cloud hosts. The runtime and model are chosen by
configuration (`aegis.ai.endpoint`, `aegis.ai.model`, `aegis.ai.enabled`), so the model
is replaceable without code changes.

The application declares **no inference dependency at all**. An ONNX Runtime dependency
that had been declared for in-pipeline classification was unused and has been removed,
so nothing in the build can quietly become an AI processing path.

**Hardware dependence, stated honestly.** The Java architecture and protocol are
verified end to end against a scripted loopback runtime, which is what makes the
assertions exact and reproducible. Executing a real neural model is a separate,
hardware-dependent matter: a low-memory development machine may not be able to run a
7B-parameter model at all. No test in this repository fakes model generation, and no
claim is made here that a particular model has been benchmarked on particular hardware.
See `docs/AI_AGENT.md` for the runtime requirements.

## 8. Intended agent behaviour

Multi-step reasoning through controlled tools, for example:

```text
"Find consulting documents from Source A, compare them with Source B,
 and summarise the common themes."

  → search Source A → retrieve content → search Source B → retrieve content
  → compare evidence → identify common themes → grounded answer, records cited
```

The agent may inspect existing files, extracted text, metadata, sources, aspects,
categories, keywords, contents, relationships, search results and statistics, and
reason over them. It must not alter those records merely because it inspected them.
