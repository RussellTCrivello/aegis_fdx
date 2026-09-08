# Local AI Agent

Version 1.2 · 2026-09-08

The application includes an analysis assistant that runs **entirely on the local
machine**. It reasons over case data by calling controlled application tools, and cites
the records behind every answer.

---

## 1. What it is, and what it is not

**It is** an intelligent operator layer sitting on top of the existing Java
application. It searches, reads and correlates the same data a human operator would,
through the same facades.

**It is not** a replacement for the processing engine. It does not read files, extract
text, hash, OCR or index anything. Those remain the engine's job; the agent consults
their results. The application must — and does — run normally with the assistant
disabled, unavailable, unconfigured or removed. The normative statement of that
separation, and the checks that enforce it, are in
**[AI_BOUNDARY.md](AI_BOUNDARY.md)**; this document describes how the agent itself
works.

---

## 2. Local-only guarantee

No cloud AI service is used or required. Concretely:

| Guarantee | How it is enforced |
|---|---|
| No cloud endpoint in the code | Release-gate check greps the agent package for known provider hosts |
| Remote endpoints refused at runtime | `HttpLocalModelProvider` throws unless the endpoint is loopback |
| Works with the machine offline | `AiAgentTest.offlineOperation` runs the full loop against a loopback runtime |
| Application unaffected without AI | `AiAgentTest.degradesWithoutRuntime` — search and database still work |

```java
// Constructing a provider against a remote host fails immediately.
ModelConfig remote = ModelConfig.defaults().withEndpoint("https://api.example.com");
new HttpLocalModelProvider(remote);
// ModelException: refusing a non-loopback AI endpoint … the agent is local-only by design
```

---

## 3. Architecture

```
  Operator
     │
     ▼
  AgentScreen  ── contextual "ask" actions on other screens
     │  (runs off the FX thread; UI never blocks)
     ▼
  AgentService ─────────────── history / activity trail
     │
     ▼
  AgentOrchestrator ── the loop: plan → call tool → observe → repeat → answer
     │                          bounded by a step budget
     │
     ├── LocalModelProvider (interface)
     │        └── HttpLocalModelProvider  → loopback HTTP → local runtime → model
     │
     └── Tool gateway (allow-list)
              │
              ├── search_items            └── SearchFacade  → Lucene
              ├── get_item                └── LiveCase
              ├── get_content             └── PreviewFacade
              ├── list_sources            └── SourceFacade
              ├── list_aspects            └── AspectFacade
              ├── list_categories         └── CategoryFacade
              ├── list_keywords           └── KeywordFacade
              ├── get_relationships       └── ContentFacade + CorpusDatabase
              ├── get_statistics          └── DashboardFacade
              ├── get_processing_status   └── LiveCase
              │
              └── confirmation required:
                  ├── classify_file       └── CorpusDatabase
                  └── set_review_state    └── ContentFacade
                                 │
                                 ▼
                        Existing Java core
                                 │
                                 ▼
                              case.db
```

Every arrow into the application goes through a facade. The model itself has no other
route in.

---

## 4. The agent loop

```
 question
    │
    ▼
 system prompt  ← tool catalogue + screen context + grounding rules
    │
    ▼
 model turn ──────────────► emits {"tool": "...", "arguments": {...}}
    │                                    │
    │                                    ▼
    │                        validate against schema
    │                                    │
    │                        execute via facade
    │                                    │
    │                        observation + evidence ids
    │                                    │
    │◄───────────────────────────────────┘
    │   (repeat, up to the step budget)
    ▼
 model turn with no tool call → final answer
    │
    ▼
 grounding check → caveat appended if nothing was retrieved
```

Bounded by `AgentOrchestrator.DEFAULT_MAX_STEPS` (6). When the budget is reached the
agent is asked for a final answer from what it already gathered, rather than being cut
off mid-investigation.

### Worked example

Question: *"What consulting material is on this case and where did it come from?"*

```
1. search_items {query=consulting, limit=5}  ok  111 ms  (5 records)
     7 item(s) matched; showing 5
2. list_sources {}                           ok   10 ms  (2 records)
     2 source(s)
3. get_statistics {}                         ok    7 ms
     Case statistics
evidence: item:E-000007, item:E-000002, item:E-000003, item:E-000006,
          item:E-000001, source:1, source:2
model calls: 4, total 318 ms
```

That trace is real output from `tools/AgentDemoHarness`, run against the seeded
workspace.

---

## 5. Model selection

The runtime and model are chosen by configuration, never by code.

| Property | Default | Meaning |
|---|---|---|
| `aegis.ai.enabled` | `true` | set `false` to disable the agent entirely |
| `aegis.ai.endpoint` | `http://127.0.0.1:11434` | local runtime base URL (must be loopback) |
| `aegis.ai.model` | `qwen2.5:7b-instruct` | chat model identifier |
| `aegis.ai.embedModel` | `nomic-embed-text` | embedding model, when semantic retrieval is used |
| `aegis.ai.timeoutSeconds` | `120` | per-request budget |
| `aegis.ai.contextTokens` | `8192` | usable context window |
| `aegis.ai.maxOutputTokens` | `1024` | generation cap |

### Why this default

`qwen2.5:7b-instruct` was chosen for **instruction-following and structured output at a
size that runs on CPU**. The agent depends on the model reliably emitting a small JSON
block; weaker 1–3B models do that inconsistently, which produces an agent that looks
fast and fails often. A 7B instruct model at 4-bit quantisation needs roughly 5–6 GB of
RAM and answers in single-digit seconds on a modern CPU.

Selecting a smaller model to reduce startup time is a false economy here — a tool call
that never parses costs far more than a slower one that does.

### Replacing the model

```bash
# any stronger local model, no code change
java -Daegis.ai.model=qwen2.5:14b-instruct -jar app.jar

# a different local runtime on another port
java -Daegis.ai.endpoint=http://127.0.0.1:8080 -jar app.jar

# turn the assistant off
java -Daegis.ai.enabled=false -jar app.jar
```

To support a runtime with a different wire protocol, implement `LocalModelProvider`
and `LocalChatModel`. Nothing else changes.

### Installing a runtime

1. Install a local inference runtime that serves an HTTP API on loopback.
2. Pull the model: e.g. `ollama pull qwen2.5:7b-instruct`.
3. Start the runtime; leave it on `127.0.0.1`.
4. Launch the application. The Assistant screen reports the model it found.

If no runtime is present, the Assistant screen says so and explains what to install.
Everything else in the application continues to work.

---

## 6. Tools

| Tool | Reads | Returns |
|---|---|---|
| `search_items` | Lucene index | matching items, ranked, with snippets |
| `get_item` | item store | full metadata, hashes, status |
| `get_content` | extracted text | the document's text, truncated |
| `list_sources` | `source` | sources, or one in detail |
| `list_aspects` | `aspect` | aspects with importance |
| `list_categories` | `category`, `path_category` | categories with file counts |
| `list_keywords` | `keyword`, `path_keyword` | keywords with hit counts, or files per keyword |
| `get_relationships` | `path` + joins | source, aspect, hash, categories, keywords for one file |
| `get_statistics` | engine + registry | counts, distributions, storage |
| `get_processing_status` | ingest queue | status counts and failures with reasons |

Write-capable, confirmation-gated:

| Tool | Effect |
|---|---|
| `classify_file` | attaches a category to a registered file |
| `set_review_state` | marks a file Read or Unread |

Both annotate **review metadata only**. No tool alters original material, extracted
text or hashes.

### Tool contract

```java
public interface AgentTool {
    String name();
    ToolSchema schema();
    ToolResult execute(ToolRequest request);
}
```

Arguments are validated against the schema before execution: missing required
arguments, non-numeric integers and unknown argument names are all rejected, and the
rejection is fed back to the model so it can correct itself.

---

## 7. Safety boundary

What the agent **cannot** do, verified by `AiAgentTest.noEscapeHatches`:

- run shell commands
- execute SQL
- read or write arbitrary files
- make network requests
- reach any facade not wrapped by a registered tool

Read-only is the default. Mutating tools are not merely hidden — they are not
registered at all unless the operator confirms it, and they refuse execution
independently even if invoked directly. Ticking "Allow the assistant to change data"
opens a confirmation that names the two review actions it would enable and what remains
impossible; declining leaves the control off and the session read-only.

The agent is also structurally separate from the processing engine: nothing in the
reading, extraction, metadata, OCR, hashing, indexing or storage path references it,
and no ingest run can invoke it. That rule, and the suite that enforces it
(`AiBoundaryTest`, B-01…B-08), are described in **[AI_BOUNDARY.md](AI_BOUNDARY.md)**.

---

## 8. Evidence grounding

Every tool returns `Evidence` records: typed identifiers of real rows
(`item:E-000001`, `source:1`, `category:3`). The interface renders these as chips under
the answer.

An answer is considered grounded when either a tool cited specific records, or a tool
succeeded and returned measured facts. Aggregate tools such as `get_statistics`
legitimately have no single row to point at — an earlier revision required a record
identifier and consequently flagged correct statistical answers as unsupported. That
was a real defect, caught by a test, and the definition was corrected.

When nothing was retrieved, the agent appends:

> *(No matching records were found, so this answer is not supported by case data.)*

The system prompt instructs the model never to invent file names, identifiers, counts
or quotations, and to state plainly when evidence is insufficient.

---

## 9. Context

The screen the operator is on becomes part of the request, so questions can be
conversational:

| Screen | Context supplied |
|---|---|
| Search | active query |
| Sources | source id |
| File Library | path id, item id |
| Keywords | keyword id |
| Categories | category id |

"Summarise this file" resolves without the operator pasting an identifier.

---

## 10. Memory

Three distinct scopes, deliberately separated:

| Scope | Lifetime | Role |
|---|---|---|
| Conversation | one run | the turns of the current exchange |
| Case context | one run | what is on screen |
| Evidence | one run | records retrieved by tools |

There is **no persistent model memory**. Evidence always comes from authoritative
application data, freshly retrieved, so a stale recollection can never become a source
of fact. Runs are retained only as an audit trail.

---

## 11. Audit

Each run records the request, every tool with its arguments, success or failure, a
result summary, the evidence count and per-step timing.

The Agent Activity panel shows tool and evidence summaries. It deliberately does **not**
expose private model reasoning — the useful, checkable thing is which tools ran and
which records they returned.

---

## 12. Retrieval

Retrieval uses the existing infrastructure first: the Lucene index, metadata filters,
category and keyword relationships, and source attribution. No second database and no
external vector store.

`EmbeddingProvider` exists for local semantic retrieval when an embedding model is
configured. It supplements keyword retrieval rather than replacing it — for
investigative work, exact term and phrase matching remains essential.

---

## 13. Failure handling

| Failure | Behaviour |
|---|---|
| No runtime installed | Assistant explains what to install; rest of the app unaffected |
| Model not installed | Named in the unavailability message |
| Timeout | `ModelException(TIMEOUT)`; the UI reports it and re-enables Ask |
| Runtime error | `ModelException(BAD_RESPONSE)` |
| Unknown tool requested | Reported to the model as an observation; the agent recovers |
| Invalid arguments | Rejected before execution, with the expected signature |
| Operator cancels | Loop stops at the next step boundary |
| Step budget reached | Final answer requested from evidence already gathered |

Each of these is covered by a test.

---

## 14. Resource requirements

| Model size | RAM (4-bit) | CPU response |
|---|---|---|
| 3B instruct | ~2.5 GB | 2–5 s |
| **7B instruct (default)** | **~5.5 GB** | **5–15 s** |
| 14B instruct | ~10 GB | 15–40 s |

Add roughly 1 GB for the application itself. A GPU is not required; if the runtime uses
one, responses are considerably faster.

---

## 15. Testing

`AiAgentTest` — 23 tests, all passing. Because generation is not deterministic, the
tests assert the surrounding contracts using a scripted loopback runtime
(`FakeLocalRuntime`) that speaks the real protocol:

| Area | Covered |
|---|---|
| Wire protocol | request shape, response parsing, model availability |
| Endpoint safety | remote endpoints refused |
| Failures | unreachable, HTTP error, timeout, cancellation |
| Tool-call parsing | single, multiple, argument-free, numeric args, prose-only |
| Tool contracts | schemas present, argument validation, real data returned |
| Empty vs failed | an empty case is not an error |
| Safety | mutating tools gated; no shell/SQL/file/network tool exists |
| Agent loop | multi-step, unknown-tool recovery, invalid args, step budget |
| Grounding | evidence collected; unsupported answers flagged |
| Context | screen context reaches tools; prompt contract |
| Offline | full loop with only a loopback runtime |
| Degradation | application works with no runtime at all |

Run them:

```bash
./gradlew :app:test --tests '*AiAgentTest'
```

### Boundary suite

`AiBoundaryTest` is a separate, architecture-level battery (B-01…B-08) asserting that
the agent stays optional, manually invoked and read-only: no pipeline package
references it in source or bytecode, a full ingest/index/analyse/search cycle makes
zero model calls with a runtime reachable, and a question leaves every record, file and
index segment untouched — verified with a control that a *confirmed* write does move
the same fingerprint. It runs inside `./run-tests.sh`, `./gradlew :app:test` and
`./final-acceptance.sh`:

```bash
./gradlew :app:test --tests '*SuiteBridgeTest'      # includes the boundary suite
```

Status note: this suite was authored on a machine without a JDK, so its first execution
belongs to the next build on a machine that has one. It is wired into all three
runners, and the release gate fails if it does not report `0 failed`.

---

## 16. Verified in this environment

Honest scope note. This build machine has **2 CPU cores and ~400 MB of free RAM**, which
cannot hold a 7B model — or any neural model of useful size.

What was therefore verified here:

- the complete agent architecture, compiled and exercised end to end
- the HTTP protocol, against a real local HTTP server speaking the runtime's format
- the full multi-step loop, tool execution against the real Lucene index and database,
  evidence collection, grounding, audit trail and UI rendering
- offline behaviour and graceful degradation

What was **not** verified here, and needs a machine with sufficient RAM:

- generation quality of a specific model
- whether a given model reliably emits the tool-call JSON format
- real-world latency figures

The screenshot in the interface guide shows the agent running against a scripted
loopback runtime: the agent, tools, facades, database and index are all real, and only
token generation is scripted. Substituting a genuine runtime requires no code change —
only `aegis.ai.endpoint` and `aegis.ai.model`.
