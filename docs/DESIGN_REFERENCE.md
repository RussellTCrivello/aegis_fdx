# Design Reference

The interface design of this application was informed by studying an existing
open-source file-analysis project as a **reference for user experience only**:

- overall layout and navigation structure
- information architecture and page organisation
- workflow ordering (define attribution → ingest → register → work)
- component vocabulary: stat tiles, section cards, filter bars, data tables
- the five domain concepts added here: Sources, Aspects, Categories, Keywords, Contents

## What was NOT taken

Nothing was ported, embedded, imported or exposed:

| Not taken | Why |
|---|---|
| Its HTTP routes | This is a desktop application; it has no HTTP surface |
| Its service/API signatures | The interface layer is Java-native: criteria objects, typed DTOs, enums, builders |
| Its backend | The existing Java processing engine remains the implementation |
| Its database engine and schema | This application uses its own SQLite schema in the case database |
| Its internal modules, algorithms or data structures | Not reproduced |
| Its templates, stylesheets or scripts | The UI is JavaFX with its own stylesheet |

## How the interface layer is designed instead

Java conventions throughout:

```java
// A search is a criteria object, not a positional parameter list.
Page<SearchResultDto> hits = facades.search().search(
        SearchCriteria.of("invoice")
                .fileType("pdf")
                .source(sourceId)
                .between(from, to)
                .sortBy(SortField.DATE, SortOrder.DESCENDING)
                .page(0, 50));

// Optional details travel in a draft, not thirteen arguments.
int id = facades.sources().createSource(
        new SourceDraft("Acme", "NL", "custodian", 0.85)
                .city("Amsterdam")
                .accessStatus("Full access"));
```

- Enums (`SortField`, `SortOrder`, `FileState`, `Priority`) instead of magic strings
- Records for DTOs
- One unchecked `FacadeException` carrying a `Kind`, instead of leaking `SQLException`
- Method names that read as Java: `listSources`, `getAspect`, `registerIngestedItems`

## Terminology

One concept was renamed for clarity in this application:

| Reference term | Term used here | Note |
|---|---|---|
| Side | **Aspect** | `SideFacade` and `SideDto` remain as deprecated aliases that delegate, so earlier callers keep compiling |

Everything verified, screen by screen, in
**[INTERFACE_INVENTORY.md](INTERFACE_INVENTORY.md)**.
