package com.aegis.fdx.ai.tools;

/**
 * What the operator is looking at when they ask the agent something.
 *
 * <p>Populated by whichever screen launched the request, so "summarise this source"
 * resolves without the operator copying identifiers into a prompt.
 */
public record AgentContext(
        String screen,
        String elementId,
        Integer sourceId,
        Integer aspectId,
        Integer categoryId,
        Integer keywordId,
        Integer pathId,
        String searchQuery,
        boolean allowMutations) {

    public static AgentContext empty() {
        return new AgentContext(null, null, null, null, null, null, null, null, false);
    }

    public static AgentContext ofScreen(String screen) {
        return new AgentContext(screen, null, null, null, null, null, null, null, false);
    }

    public AgentContext withElement(String id) {
        return new AgentContext(screen, id, sourceId, aspectId, categoryId, keywordId,
                pathId, searchQuery, allowMutations);
    }

    public AgentContext withSource(Integer id) {
        return new AgentContext(screen, elementId, id, aspectId, categoryId, keywordId,
                pathId, searchQuery, allowMutations);
    }

    public AgentContext withAspect(Integer id) {
        return new AgentContext(screen, elementId, sourceId, id, categoryId, keywordId,
                pathId, searchQuery, allowMutations);
    }

    public AgentContext withCategory(Integer id) {
        return new AgentContext(screen, elementId, sourceId, aspectId, id, keywordId,
                pathId, searchQuery, allowMutations);
    }

    public AgentContext withKeyword(Integer id) {
        return new AgentContext(screen, elementId, sourceId, aspectId, categoryId, id,
                pathId, searchQuery, allowMutations);
    }

    public AgentContext withPath(Integer id) {
        return new AgentContext(screen, elementId, sourceId, aspectId, categoryId,
                keywordId, id, searchQuery, allowMutations);
    }

    public AgentContext withQuery(String query) {
        return new AgentContext(screen, elementId, sourceId, aspectId, categoryId,
                keywordId, pathId, query, allowMutations);
    }

    /**
     * Grants permission for state-changing tools.
     *
     * <p>Off unless the operator explicitly confirms, so an ordinary question can never
     * modify the case.
     */
    public AgentContext allowingMutations() {
        return new AgentContext(screen, elementId, sourceId, aspectId, categoryId,
                keywordId, pathId, searchQuery, true);
    }

    public boolean isEmpty() {
        return screen == null && elementId == null && sourceId == null && aspectId == null
                && categoryId == null && keywordId == null && pathId == null
                && (searchQuery == null || searchQuery.isBlank());
    }

    /** Rendered into the system prompt so the model knows the situation. */
    public String toPromptBlock() {
        if (isEmpty()) {
            return "No specific record is open.";
        }
        StringBuilder sb = new StringBuilder("The operator is currently viewing:\n");
        if (screen != null) {
            sb.append("  screen: ").append(screen).append('\n');
        }
        if (elementId != null) {
            sb.append("  item id: ").append(elementId).append('\n');
        }
        if (sourceId != null) {
            sb.append("  source id: ").append(sourceId).append('\n');
        }
        if (aspectId != null) {
            sb.append("  aspect id: ").append(aspectId).append('\n');
        }
        if (categoryId != null) {
            sb.append("  category id: ").append(categoryId).append('\n');
        }
        if (keywordId != null) {
            sb.append("  keyword id: ").append(keywordId).append('\n');
        }
        if (pathId != null) {
            sb.append("  file (path) id: ").append(pathId).append('\n');
        }
        if (searchQuery != null && !searchQuery.isBlank()) {
            sb.append("  active search: ").append(searchQuery).append('\n');
        }
        return sb.toString();
    }
}
