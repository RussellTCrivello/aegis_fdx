package com.aegis.fdx.ai.tools;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.FileState;
import com.aegis.fdx.store.CorpusDatabase;

import java.util.List;

/**
 * Tools that change application state.
 *
 * <p>Registered only when the operator has explicitly confirmed, via
 * {@link AgentContext#allowingMutations()}. Without that, the agent cannot reach them
 * at all, so asking a question can never alter the case.
 *
 * <p>Even here, nothing touches original material: these tools write review
 * annotations — classification and read state — not evidence. Ingested bytes, hashes
 * and extracted text stay immutable.
 */
public final class MutatingTools {

    private final AegisFacades facades;
    private final CorpusDatabase dao;

    public MutatingTools(AegisFacades facades, CorpusDatabase dao) {
        this.facades = facades;
        this.dao = dao;
    }

    public List<AgentTool> all() {
        return List.of(classifyTool(), markReviewedTool());
    }

    /** Attaches a category to a registered file. */
    private AgentTool classifyTool() {
        return new CaseTools.Tool(
                ToolSchema.mutating("classify_file",
                        "Attach a category to a registered file. Annotates review "
                                + "classification only; it does not alter the file.",
                        ToolSchema.Param.required("pathId", "integer", "registry file id"),
                        ToolSchema.Param.required("categoryId", "integer", "category id")),
                req -> {
                    if (!req.context().allowMutations()) {
                        return ToolResult.failure(
                                "not permitted: this action changes data and the operator "
                                        + "has not confirmed it");
                    }
                    try {
                        int pathId = req.getInt("pathId", -1);
                        int catId = req.getInt("categoryId", -1);
                        boolean added = dao.linkPathToCategory(pathId, catId);
                        var p = facades.contents().getPath(pathId);
                        return ToolResult.ok(added
                                        ? "Classified " + p.fileName() + " under category " + catId + "."
                                        : "That classification was already present.",
                                List.of(ToolResult.Evidence.content(pathId, p.fileName()),
                                        ToolResult.Evidence.category(catId, String.valueOf(catId))));
                    } catch (FacadeException e) {
                        return ToolResult.failure(e.getMessage());
                    } catch (Exception e) {
                        return ToolResult.failure("classification failed: " + e.getMessage());
                    }
                });
    }

    /** Sets the review state of a registered file. */
    private AgentTool markReviewedTool() {
        return new CaseTools.Tool(
                ToolSchema.mutating("set_review_state",
                        "Mark a registered file Read or Unread. Review bookkeeping only.",
                        ToolSchema.Param.required("pathId", "integer", "registry file id"),
                        ToolSchema.Param.required("state", "string", "Read or Unread")),
                req -> {
                    if (!req.context().allowMutations()) {
                        return ToolResult.failure(
                                "not permitted: this action changes data and the operator "
                                        + "has not confirmed it");
                    }
                    try {
                        int pathId = req.getInt("pathId", -1);
                        FileState state = FileState.fromLabel(req.get("state"));
                        boolean ok = facades.contents().setPathStatus(pathId, state.label());
                        var p = facades.contents().getPath(pathId);
                        return ToolResult.ok(ok
                                        ? "Marked " + p.fileName() + " as " + state.label() + "."
                                        : "No change was made.",
                                List.of(ToolResult.Evidence.content(pathId, p.fileName())));
                    } catch (FacadeException e) {
                        return ToolResult.failure(e.getMessage());
                    }
                });
    }
}
