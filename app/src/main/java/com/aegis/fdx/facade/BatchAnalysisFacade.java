package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.BatchRun;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Batch analysis: run a chosen analysis over a selected set of registered files, and
 * keep a history of what each run did.
 *
 * <p>This is a distinct user-facing workflow from the processing monitor. The monitor
 * answers "what is the ingest pipeline doing right now"; this answers "run this
 * analysis over this selection, and show me what previous runs found". They share the
 * engine underneath and duplicate none of it.
 *
 * <p>Analysis here means real computation over material the engine already processed —
 * keyword scanning against stored extracted text, classification against categories —
 * not a second extraction pass. Nothing re-reads original evidence.
 */
public final class BatchAnalysisFacade {

    /** What a run does to each selected file. */
    public enum Template {
        /** Counts keyword occurrences in stored text and records the hits. */
        KEYWORD_SCAN("Keyword Scan", "Count keyword occurrences in extracted text"),
        /** Applies categories whose naming word appears in the text. */
        CLASSIFY("Classification", "Apply categories whose term appears in the text"),
        /** Both, in one pass over the text. */
        DEEP("Deep Analysis", "Keyword scan and classification together"),
        /** Recomputes review statistics without changing classification. */
        RECOUNT("Recount", "Refresh counts without changing classification");

        private final String label;
        private final String description;

        Template(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }

        public static Template fromLabel(String s) {
            if (s == null || s.isBlank()) {
                return KEYWORD_SCAN;
            }
            for (Template t : values()) {
                if (t.label.equalsIgnoreCase(s.trim()) || t.name().equalsIgnoreCase(s.trim())) {
                    return t;
                }
            }
            throw FacadeException.validation("unknown analysis template: " + s);
        }
    }

    /** How a run reacts to a file that fails. */
    public enum ErrorHandling {
        SKIP_FAILED("Skip Failed"),
        STOP_ON_ERROR("Stop on Error");

        private final String label;

        ErrorHandling(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static ErrorHandling fromLabel(String s) {
            if (s == null || s.isBlank()) {
                return SKIP_FAILED;
            }
            for (ErrorHandling e : values()) {
                if (e.label.equalsIgnoreCase(s.trim())) {
                    return e;
                }
            }
            return SKIP_FAILED;
        }
    }

    /** Priority recorded against a run, for the history view. */
    public enum Priority {
        LOW("Low"), MEDIUM("Medium"), HIGH("High");

        private final String label;

        Priority(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Priority fromLabel(String s) {
            if (s == null || s.isBlank()) {
                return MEDIUM;
            }
            for (Priority p : values()) {
                if (p.label.equalsIgnoreCase(s.trim())) {
                    return p;
                }
            }
            return MEDIUM;
        }
    }

    /** A run request. */
    public static final class Request {
        private final Template template;
        private Priority priority = Priority.MEDIUM;
        private ErrorHandling errorHandling = ErrorHandling.SKIP_FAILED;
        private Integer sourceId;
        private Integer aspectId;
        private String fileType;
        private List<Integer> pathIds = new ArrayList<>();

        public Request(Template template) {
            this.template = template == null ? Template.KEYWORD_SCAN : template;
        }

        public Request priority(Priority p) {
            this.priority = p == null ? Priority.MEDIUM : p;
            return this;
        }

        public Request errorHandling(ErrorHandling e) {
            this.errorHandling = e == null ? ErrorHandling.SKIP_FAILED : e;
            return this;
        }

        public Request source(Integer id) {
            this.sourceId = id;
            return this;
        }

        public Request aspect(Integer id) {
            this.aspectId = id;
            return this;
        }

        public Request fileType(String t) {
            this.fileType = (t == null || t.isBlank()) ? null : t.trim();
            return this;
        }

        /** Explicit selection; when empty the filters decide the set. */
        public Request paths(List<Integer> ids) {
            this.pathIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
            return this;
        }

        public Template template() {
            return template;
        }

        public Priority priority() {
            return priority;
        }

        public ErrorHandling errorHandling() {
            return errorHandling;
        }

        public Integer sourceId() {
            return sourceId;
        }

        public Integer aspectId() {
            return aspectId;
        }

        public String fileType() {
            return fileType;
        }

        public List<Integer> pathIds() {
            return List.copyOf(pathIds);
        }
    }

    /** Progress callback payload. */
    public record Progress(int done, int total, String currentFile, int failed) {
        public double fraction() {
            return total == 0 ? 0d : (double) done / total;
        }
    }

    private final CorpusDatabase db;
    private final ContentFacade contents;
    private final KeywordFacade keywords;
    private final CategoryFacade categories;

    private volatile boolean cancelled;

    public BatchAnalysisFacade(CorpusDatabase db, ContentFacade contents,
                               KeywordFacade keywords, CategoryFacade categories) {
        this.db = db;
        this.contents = contents;
        this.keywords = keywords;
        this.categories = categories;
    }

    /** Files a request would act on, so the interface can show the selection first. */
    public List<PathDto> resolveSelection(Request req) {
        if (req == null) {
            throw FacadeException.validation("request is required");
        }
        List<PathDto> all = contents.getPaths(req.fileType(), req.sourceId(),
                req.aspectId(), null, 10_000, 0).results();
        if (req.pathIds().isEmpty()) {
            return all;
        }
        List<PathDto> chosen = new ArrayList<>();
        for (PathDto p : all) {
            if (req.pathIds().contains(p.id())) {
                chosen.add(p);
            }
        }
        return chosen;
    }

    /** Requests cancellation of the run in progress. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Runs an analysis and records it in the history.
     *
     * <p>Synchronous: the interface calls this from a background thread and receives
     * progress through the callback.
     *
     * @param onProgress may be null
     * @return the completed run, including per-file outcomes
     */
    public BatchRun run(Request req, Consumer<Progress> onProgress) {
        if (req == null) {
            throw FacadeException.validation("request is required");
        }
        cancelled = false;
        List<PathDto> selection = resolveSelection(req);
        if (selection.isEmpty()) {
            throw FacadeException.validation(
                    "nothing to analyse: no registered files match that selection");
        }

        long t0 = System.nanoTime();
        Instant started = Instant.now();
        int runId;
        try {
            runId = db.insertBatchRun(req.template().label(), req.priority().label(),
                    req.errorHandling().label(), req.sourceId(), req.aspectId(),
                    req.fileType(), selection.size(), started);
        } catch (SQLException e) {
            throw FacadeException.internal("could not start the analysis run", e);
        }

        int completed = 0;
        int failed = 0;
        String note = null;
        String state = "Completed";

        // Load the vocabulary once rather than per file.
        var kws = keywords.listKeywords(10_000, 0).results();
        var cats = categories.listCategories(10_000, 0).results();

        for (PathDto p : selection) {
            if (cancelled) {
                state = "Cancelled";
                note = "cancelled by the operator after " + completed + " file(s)";
                break;
            }
            long fileStart = System.nanoTime();
            try {
                String text = contents.getContentAsText(p.id());
                if (text == null || text.isBlank()) {
                    record(runId, p.id(), "Skipped", "no extracted text stored", fileStart);
                    completed++;
                } else {
                    String lower = text.toLowerCase();
                    int applied = 0;

                    if (req.template() == Template.KEYWORD_SCAN
                            || req.template() == Template.DEEP) {
                        for (var k : kws) {
                            int hits = countOccurrences(lower, k.keyword().toLowerCase());
                            if (hits > 0) {
                                db.linkPathToKeyword(p.id(), k.id(), hits);
                                applied += hits;
                            }
                        }
                    }
                    if (req.template() == Template.CLASSIFY
                            || req.template() == Template.DEEP) {
                        for (var c : cats) {
                            if (lower.contains(c.word().toLowerCase())) {
                                db.linkPathToCategory(p.id(), c.id());
                                applied++;
                            }
                        }
                    }
                    record(runId, p.id(), "Analysed",
                            applied + " match(es)", fileStart);
                    completed++;
                }
            } catch (Exception e) {
                failed++;
                try {
                    record(runId, p.id(), "Failed", String.valueOf(e.getMessage()), fileStart);
                } catch (RuntimeException ignored) {
                    // recording the failure must not mask the original failure
                }
                if (req.errorHandling() == ErrorHandling.STOP_ON_ERROR) {
                    state = "Failed";
                    note = "stopped on the first error: " + e.getMessage();
                    break;
                }
            }
            if (onProgress != null) {
                onProgress.accept(new Progress(completed + failed, selection.size(),
                        p.fileName(), failed));
            }
        }

        long millis = (System.nanoTime() - t0) / 1_000_000;
        try {
            db.finishBatchRun(runId, state, completed, failed, Instant.now(), millis, note);
        } catch (SQLException e) {
            throw FacadeException.internal("could not finish the analysis run", e);
        }
        return getRun(runId);
    }

    private void record(int runId, int pathId, String outcome, String detail, long startNanos) {
        try {
            db.insertBatchItem(runId, pathId, outcome, detail,
                    (System.nanoTime() - startNanos) / 1_000_000);
        } catch (SQLException e) {
            throw FacadeException.internal("could not record a file outcome", e);
        }
    }

    /** Run history, most recent first. */
    public List<BatchRun> history(int limit) {
        try {
            List<BatchRun> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectBatchRuns(Validate.limit(limit))) {
                out.add(toRun(r, List.of()));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("could not read the analysis history", e);
        }
    }

    /** One run with its per-file outcomes. */
    public BatchRun getRun(int runId) {
        Validate.positiveId(runId, "runId");
        try {
            CorpusDatabase.Row r = db.selectBatchRun(runId);
            if (r == null) {
                throw FacadeException.notFound("analysis run", runId);
            }
            List<BatchRun.ItemOutcome> items = new ArrayList<>();
            for (CorpusDatabase.Row i : db.selectBatchItems(runId)) {
                items.add(new BatchRun.ItemOutcome(i.i("path_id"), i.str("file_name"),
                        i.str("file_type"), i.l("file_size"), i.str("outcome"),
                        i.str("detail"), i.l("millis")));
            }
            return toRun(r, items);
        } catch (SQLException e) {
            throw FacadeException.internal("could not read the analysis run", e);
        }
    }

    public boolean deleteRun(int runId) {
        Validate.positiveId(runId, "runId");
        try {
            return db.deleteBatchRun(runId);
        } catch (SQLException e) {
            throw FacadeException.internal("could not delete the analysis run", e);
        }
    }

    public int runCount() {
        try {
            return db.countBatchRuns();
        } catch (SQLException e) {
            throw FacadeException.internal("could not count analysis runs", e);
        }
    }

    /** Re-runs a previous run with the same settings. */
    public BatchRun rerun(int runId, Consumer<Progress> onProgress) {
        BatchRun previous = getRun(runId);
        Request req = new Request(Template.fromLabel(previous.template()))
                .priority(Priority.fromLabel(previous.priority()))
                .errorHandling(ErrorHandling.fromLabel(previous.errorHandling()))
                .source(previous.sourceId())
                .aspect(previous.aspectId())
                .fileType(previous.fileType());
        return run(req, onProgress);
    }

    private static BatchRun toRun(CorpusDatabase.Row r, List<BatchRun.ItemOutcome> items) {
        return new BatchRun(
                r.i("id"), r.str("template"), r.str("priority"), r.str("error_handling"),
                r.boxed("source_id"), r.str("source_name"),
                r.boxed("aspect_id"), r.str("aspect_name"),
                r.str("file_type"), r.str("state"),
                r.i("selected"), r.i("completed"), r.i("failed"),
                r.instant("started_at"), r.instant("finished_at"),
                r.l("millis"), r.str("note"), items);
    }

    /** Non-overlapping occurrence count. */
    public static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
