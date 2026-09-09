package com.aegis.fdx.facade;

import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.dto.ContentDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.facade.dto.PathDto;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.store.CorpusDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the file registry and the extracted content held against it.
 *
 * <p>This is the bridge between the processing engine and the five integrated
 * concepts. The ingest pipeline remains the sole reader and extractor of material —
 * nothing about reading, hashing, extraction or metadata is changed here. This facade
 * <em>registers</em> what the pipeline produced as {@code path} rows attributed to a
 * source and an aspect, with the extracted text stored as {@code content}.
 *
 * <p>Because the registry shares the case database with the engine's own tables, a
 * {@code path} references its originating element by foreign key and the two halves
 * can be queried together.
 */
public final class ContentFacade {

    /** @deprecated use {@link FileState#READ} */
    @Deprecated
    public static final String STATUS_READ = "Read";

    /** @deprecated use {@link FileState#UNREAD} */
    @Deprecated
    public static final String STATUS_UNREAD = "Unread";

    private final CorpusDatabase db;
    private final LiveCase liveCase;

    public ContentFacade(CorpusDatabase db, LiveCase liveCase) {
        this.db = db;
        this.liveCase = liveCase;
    }

    // ---- hashes ----------------------------------------------------------

    /** Records a content hash, returning the existing id when already present. */
    public int createHash(String hashValue, Integer sourceId) {
        String h = Validate.required(hashValue, "hashValue");
        try {
            return db.insertHash(h, sourceId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create hash", e);
        }
    }

    /** @return true if this hash is already recorded for the source */
    public boolean hashExists(String hashValue, int sourceId) {
        String h = Validate.required(hashValue, "hashValue");
        Validate.positiveId(sourceId, "sourceId");
        try {
            return db.hashExists(h, sourceId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to check hash", e);
        }
    }

    // ---- paths -----------------------------------------------------------

    /** Registers a file. Prefer {@link #registerIngestedItems} for pipeline output. */
    public int createPath(String fileName, String filePath, long fileSize, String fileType,
                          String fileStatus, LocalDate fileDate, LocalDate dateCreation,
                          Integer hashId, String coordinates,
                          Integer sourceId, Integer aspectId, String elementId) {
        String n = Validate.required(fileName, "fileName");
        String p = Validate.required(filePath, "filePath");
        String t = Validate.required(fileType, "fileType");
        if (fileSize < 0) {
            throw FacadeException.validation("file_size must not be negative");
        }
        String st = normaliseStatus(fileStatus);
        LocalDate fd = fileDate == null ? LocalDate.now() : fileDate;
        LocalDate dc = dateCreation == null ? LocalDate.now() : dateCreation;
        try {
            return db.insertPath(n, p, fileSize, t, st, fd, dc, hashId, coordinates,
                    sourceId, aspectId, elementId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create path", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such path exists */
    public PathDto getPath(int pathId) {
        Validate.positiveId(pathId, "pathId");
        try {
            CorpusDatabase.Row r = db.selectPathById(pathId);
            if (r == null) {
                throw FacadeException.notFound("path", pathId);
            }
            return toPath(r);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load path", e);
        }
    }

    /**
     * Resolves a registered file by its forensic element id.
     *
     * <p>This is the navigation behind a search result: the index answers which
     * <em>items</em> match ({@code E-000001}, extracted children {@code E-000001-E1}),
     * and every registered file carries that id in {@code path.element_id}. The lookup
     * is a single indexed query ({@code ix_path_element}); it never scans names, never
     * depends on row position, and cannot confuse two files that share a name.
     *
     * @throws FacadeException NOT_FOUND if no registered file carries that element id,
     *         for example because the result predates registration or the record was
     *         deleted; callers must report that to the operator, never crash
     */
    public PathDto getPathByElementId(String elementId) {
        String id = Validate.required(elementId, "elementId");
        try {
            Integer pathId = db.findPathIdByElement(id);
            if (pathId == null) {
                throw FacadeException.notFound("path", id);
            }
            return getPath(pathId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to resolve element " + id, e);
        }
    }

    /** Registered files matching the given filters. */
    public Page<PathDto> getPaths(String fileType, Integer sourceId, Integer aspectId,
                                  String status, int limit, int offset) {
        int lim = Validate.limit(limit);
        int off = Validate.offset(offset);
        try {
            List<CorpusDatabase.Row> rows =
                    db.selectPaths(fileType, sourceId, aspectId, status, lim, off);
            List<PathDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(toPath(r));
            }
            return new Page<>(out, db.countPaths(fileType, sourceId, aspectId, status));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list paths", e);
        }
    }

    public Page<PathDto> getPaths() {
        return getPaths(null, null, null, null, 100, 0);
    }

    /** Marks a file read or unread. */
    public boolean setPathStatus(int pathId, String status) {
        Validate.positiveId(pathId, "pathId");
        try {
            return db.updatePathStatus(pathId, normaliseStatus(status));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to update path status", e);
        }
    }

    public boolean deletePath(int pathId) {
        Validate.positiveId(pathId, "pathId");
        try {
            return db.deletePath(pathId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete path", e);
        }
    }

    // ---- contents --------------------------------------------------------

    /** Stores an extracted text payload against a path. */
    public int createContent(String contentData, LocalDate contentDate, int pathId) {
        Validate.positiveId(pathId, "pathId");
        try {
            return db.insertContent(contentData, contentDate, pathId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create content", e);
        }
    }

    /** All content for a path, concatenated. */
    public String getContentAsText(int pathId) {
        Validate.positiveId(pathId, "pathId");
        try {
            StringBuilder sb = new StringBuilder();
            for (CorpusDatabase.Row r : db.selectContentsByPath(pathId)) {
                String d = r.str("content_data");
                if (d != null) {
                    sb.append(d);
                }
            }
            return sb.toString();
        } catch (SQLException e) {
            throw FacadeException.internal("failed to read content", e);
        }
    }

    /**
     * Native bytes of the registered file, for display purposes (image views).
     *
     * @return the bytes, or null when the source cannot be read (moved file,
     *         nested item whose registered path is its container, permissions);
     *         callers fall back to the extracted text
     * @throws FacadeException NOT_FOUND if no such path exists
     */
    public byte[] getNativeBytes(int pathId) {
        PathDto p = getPath(pathId);
        try {
            if (p.filePath() == null) {
                return null;
            }
            Path f = Path.of(p.filePath());
            if (!Files.isReadable(f)) {
                return null;
            }
            return Files.readAllBytes(f);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether a file name points at a displayable image (case-insensitive). */
    public static boolean isImageFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return PreviewFacade.isImageExtension(fileName.substring(dot + 1));
    }

    /** Content payloads for a path, in insertion order. */
    public List<ContentDto> getContents(int pathId) {
        Validate.positiveId(pathId, "pathId");
        try {
            List<ContentDto> out = new ArrayList<>();
            for (CorpusDatabase.Row r : db.selectContentsByPath(pathId)) {
                out.add(new ContentDto(r.i("id"), r.str("content_data"),
                        r.date("content_date"), r.i("path_id")));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list contents", e);
        }
    }

    public boolean deleteContent(int contentId) {
        Validate.positiveId(contentId, "contentId");
        try {
            return db.deleteContent(contentId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete content", e);
        }
    }

    // ---- integration -----------------------------------------------------

    /**
     * Registers everything the pipeline has ingested into the file registry, attributing
     * each element to the given source and aspect.
     *
     * <p>This is the integration point: the engine still does all reading, extraction,
     * hashing and metadata work, and this method projects its results into
     * {@code hash → path → content} so the registry describes real processed material
     * rather than a parallel dataset.
     *
     * <p>Idempotent: an element already registered (matched on {@code element_id}) is
     * skipped, so it is safe to call after every ingest.
     *
     * @return number of newly registered elements
     */
    public int registerIngestedItems(Integer sourceId, Integer aspectId) {
        if (liveCase == null) {
            throw FacadeException.unsupported(
                    "registerIngestedItems requires a LiveCase-backed ContentFacade");
        }
        try {
            int created = 0;
            for (Item it : liveCase.allItems()) {
                if (db.findPathIdByElement(it.id()) != null) {
                    continue;
                }
                Integer hashId = null;
                if (it.sha256() != null && !it.sha256().isBlank()) {
                    hashId = db.insertHash(it.sha256(), sourceId);
                }
                LocalDate fileDate = it.modified() == null
                        ? LocalDate.now()
                        : it.modified().atZone(ZoneId.systemDefault()).toLocalDate();

                int pathId = db.insertPath(
                        nz(it.name()),
                        nz(it.sourcePath()),
                        Math.max(0L, it.size()),
                        it.extension() == null || it.extension().isBlank()
                                ? "unknown" : it.extension().toLowerCase(),
                        STATUS_UNREAD,
                        fileDate,
                        LocalDate.now(),
                        hashId,
                        it.geoLocation(),
                        sourceId,
                        aspectId,
                        it.id());

                String text = readText(it.id());
                if (text != null && !text.isBlank()) {
                    db.insertContent(text, fileDate, pathId);
                    // The reference derives word and keyword edges while it stores the
                    // content; doing it here keeps counts real from the first screen on.
                    new RelationshipAnalyzer(db, this).analyzeFile(pathId);
                }
                created++;
            }
            return created;
        } catch (Exception e) {
            throw FacadeException.internal("failed to register ingested items", e);
        }
    }

    private String readText(String itemId) {
        try {
            return liveCase.folder().readText(itemId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normaliseStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_UNREAD;
        }
        String s = status.trim();
        if (STATUS_READ.equalsIgnoreCase(s)) {
            return STATUS_READ;
        }
        if (STATUS_UNREAD.equalsIgnoreCase(s)) {
            return STATUS_UNREAD;
        }
        throw FacadeException.validation("file_status must be 'Read' or 'Unread'");
    }

    /** Shared row mapper, used by {@link AnalyticsFacade} for its own joins. */
    static PathDto rowToPath(CorpusDatabase.Row r) {
        return toPath(r);
    }

    private static PathDto toPath(CorpusDatabase.Row r) {
        return new PathDto(
                r.i("id"), r.str("file_name"), r.str("file_path"), r.l("file_size"),
                r.str("file_type"), r.str("file_status"),
                r.date("file_date"), r.date("date_creation"),
                r.boxed("hash_id"), r.str("hash_value"), r.str("coordinates"),
                r.boxed("source_id"), r.str("source_name"),
                r.boxed("aspect_id"), r.str("aspect_name"), r.str("element_id"));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
