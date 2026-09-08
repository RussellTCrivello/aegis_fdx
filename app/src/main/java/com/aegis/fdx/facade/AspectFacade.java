package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages aspects: the parties, groupings or viewpoints that ingested material is
 * attributed to.
 *
 * <p>An aspect carries a name, a relative importance (0.0-1.0) and a creation date.
 * Together with a {@link SourceFacade} source, an aspect forms the mandatory storage
 * attribution recorded against every file taken through the processing pipeline.
 */
public final class AspectFacade {

    private final CorpusDatabase db;

    public AspectFacade(CorpusDatabase db) {
        this.db = db;
    }

    /** All aspects, ordered by id. */
    public List<AspectDto> listAspects() {
        try {
            Map<Integer, String> pairs = db.selectAllAspects();
            List<AspectDto> out = new ArrayList<>(pairs.size());
            for (Integer id : pairs.keySet()) {
                out.add(getAspect(id));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list aspects", e);
        }
    }

    /** Creates an aspect dated today. */
    public int createAspect(String name, double importance) {
        return createAspect(name, importance, null);
    }

    public int createAspect(String name, double importance, LocalDate dateCreation) {
        String n = Validate.required(name, "name");
        double imp = Validate.importance(importance);
        LocalDate when = dateCreation == null ? LocalDate.now() : dateCreation;
        try {
            if (db.aspectNameExists(n)) {
                throw FacadeException.conflict("aspect already exists: " + n);
            }
            return db.insertAspect(n, imp, when);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create aspect", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such aspect exists */
    public AspectDto getAspect(int sideId) {
        Validate.positiveId(sideId, "aspectId");
        try {
            CorpusDatabase.Row r = db.selectAspectById(sideId);
            if (r == null) {
                throw FacadeException.notFound("aspect", sideId);
            }
            return new AspectDto(r.i("id"), r.str("name"), r.d("importance"),
                    r.date("date_creation"));
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load aspect", e);
        }
    }

    /**
     * Updates an aspect.
     *
     * @throws FacadeException NOT_FOUND if it does not exist, CONFLICT on a name clash
     */
    public boolean updateAspect(int aspectId, String name, double importance,
                                java.time.LocalDate dateCreation) {
        Validate.positiveId(aspectId, "aspectId");
        String n = Validate.required(name, "name");
        double imp = Validate.importance(importance);
        try {
            if (db.selectAspectById(aspectId) == null) {
                throw FacadeException.notFound("aspect", aspectId);
            }
            if (db.aspectNameTaken(n, aspectId)) {
                throw FacadeException.conflict("another aspect is already called " + n);
            }
            return db.updateAspect(aspectId, n, imp,
                    dateCreation == null ? java.time.LocalDate.now() : dateCreation);
        } catch (java.sql.SQLException e) {
            throw FacadeException.internal("failed to update aspect", e);
        }
    }


    /** @return true if a row was removed */
    public boolean deleteAspect(int sideId) {
        Validate.positiveId(sideId, "aspectId");
        try {
            return db.deleteAspect(sideId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to delete aspect", e);
        }
    }

    /** Copies an aspect under a non-clashing name. */
    public int duplicateAspect(int sideId) {
        AspectDto src = getAspect(sideId);
        String copyName = src.name() + " (copy)";
        try {
            int suffix = 2;
            while (db.aspectNameExists(copyName)) {
                copyName = src.name() + " (copy " + suffix++ + ")";
            }
        } catch (SQLException e) {
            throw FacadeException.internal("failed to duplicate aspect", e);
        }
        return createAspect(copyName, src.importance(), src.dateCreation());
    }
}
