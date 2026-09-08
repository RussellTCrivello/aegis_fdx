package com.aegis.fdx.ai.tools;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.SearchCriteria;
import com.aegis.fdx.facade.SortField;
import com.aegis.fdx.facade.SortOrder;
import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.CategoryDto;
import com.aegis.fdx.facade.dto.KeywordDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.facade.dto.PreviewDto;
import com.aegis.fdx.facade.dto.SearchResultDto;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tools the agent may call, each backed by an existing application facade.
 *
 * <p>Every tool here is read-only. Nothing in this class writes to the case, so an
 * ordinary question cannot alter evidence. State-changing tools live in
 * {@link MutatingTools} and are registered only when the operator has confirmed.
 *
 * <p>Results are deliberately compact: a small local model has a limited context
 * window, so tools summarise and cap their output rather than dumping tables.
 */
public final class CaseTools {

    /** Cap on rows any single tool returns, to protect the model's context. */
    private static final int MAX_ROWS = 25;

    /** Cap on extracted text a tool will return in one call. */
    private static final int MAX_TEXT = 4000;

    private final AegisFacades facades;
    private final CorpusDatabase dao;

    public CaseTools(AegisFacades facades, CorpusDatabase dao) {
        this.facades = facades;
        this.dao = dao;
    }

    /** All read-only tools, in the order they are advertised to the model. */
    public List<AgentTool> all() {
        return List.of(
                searchTool(),
                itemTool(),
                contentTool(),
                sourceTool(),
                aspectTool(),
                categoryTool(),
                keywordTool(),
                relationshipTool(),
                statisticsTool(),
                processingStatusTool());
    }

    // ---------------------------------------------------------------- search

    private AgentTool searchTool() {
        return new Tool(
                ToolSchema.readOnly("search_items",
                        "Full-text search across all indexed material. Supports quoted "
                                + "phrases, wildcards (term*), fuzzy (term~) and AND/OR/NOT.",
                        ToolSchema.Param.required("query", "string", "the search expression"),
                        ToolSchema.Param.optional("fileType", "string",
                                "restrict to an extension such as pdf or txt", null),
                        ToolSchema.Param.optional("sourceId", "integer",
                                "restrict to one source", null),
                        ToolSchema.Param.optional("aspectId", "integer",
                                "restrict to one aspect", null),
                        ToolSchema.Param.optional("limit", "integer",
                                "maximum results, default 10", "10")),
                req -> {
                    SearchCriteria c = SearchCriteria.of(req.get("query"))
                            .fileType(req.get("fileType", null))
                            .source(req.getInteger("sourceId") != null
                                    ? req.getInteger("sourceId") : req.context().sourceId())
                            .aspect(req.getInteger("aspectId") != null
                                    ? req.getInteger("aspectId") : req.context().aspectId())
                            .sortBy(SortField.RELEVANCE, SortOrder.DESCENDING)
                            .limit(Math.min(req.getInt("limit", 10), MAX_ROWS));

                    Page<SearchResultDto> page = facades.search().search(c);
                    if (page.results().isEmpty()) {
                        return ToolResult.empty("No items matched \"" + req.get("query") + "\".");
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(page.totalCount()).append(" item(s) matched; showing ")
                            .append(page.results().size()).append(":\n");
                    List<ToolResult.Evidence> ev = new ArrayList<>();
                    for (SearchResultDto r : page.results()) {
                        sb.append("- ").append(r.fileName())
                                .append(" [").append(r.id()).append(']')
                                .append(" type=").append(nz(r.fileType()))
                                .append(" source=").append(nz(r.sourceName()))
                                .append(" matches=").append(r.matchCount());
                        if (!r.snippets().isEmpty()) {
                            sb.append("\n    \"").append(trim(r.snippets().get(0), 200)).append('"');
                        }
                        sb.append('\n');
                        ev.add(ToolResult.Evidence.item(r.id(), r.fileName()));
                    }
                    return ToolResult.ok(sb.toString(), ev);
                });
    }

    // ------------------------------------------------------------------ item

    private AgentTool itemTool() {
        return new Tool(
                ToolSchema.readOnly("get_item",
                        "Full metadata for one item: name, size, type, hashes, dates, "
                                + "custodian and processing status.",
                        ToolSchema.Param.optional("itemId", "string",
                                "item id; defaults to the item on screen", null)),
                req -> {
                    String id = req.get("itemId", req.context().elementId());
                    if (id == null) {
                        return ToolResult.failure(
                                "no itemId given and no item is open on screen");
                    }
                    try {
                        var it = facades.liveCase().byId(id);
                        if (it == null) {
                            return ToolResult.empty("No item with id " + id + ".");
                        }
                        String text = "Item " + it.id() + "\n"
                                + "  name: " + nz(it.name()) + "\n"
                                + "  type: " + nz(it.extension()) + "\n"
                                + "  size: " + it.size() + " bytes\n"
                                + "  status: " + it.status() + "\n"
                                + "  custodian: " + nz(it.custodian()) + "\n"
                                + "  path: " + nz(it.sourcePath()) + "\n"
                                + "  sha256: " + nz(it.sha256()) + "\n"
                                + "  md5: " + nz(it.md5()) + "\n"
                                + "  modified: " + it.modified() + "\n"
                                + "  container: " + nz(it.containerPath());
                        return ToolResult.ok(text,
                                List.of(ToolResult.Evidence.item(it.id(), it.name())));
                    } catch (Exception e) {
                        return ToolResult.failure("could not read item " + id + ": " + e.getMessage());
                    }
                });
    }

    // --------------------------------------------------------------- content

    private AgentTool contentTool() {
        return new Tool(
                ToolSchema.readOnly("get_content",
                        "The extracted text of an item, so its wording can be quoted or "
                                + "summarised. Truncated for long documents.",
                        ToolSchema.Param.optional("itemId", "string",
                                "item id; defaults to the item on screen", null),
                        ToolSchema.Param.optional("maxChars", "integer",
                                "characters to return, default 2000", "2000")),
                req -> {
                    String id = req.get("itemId", req.context().elementId());
                    if (id == null) {
                        return ToolResult.failure(
                                "no itemId given and no item is open on screen");
                    }
                    try {
                        PreviewDto p = facades.preview().getPreview(id);
                        if (p.content() == null || p.content().isBlank()) {
                            return ToolResult.empty("Item " + id + " (" + p.previewType()
                                    + ") has no extracted text stored.");
                        }
                        int cap = Math.min(req.getInt("maxChars", 2000), MAX_TEXT);
                        String body = trim(p.content(), cap);
                        return ToolResult.ok("Extracted text of " + nz(p.fileName())
                                        + " [" + id + "]:\n" + body,
                                List.of(ToolResult.Evidence.item(id, p.fileName())));
                    } catch (FacadeException e) {
                        return ToolResult.failure(e.getMessage());
                    }
                });
    }

    // ---------------------------------------------------------------- source

    private AgentTool sourceTool() {
        return new Tool(
                ToolSchema.readOnly("list_sources",
                        "Sources on the case, with their details. Optionally filter by name.",
                        ToolSchema.Param.optional("nameContains", "string",
                                "case-insensitive substring of the source name", null),
                        ToolSchema.Param.optional("sourceId", "integer",
                                "return just this source, in full", null)),
                req -> {
                    Integer id = req.getInteger("sourceId");
                    if (id == null && req.context().sourceId() != null && !req.has("nameContains")) {
                        id = req.context().sourceId();
                    }
                    if (id != null) {
                        try {
                            SourceDto s = facades.sources().getSource(id);
                            String text = "Source " + s.id() + "\n"
                                    + "  name: " + nz(s.name()) + "\n"
                                    + "  job: " + nz(s.job()) + "\n"
                                    + "  importance: " + s.importance() + "\n"
                                    + "  country/city: " + nz(s.country()) + " / " + nz(s.city()) + "\n"
                                    + "  access: " + nz(s.accessStatus()) + "\n"
                                    + "  description: " + nz(s.description()) + "\n"
                                    + "  discovered: " + s.entryDate();
                            return ToolResult.ok(text,
                                    List.of(ToolResult.Evidence.source(s.id(), s.name())));
                        } catch (FacadeException e) {
                            return ToolResult.failure(e.getMessage());
                        }
                    }
                    String filter = req.get("nameContains", null);
                    List<SourceDto> all = facades.sources().listSources();
                    List<ToolResult.Evidence> ev = new ArrayList<>();
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    for (SourceDto s : all) {
                        if (filter != null && !nz(s.name()).toLowerCase()
                                .contains(filter.toLowerCase())) {
                            continue;
                        }
                        if (shown++ >= MAX_ROWS) {
                            break;
                        }
                        sb.append("- [").append(s.id()).append("] ").append(s.name())
                                .append(" (").append(nz(s.job())).append(", ")
                                .append(nz(s.country())).append(")\n");
                        ev.add(ToolResult.Evidence.source(s.id(), s.name()));
                    }
                    if (shown == 0) {
                        return ToolResult.empty("No sources"
                                + (filter == null ? " exist yet." : " match \"" + filter + "\"."));
                    }
                    return ToolResult.ok(shown + " source(s):\n" + sb, ev);
                });
    }

    // ---------------------------------------------------------------- aspect

    private AgentTool aspectTool() {
        return new Tool(
                ToolSchema.readOnly("list_aspects",
                        "Aspects on the case: the parties or groupings material is "
                                + "attributed to."),
                req -> {
                    List<AspectDto> all = facades.aspects().listAspects();
                    if (all.isEmpty()) {
                        return ToolResult.empty("No aspects exist yet.");
                    }
                    StringBuilder sb = new StringBuilder();
                    List<ToolResult.Evidence> ev = new ArrayList<>();
                    for (AspectDto a : all) {
                        sb.append("- [").append(a.id()).append("] ").append(a.name())
                                .append(" (importance ").append(a.importance()).append(")\n");
                        ev.add(ToolResult.Evidence.aspect(a.id(), a.name()));
                    }
                    return ToolResult.ok(all.size() + " aspect(s):\n" + sb, ev);
                });
    }

    // -------------------------------------------------------------- category

    private AgentTool categoryTool() {
        return new Tool(
                ToolSchema.readOnly("list_categories",
                        "Categories and how many registered files fall under each."),
                req -> {
                    Page<CategoryDto> page = facades.categories().listCategories(MAX_ROWS, 0);
                    if (page.results().isEmpty()) {
                        return ToolResult.empty("No categories exist yet.");
                    }
                    Map<String, Integer> counts;
                    try {
                        counts = dao.countPathsByCategory();
                    } catch (Exception e) {
                        counts = Map.of();
                    }
                    StringBuilder sb = new StringBuilder();
                    List<ToolResult.Evidence> ev = new ArrayList<>();
                    for (CategoryDto c : page.results()) {
                        sb.append("- [").append(c.id()).append("] ").append(c.word())
                                .append(" — ").append(counts.getOrDefault(c.word(), 0))
                                .append(" file(s)\n");
                        ev.add(ToolResult.Evidence.category(c.id(), c.word()));
                    }
                    return ToolResult.ok(page.totalCount() + " category(ies):\n" + sb, ev);
                });
    }

    // --------------------------------------------------------------- keyword

    private AgentTool keywordTool() {
        return new Tool(
                ToolSchema.readOnly("list_keywords",
                        "Keywords, their category, and how many times each was found "
                                + "across processed material.",
                        ToolSchema.Param.optional("keywordId", "integer",
                                "show the files carrying just this keyword", null)),
                req -> {
                    Integer kwId = req.getInteger("keywordId");
                    if (kwId == null) {
                        kwId = req.context().keywordId();
                    }
                    if (kwId != null) {
                        try {
                            KeywordDto k = facades.keywords().getKeyword(kwId);
                            var rows = dao.selectPathsForKeyword(kwId, MAX_ROWS);
                            if (rows.isEmpty()) {
                                return ToolResult.empty("Keyword \"" + k.keyword()
                                        + "\" has not been found in any processed file.");
                            }
                            StringBuilder sb = new StringBuilder("Files containing \"")
                                    .append(k.keyword()).append("\":\n");
                            List<ToolResult.Evidence> ev = new ArrayList<>();
                            for (var r : rows) {
                                sb.append("- ").append(r.str("file_name"))
                                        .append(" — ").append(r.i("hits")).append(" hit(s)\n");
                                ev.add(ToolResult.Evidence.content(r.i("id"), r.str("file_name")));
                            }
                            return ToolResult.ok(sb.toString(), ev);
                        } catch (Exception e) {
                            return ToolResult.failure("keyword lookup failed: " + e.getMessage());
                        }
                    }
                    Page<KeywordDto> page = facades.keywords().listKeywords(MAX_ROWS, 0);
                    if (page.results().isEmpty()) {
                        return ToolResult.empty("No keywords are defined yet.");
                    }
                    StringBuilder sb = new StringBuilder();
                    List<ToolResult.Evidence> ev = new ArrayList<>();
                    for (KeywordDto k : page.results()) {
                        int total = 0;
                        try {
                            for (var r : dao.selectPathsForKeyword(k.id(), 1000)) {
                                total += r.i("hits");
                            }
                        } catch (Exception ignored) {
                            // count is a convenience; the listing still stands
                        }
                        sb.append("- [").append(k.id()).append("] \"").append(k.keyword())
                                .append("\" (category ").append(k.categoryWord())
                                .append(") — ").append(total).append(" total hit(s)\n");
                        ev.add(ToolResult.Evidence.keyword(k.id(), k.keyword()));
                    }
                    return ToolResult.ok(page.totalCount() + " keyword(s):\n" + sb, ev);
                });
    }

    // ---------------------------------------------------------- relationships

    private AgentTool relationshipTool() {
        return new Tool(
                ToolSchema.readOnly("get_relationships",
                        "How one registered file relates to its source, aspect, "
                                + "categories and keywords.",
                        ToolSchema.Param.optional("pathId", "integer",
                                "registry file id; defaults to the file on screen", null),
                        ToolSchema.Param.optional("itemId", "string",
                                "resolve the registry entry from an item id instead", null)),
                req -> {
                    try {
                        Integer pathId = req.getInteger("pathId");
                        if (pathId == null) {
                            pathId = req.context().pathId();
                        }
                        if (pathId == null && req.has("itemId")) {
                            pathId = dao.findPathIdByElement(req.get("itemId"));
                        }
                        if (pathId == null && req.context().elementId() != null) {
                            pathId = dao.findPathIdByElement(req.context().elementId());
                        }
                        if (pathId == null) {
                            return ToolResult.failure(
                                    "no pathId or itemId given and nothing relevant is on screen");
                        }
                        PathDto p = facades.contents().getPath(pathId);
                        StringBuilder sb = new StringBuilder();
                        sb.append("File ").append(p.fileName()).append(" [path ")
                                .append(p.id()).append("]\n")
                                .append("  source: ").append(nz(p.sourceName())).append('\n')
                                .append("  aspect: ").append(nz(p.aspectName())).append('\n')
                                .append("  item:   ").append(nz(p.elementId())).append('\n')
                                .append("  sha256: ").append(nz(p.hashValue())).append('\n')
                                .append("  review state: ").append(p.fileStatus()).append('\n');

                        List<ToolResult.Evidence> ev = new ArrayList<>();
                        ev.add(ToolResult.Evidence.content(p.id(), p.fileName()));
                        if (p.sourceId() != null) {
                            ev.add(ToolResult.Evidence.source(p.sourceId(), p.sourceName()));
                        }
                        if (p.aspectId() != null) {
                            ev.add(ToolResult.Evidence.aspect(p.aspectId(), p.aspectName()));
                        }
                        if (p.elementId() != null) {
                            ev.add(ToolResult.Evidence.item(p.elementId(), p.fileName()));
                        }

                        var cats = dao.selectPathCategories(p.id());
                        sb.append("  categories: ");
                        if (cats.isEmpty()) {
                            sb.append("(none)\n");
                        } else {
                            List<String> names = new ArrayList<>();
                            for (var c : cats) {
                                names.add(c.str("word"));
                                ev.add(ToolResult.Evidence.category(c.i("id"), c.str("word")));
                            }
                            sb.append(String.join(", ", names)).append('\n');
                        }

                        var kws = dao.selectPathKeywords(p.id());
                        sb.append("  keywords: ");
                        if (kws.isEmpty()) {
                            sb.append("(none found)\n");
                        } else {
                            List<String> parts = new ArrayList<>();
                            for (var k : kws) {
                                parts.add(k.str("keyword") + " ×" + k.i("hits"));
                                ev.add(ToolResult.Evidence.keyword(k.i("id"), k.str("keyword")));
                            }
                            sb.append(String.join(", ", parts)).append('\n');
                        }
                        return ToolResult.ok(sb.toString(), ev);
                    } catch (Exception e) {
                        return ToolResult.failure("relationship lookup failed: " + e.getMessage());
                    }
                });
    }

    // ------------------------------------------------------------ statistics

    private AgentTool statisticsTool() {
        return new Tool(
                ToolSchema.readOnly("get_statistics",
                        "Case totals: item counts by processing status, file-type and "
                                + "source distribution, storage used."),
                req -> {
                    try {
                        var stats = facades.dashboard().getStats();
                        StringBuilder sb = new StringBuilder();
                        sb.append("Case statistics\n")
                                .append("  total items: ").append(stats.totalFiles()).append('\n')
                                .append("  indexed: ").append(stats.indexed()).append('\n')
                                .append("  errors: ").append(stats.errors()).append('\n')
                                .append("  locked: ").append(stats.locked()).append('\n')
                                .append("  unsupported: ").append(stats.unsupported()).append('\n')
                                .append("  duplicate clusters: ")
                                .append(stats.duplicateClusters()).append('\n')
                                .append("  total bytes: ").append(stats.totalBytes()).append('\n');
                        sb.append("  by file type: ")
                                .append(facades.dashboard().getFileTypeBreakdown()).append('\n');
                        sb.append("  by source: ").append(dao.countPathsBySource()).append('\n');
                        sb.append("  by aspect: ").append(dao.countPathsByAspect()).append('\n');
                        sb.append("  registered files: ").append(dao.countPathsAll()).append('\n');
                        sb.append("  stored contents: ").append(dao.countContents()).append('\n');
                        return ToolResult.ok(sb.toString());
                    } catch (Exception e) {
                        return ToolResult.failure("statistics unavailable: " + e.getMessage());
                    }
                });
    }

    // ------------------------------------------------------- processing state

    private AgentTool processingStatusTool() {
        return new Tool(
                ToolSchema.readOnly("get_processing_status",
                        "What the ingest pipeline did with the material, including which "
                                + "items failed and why."),
                req -> {
                    try {
                        var counts = facades.liveCase().statusCounts();
                        StringBuilder sb = new StringBuilder("Processing status\n");
                        counts.forEach((k, v) -> sb.append("  ").append(k.label())
                                .append(": ").append(v).append('\n'));

                        List<ToolResult.Evidence> ev = new ArrayList<>();
                        int listed = 0;
                        for (var it : facades.liveCase().allItems()) {
                            if (it.status() != com.aegis.fdx.model.ItemStatus.INDEXED
                                    && listed < 10) {
                                sb.append("  ! ").append(it.name()).append(" [")
                                        .append(it.id()).append("] ").append(it.status());
                                if (it.errors() != null && !it.errors().isEmpty()) {
                                    sb.append(" - ").append(
                                            trim(String.join("; ", it.errors()), 160));
                                }
                                sb.append('\n');
                                ev.add(ToolResult.Evidence.item(it.id(), it.name()));
                                listed++;
                            }
                        }
                        return ToolResult.ok(sb.toString(), ev);
                    } catch (Exception e) {
                        return ToolResult.failure("processing status unavailable: " + e.getMessage());
                    }
                });
    }

    // ----------------------------------------------------------------- utils

    private static String nz(String s) {
        return s == null || s.isBlank() ? "(none)" : s;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "\n… (truncated)";
    }

    /** Adapts a schema plus a lambda into an {@link AgentTool}. */
    static final class Tool implements AgentTool {
        private final ToolSchema schema;
        private final java.util.function.Function<ToolRequest, ToolResult> body;

        Tool(ToolSchema schema, java.util.function.Function<ToolRequest, ToolResult> body) {
            this.schema = schema;
            this.body = body;
        }

        @Override
        public String name() {
            return schema.name();
        }

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            long t0 = System.nanoTime();
            try {
                return body.apply(request).withTiming((System.nanoTime() - t0) / 1_000_000);
            } catch (FacadeException e) {
                return ToolResult.failure(e.getMessage())
                        .withTiming((System.nanoTime() - t0) / 1_000_000);
            } catch (RuntimeException e) {
                return ToolResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage())
                        .withTiming((System.nanoTime() - t0) / 1_000_000);
            }
        }
    }
}
