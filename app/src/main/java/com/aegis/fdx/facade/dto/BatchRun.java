package com.aegis.fdx.facade.dto;

import java.time.Instant;
import java.util.List;

/**
 * One recorded batch-analysis run.
 *
 * <p>Persisted, so the history survives a restart and a previous run's outcome can be
 * inspected or repeated.
 */
public record BatchRun(
        int id,
        String template,
        String priority,
        String errorHandling,
        Integer sourceId,
        String sourceName,
        Integer aspectId,
        String aspectName,
        String fileType,
        String state,
        int selected,
        int completed,
        int failed,
        Instant startedAt,
        Instant finishedAt,
        long millis,
        String note,
        List<ItemOutcome> items) {

    /** What happened to one file within a run. */
    public record ItemOutcome(int pathId, String fileName, String fileType, long fileSize,
                              String outcome, String detail, long millis) {
        public boolean isFailure() {
            return "Failed".equals(outcome);
        }
    }

    public BatchRun {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Proportion of selected files that completed without failing. */
    public double successRate() {
        return selected == 0 ? 0d : (double) completed / selected;
    }

    /** Mean milliseconds per file. */
    public double averageMillis() {
        int done = completed + failed;
        return done == 0 ? 0d : (double) millis / done;
    }

    public boolean isFinished() {
        return !"Running".equals(state) && !"Queued".equals(state);
    }

    public boolean isClean() {
        return failed == 0 && "Completed".equals(state);
    }
}
