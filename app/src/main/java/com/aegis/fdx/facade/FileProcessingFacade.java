package com.aegis.fdx.facade;

import com.aegis.fdx.engine.IngestPipeline;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.dto.ProcessingResultDto;
import com.aegis.fdx.facade.dto.StatisticsDto;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Runs material through the processing pipeline and reports what happened to it.
 *
 * <p>Every session is attributed to a source and an aspect; both are mandatory and
 * validated on construction. The source name is also used as the pipeline's custodian,
 * which is the field it already partitions by.
 *
 * <p><strong>Nothing is silently dropped.</strong> Every path submitted yields a
 * {@link ProcessingResultDto}, including unreadable, missing and failing files, which
 * come back with {@code success == false} and a populated error rather than throwing.
 */
public final class FileProcessingFacade implements AutoCloseable {

    private final LiveCase liveCase;
    private final String storageSource;
    private final String storageSide;
    private final boolean enableStorage;

    /** A session with storage enabled. */
    public FileProcessingFacade(LiveCase liveCase, String storageSource, String storageSide) {
        this(liveCase, storageSource, storageSide, true);
    }

    /**
     * @param storageSource the originating source (required)
     * @param storageSide   the aspect the material belongs to (required)
     * @param enableStorage whether results are persisted
     */
    public FileProcessingFacade(LiveCase liveCase, String storageSource, String storageSide,
                                boolean enableStorage) {
        if (liveCase == null) {
            throw FacadeException.validation("liveCase is required");
        }
        this.liveCase = liveCase;
        this.storageSource = Validate.required(storageSource, "storageSource");
        this.storageSide = Validate.required(storageSide, "storageSide");
        this.enableStorage = enableStorage;
    }

    /**
     * Processes one file.
     *
     * <p>Never throws for an inaccessible path; the failure is reported in the result.
     * A null or blank argument is still a validation error.
     */
    public ProcessingResultDto processSingleFile(String filePath) {
        String p = Validate.required(filePath, "filePath");
        Path path = Path.of(p);
        String fileName = path.getFileName() == null ? p : path.getFileName().toString();
        String ext = extensionOf(fileName);

        if (!Files.exists(path)) {
            return new ProcessingResultDto(p, fileName, ext, 0L, false,
                    "file does not exist", enableStorage, false, null, 0);
        }
        if (!Files.isReadable(path)) {
            return new ProcessingResultDto(p, fileName, ext, 0L, false,
                    "permission denied", enableStorage, false, null, 0);
        }

        long size;
        try {
            size = Files.size(path);
        } catch (Exception e) {
            size = 0L;
        }

        try {
            Future<IngestPipeline.Result> f = liveCase.startIngest(path, storageSource, ev -> { });
            IngestPipeline.Result r = f.get();
            boolean ok = r.errors() == 0;
            Item stored = findBySourcePath(p);
            return new ProcessingResultDto(
                    p, fileName, ext, size, ok,
                    ok ? null : "processing reported " + r.errors() + " error(s)",
                    enableStorage,
                    r.duplicates() > 0,
                    stored == null ? null : stored.sha256(),
                    stored == null ? 0 : (int) Math.min(Integer.MAX_VALUE, stored.size()));
        } catch (Exception e) {
            return new ProcessingResultDto(p, fileName, ext, size, false,
                    String.valueOf(e.getMessage()), enableStorage, false, null, 0);
        }
    }

    /**
     * Tries one element again, through the same pipeline that first processed it.
     *
     * <p>For the error views: a document that was locked, a share that dropped, a read
     * that timed out. The element keeps its identifier and the reviewer's notes and
     * tags; everything derived from the file is recomputed. Like the rest of this
     * facade it reports a failure in the result rather than throwing.
     *
     * @param elementId the element to run again, e.g. {@code E-000004-E1}
     */
    public ProcessingResultDto retryFile(String elementId) {
        String id = Validate.required(elementId, "elementId");
        try {
            Item before = liveCase.byId(id);
            if (before == null) {
                return new ProcessingResultDto(null, id, "", 0L, false,
                        "no element " + id + " in this case", enableStorage, false, null, 0);
            }
            Item after = liveCase.retryElement(id, ev -> { }).get();
            boolean ok = after.status() == ItemStatus.INDEXED;
            return new ProcessingResultDto(
                    after.sourcePath(),
                    after.name(),
                    after.extension(),
                    after.size(),
                    ok,
                    ok ? null : (after.errors().isEmpty()
                            ? String.valueOf(after.status())
                            : String.join("; ", after.errors())),
                    enableStorage,
                    after.duplicateOf() != null,
                    after.sha256(),
                    after.extractedText() == null ? 0 : after.extractedText().length());
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new ProcessingResultDto(null, id, "", 0L, false,
                    String.valueOf(cause.getMessage()), enableStorage, false, null, 0);
        }
    }

    /** Processes a folder recursively, returning one entry per discovered element. */
    public List<ProcessingResultDto> processFolder(String folderPath) {        String p = Validate.required(folderPath, "folderPath");
        Path dir = Path.of(p);

        if (!Files.exists(dir)) {
            return List.of(new ProcessingResultDto(p, nameOf(dir), "", 0L, false,
                    "folder does not exist", false, false, null, 0));
        }
        if (!Files.isDirectory(dir)) {
            return List.of(new ProcessingResultDto(p, nameOf(dir), "", 0L, false,
                    "path is not a directory", false, false, null, 0));
        }

        try {
            Future<IngestPipeline.Result> f = liveCase.startIngest(dir, storageSource, ev -> { });
            f.get();
            List<ProcessingResultDto> out = new ArrayList<>();
            for (Item it : liveCase.allItems()) {
                boolean ok = it.status() == ItemStatus.INDEXED;
                out.add(new ProcessingResultDto(
                        it.sourcePath(),
                        it.name(),
                        it.extension(),
                        it.size(),
                        ok,
                        ok ? null : String.valueOf(it.status()),
                        enableStorage,
                        false,
                        it.sha256(),
                        (int) Math.min(Integer.MAX_VALUE, it.size())));
            }
            return out;
        } catch (Exception e) {
            // A tree-read failure still emits an error entry rather than vanishing.
            return List.of(new ProcessingResultDto(p, nameOf(dir), "", 0L, false,
                    String.valueOf(e.getMessage()), false, false, null, 0));
        }
    }

    /** Counters for what the pipeline has processed. */
    public StatisticsDto getStatistics() {
        try {
            Map<ItemStatus, Integer> counts = liveCase.statusCounts();
            long total = 0;
            for (int v : counts.values()) {
                total += v;
            }
            long completed = counts.getOrDefault(ItemStatus.INDEXED, 0);
            long failed = counts.getOrDefault(ItemStatus.ERROR, 0);
            long pending = counts.getOrDefault(ItemStatus.PENDING, 0);

            Map<String, Long> extra = new LinkedHashMap<>();
            extra.put("locked", (long) counts.getOrDefault(ItemStatus.LOCKED, 0));
            extra.put("unsupported", (long) counts.getOrDefault(ItemStatus.UNSUPPORTED, 0));
            extra.put("processing", (long) counts.getOrDefault(ItemStatus.PROCESSING, 0));

            return new StatisticsDto(total, completed, failed,
                    liveCase.duplicateClusters().size(), pending, extra);
        } catch (Exception e) {
            throw FacadeException.internal("failed to read statistics", e);
        }
    }

    /** Counters for what reached durable storage and the index. */
    public StatisticsDto getStorageStatistics() {
        try {
            long indexed = liveCase.indexedCount();
            Map<ItemStatus, Integer> counts = liveCase.statusCounts();
            long total = 0;
            for (int v : counts.values()) {
                total += v;
            }
            long duplicates = liveCase.duplicateClusters().size();
            Map<String, Long> extra = new LinkedHashMap<>();
            extra.put("indexed", indexed);
            return new StatisticsDto(total, indexed,
                    counts.getOrDefault(ItemStatus.ERROR, 0), duplicates, 0L, extra);
        } catch (Exception e) {
            throw FacadeException.internal("failed to read storage statistics", e);
        }
    }

    /** Pauses the running session between elements. */
    public void pause() {
        liveCase.pauseIngest();
    }

    public void resume() {
        liveCase.resumeIngest();
    }

    public void cancel() {
        liveCase.cancelIngest();
    }

    public boolean isRunning() {
        return liveCase.ingestRunning();
    }

    /** Releases session resources; the case itself is owned by the caller. */
    @Override
    public void close() {
        // The LiveCase is owned by the caller: closing the facade must not close it,
        // the case is owned by the caller.
    }

    private Item findBySourcePath(String sourcePath) {
        try {
            for (Item it : liveCase.allItems()) {
                if (sourcePath.equals(it.sourcePath())) {
                    return it;
                }
            }
        } catch (Exception ignored) {
            // best-effort enrichment only
        }
        return null;
    }

    private static String nameOf(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
