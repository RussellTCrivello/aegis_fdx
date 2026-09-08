package com.aegis.fdx.facade;

import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.store.CorpusDatabase;

/**
 * Single entry point to the application's interface layer.
 *
 * <p>Hands out every facade, already bound to a live case. The five integrated
 * concepts — sources, aspects, categories, keywords and contents — are stored in the
 * case's own database alongside the engine's tables, so this object needs no
 * connection or file of its own.
 *
 * <p>Nothing in the existing engine API changes: a caller that wants the low-level
 * interface keeps using {@link LiveCase} directly, and both views operate on the same
 * case data.
 *
 * <pre>{@code
 * try (LiveCase c = new LiveCase(root, "Matter-1", settings)) {
 *     AegisFacades f = AegisFacades.open(c);
 *     int sourceId = f.sources().createSource("Acme", "NL", "custodian", 0.8);
 *     int aspectId = f.aspects().createAspect("Plaintiff", 0.9);
 *     f.processing("Acme", "Plaintiff").processFolder("/evidence");
 *     f.contents().registerIngestedItems(sourceId, aspectId);
 *     Page<SearchResultDto> hits = f.search().search("invoice");
 * }
 * }</pre>
 */
public final class AegisFacades {

    private final LiveCase liveCase;

    private final SourceFacade sources;
    private final AspectFacade aspects;
    private final WordFacade words;
    private final CategoryFacade categories;
    private final KeywordFacade keywords;
    private final NotificationFacade notifications;
    private final SearchHistoryFacade history;
    private final SearchFacade search;
    private final PreviewFacade preview;
    private final DashboardFacade dashboard;
    private final ContentFacade contents;
    private final AnalyticsFacade analytics;
    private final BatchAnalysisFacade batch;
    private final RelationshipFacade relationships;
    private final RelationshipAnalyzer relationshipAnalyzer;
    private final RelationshipIntegrity relationshipIntegrity;

    private AegisFacades(LiveCase liveCase, CorpusDatabase corpus) {
        this.liveCase = liveCase;
        this.sources = new SourceFacade(corpus);
        this.aspects = new AspectFacade(corpus);
        this.words = new WordFacade(corpus);
        this.categories = new CategoryFacade(corpus);
        this.keywords = new KeywordFacade(corpus);
        this.notifications = new NotificationFacade(corpus);
        this.history = new SearchHistoryFacade(corpus);
        this.search = new SearchFacade(liveCase, sources, aspects);
        this.preview = new PreviewFacade(liveCase);
        this.dashboard = new DashboardFacade(liveCase);
        this.contents = new ContentFacade(corpus, liveCase);
        this.analytics = new AnalyticsFacade(corpus, liveCase);
        this.batch = new BatchAnalysisFacade(corpus, contents, keywords, categories);
        this.relationships = new RelationshipFacade(corpus);
        this.relationshipAnalyzer = new RelationshipAnalyzer(corpus, contents);
        this.relationshipIntegrity = new RelationshipIntegrity(corpus, relationships);
    }

    /**
     * Opens the interface layer over a case.
     *
     * <p>Uses the case's existing database connection, so the integrated concepts share
     * its file, transaction scope and lifecycle.
     */
    public static AegisFacades open(LiveCase liveCase) {
        if (liveCase == null) {
            throw FacadeException.validation("liveCase is required");
        }
        return new AegisFacades(liveCase, new CorpusDatabase(liveCase.db()));
    }

    /** Sources: who or what material originated from. */
    public SourceFacade sources() {
        return aspectsAwareSources();
    }

    private SourceFacade aspectsAwareSources() {
        return sources;
    }

    /** Aspects: the party or grouping material is attributed to. */
    public AspectFacade aspects() {
        return aspects;
    }

    /**
     * @deprecated this concept is now called an aspect; use {@link #aspects()}
     */
    @Deprecated(since = "1.1", forRemoval = false)
    public SideFacade sides() {
        return new SideFacade(aspects);
    }

    /** Vocabulary. */
    public WordFacade words() {
        return words;
    }

    /** Categories: named groupings of vocabulary. */
    public CategoryFacade categories() {
        return categories;
    }

    /** Keywords: phrases of interest within a category. */
    public KeywordFacade keywords() {
        return keywords;
    }

    /** Notifications and scheduled events. */
    public NotificationFacade notifications() {
        return notifications;
    }

    /** Search history and saved searches. */
    public SearchHistoryFacade history() {
        return history;
    }

    /** Full-text search. */
    public SearchFacade search() {
        return search;
    }

    /** Element previews. */
    public PreviewFacade preview() {
        return preview;
    }

    /** Aggregated figures for the overview and analysis screens. */
    public DashboardFacade dashboard() {
        return dashboard;
    }

    /** The file registry and extracted content. */
    public ContentFacade contents() {
        return contents;
    }

    /** Rollups behind the detail, relationship and analytics destinations. */
    public AnalyticsFacade analytics() {
        return analytics;
    }

    /** Batch analysis runs and their persisted history. */
    public BatchAnalysisFacade batch() {
        return batch;
    }

    /** File ↔ keyword ↔ category ↔ category word, with whole-case counts and search. */
    public RelationshipFacade relationships() {
        return relationships;
    }

    /** Walks the graph from both ends and reports any disagreement. */
    public RelationshipIntegrity relationshipIntegrity() {
        return relationshipIntegrity;
    }

    /** Re-derives the file ↔ word and file ↔ keyword edges from stored content. */
    public RelationshipAnalyzer relationshipAnalyzer() {
        return relationshipAnalyzer;
    }

    /**
     * A processing session attributed to a source and an aspect.
     *
     * <p>Both attributions are mandatory, so they are supplied per call rather than
     * being cached on this object.
     */
    public FileProcessingFacade processing(String sourceName, String aspectName) {
        return new FileProcessingFacade(liveCase, sourceName, aspectName);
    }

    /**
     * Processing operations that act on elements already in the case, such as a retry.
     *
     * <p>Source and aspect are attributes of new material; an element that is already
     * registered keeps the ones it was ingested with, so this session states plainly
     * that it is not adding anything under a new heading.
     */
    public FileProcessingFacade processing() {
        return new FileProcessingFacade(liveCase, "(existing element)", "(existing element)");
    }

    /** Import operations that need the processing pipeline. */
    public ImportFacade imports(String sourceName, String aspectName) {
        return new ImportFacade(processing(sourceName, aspectName));
    }

    /** Import operations limited to backup and settings validation. */
    public ImportFacade imports() {
        return new ImportFacade();
    }

    /** The underlying case, for callers mixing both interfaces. */
    public LiveCase liveCase() {
        return liveCase;
    }
}
