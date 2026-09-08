package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages sources: the people, organisations or systems that material originates from.
 *
 * <p>A source carries identifying details (name, job, country, city), a relative
 * importance, provenance notes and an access status. Together with an
 * {@link AspectFacade} aspect, a source forms the mandatory storage attribution
 * recorded against every file taken through the processing pipeline.
 *
 * <p>The descriptive fields are optional and are supplied through
 * {@link SourceDraft} rather than a long positional parameter list.
 */
public final class SourceFacade {

    private final CorpusDatabase db;

    public SourceFacade(CorpusDatabase db) {
        this.db = db;
    }

    /** All sources, ordered by id. */
    public List<SourceDto> listSources() {
        try {
            Map<Integer, String> pairs = db.selectAllSources();
            List<SourceDto> out = new ArrayList<>(pairs.size());
            for (Map.Entry<Integer, String> e : pairs.entrySet()) {
                SourceDto full = getSource(e.getKey());
                out.add(full);
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list sources", e);
        }
    }

    /** Creates a source with only the required details. */
    public int createSource(String name, String country, String job, double importance) {
        return createSource(new SourceDraft(name, country, job, importance));
    }

    /**
     * Creates a source from a draft.
     *
     * @return the generated source id
     * @throws FacadeException VALIDATION for missing/out-of-range fields,
     *         CONFLICT if the name is already taken
     */
    public int createSource(SourceDraft draft) {
        if (draft == null) {
            throw FacadeException.validation("draft is required");
        }
        return createSource(draft.name(), draft.country(), draft.job(), draft.importance(),
                draft.city(), draft.description(), draft.accounts(), draft.note(),
                draft.attachments(), draft.ownership(), draft.accessStatus(),
                draft.entryDate(), draft.categoryId());
    }

    private int createSource(String name, String country, String job, double importance,
                            String city, String description, String accounts, String note,
                            String attachments, String ownership, String accessStatus,
                            LocalDate entryDate, Integer categoryId) {
        String n = Validate.required(name, "name");
        String c = Validate.required(country, "country");
        String j = Validate.required(job, "job");
        double imp = Validate.importance(importance);
        LocalDate when = entryDate == null ? LocalDate.now() : entryDate;
        try {
            if (db.sourceNameExists(n)) {
                throw FacadeException.conflict("source already exists: " + n);
            }
            return db.insertSource(n, c, j, imp,
                    Validate.optional(city), Validate.optional(description),
                    Validate.optional(accounts), Validate.optional(note),
                    Validate.optional(attachments), Validate.optional(ownership),
                    Validate.optional(accessStatus), when, categoryId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create source", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such source exists */
    public SourceDto getSource(int sourceId) {
        Validate.positiveId(sourceId, "sourceId");
        try {
            CorpusDatabase.Row r = db.selectSourceById(sourceId);
            if (r == null) {
                throw FacadeException.notFound("source", sourceId);
            }
            return toDto(r);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load source", e);
        }
    }

    /**
     * Updates a source from a draft.
     *
     * @throws FacadeException NOT_FOUND if it does not exist, CONFLICT if the new name
     *         is already taken by another source
     */
    public boolean updateSource(int sourceId, SourceDraft draft) {
        Validate.positiveId(sourceId, "sourceId");
        if (draft == null) {
            throw FacadeException.validation("draft is required");
        }
        String n = Validate.required(draft.name(), "name");
        String c = Validate.required(draft.country(), "country");
        String j = Validate.required(draft.job(), "job");
        double imp = Validate.importance(draft.importance());
        try {
            if (db.selectSourceById(sourceId) == null) {
                throw FacadeException.notFound("source", sourceId);
            }
            if (db.sourceNameTaken(n, sourceId)) {
                throw FacadeException.conflict("another source is already called " + n);
            }
            return db.updateSource(sourceId, n, c, j, imp,
                    Validate.optional(draft.city()), Validate.optional(draft.description()),
                    Validate.optional(draft.accounts()), Validate.optional(draft.note()),
                    Validate.optional(draft.attachments()), Validate.optional(draft.ownership()),
                    Validate.optional(draft.accessStatus()),
                    draft.entryDate() == null ? LocalDate.now() : draft.entryDate(),
                    draft.categoryId());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to update source", e);
        }
    }

    /** @return true if a row was removed */
    public boolean deleteSource(int sourceId) {
        Validate.positiveId(sourceId, "sourceId");
        try {
            return db.deleteSource(sourceId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete source", e);
        }
    }

    /** Copies a source under a non-clashing name. */
    public int duplicateSource(int sourceId) {
        SourceDto src = getSource(sourceId);
        String copyName = src.name() + " (copy)";
        try {
            int suffix = 2;
            while (db.sourceNameExists(copyName)) {
                copyName = src.name() + " (copy " + suffix++ + ")";
            }
        } catch (SQLException e) {
            throw FacadeException.internal("failed to duplicate source", e);
        }
        return createSource(copyName, src.country(), src.job(), src.importance(),
                src.city(), src.description(), src.accounts(), src.note(),
                src.attachments(), src.ownership(), src.accessStatus(),
                src.entryDate(), src.categoryId());
    }

    private static SourceDto toDto(CorpusDatabase.Row r) {
        return new SourceDto(
                r.i("id"),
                r.str("name"),
                r.str("country"),
                r.str("job"),
                r.d("importance"),
                r.str("city"),
                r.str("description"),
                r.str("accounts"),
                r.str("note"),
                r.str("attachments"),
                r.str("ownership"),
                r.str("access_status"),
                r.date("entry_date"),
                r.boxed("category_id"));
    }
}
