package com.aegis.fdx.ai.agent;

import com.aegis.fdx.ai.tools.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The audit trail for one agent run.
 *
 * <p>Records what was asked, which tools ran with which arguments, what they returned
 * and how long each took. This is deliberately a record of <em>operational decisions
 * and evidence</em>, not of the model's private reasoning: the value is being able to
 * check that an answer came from real records.
 */
public final class AgentActivity {

    /** One step in the run. */
    public record Step(int index, String tool, Map<String, String> arguments,
                       boolean success, String summary, int evidenceCount, long millis) {
    }

    private final String request;
    private final Instant started = Instant.now();
    private final List<Step> steps = new ArrayList<>();
    private final List<ToolResult.Evidence> evidence = new ArrayList<>();
    private String finalAnswer = "";
    private String failure;
    private long totalMillis;
    private int modelCalls;

    public AgentActivity(String request) {
        this.request = request;
    }

    public void addStep(String tool, Map<String, String> args, ToolResult result) {
        steps.add(new Step(steps.size() + 1, tool, args, result.success(),
                summarise(result), result.evidence().size(), result.millis()));
        for (ToolResult.Evidence e : result.evidence()) {
            if (!evidence.contains(e)) {
                evidence.add(e);
            }
        }
    }

    public void countModelCall() {
        modelCalls++;
    }

    public void finish(String answer, long millis) {
        this.finalAnswer = answer == null ? "" : answer;
        this.totalMillis = millis;
    }

    public void fail(String reason, long millis) {
        this.failure = reason;
        this.totalMillis = millis;
    }

    public String request() {
        return request;
    }

    public Instant started() {
        return started;
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public List<ToolResult.Evidence> evidence() {
        return List.copyOf(evidence);
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public String failure() {
        return failure;
    }

    public boolean failed() {
        return failure != null;
    }

    public long totalMillis() {
        return totalMillis;
    }

    public int modelCalls() {
        return modelCalls;
    }

    /**
     * True when the answer rests on data actually read from the case.
     *
     * <p>Either a tool cited specific records, or a tool succeeded and returned
     * measured facts. Aggregate tools such as statistics legitimately have no single
     * record to point at, so requiring a record identifier would wrongly flag a
     * correct, fully grounded answer as unsupported.
     */
    public boolean isGrounded() {
        if (!evidence.isEmpty()) {
            return true;
        }
        return steps.stream().anyMatch(s -> s.success() && s.evidenceCount() == 0
                && s.summary() != null && !s.summary().isBlank()
                && !s.summary().startsWith("No "));
    }

    /** Human-readable trace for the activity panel. */
    public String toTrace() {
        StringBuilder sb = new StringBuilder();
        sb.append("Request: ").append(request).append('\n');
        if (steps.isEmpty()) {
            sb.append("  (no tools were used)\n");
        }
        for (Step s : steps) {
            sb.append("  ").append(s.index()).append(". ").append(s.tool())
                    .append(' ').append(s.arguments())
                    .append(s.success() ? "  ok" : "  FAILED")
                    .append("  ").append(s.millis()).append(" ms");
            if (s.evidenceCount() > 0) {
                sb.append("  (").append(s.evidenceCount()).append(" record(s))");
            }
            sb.append('\n');
            if (s.summary() != null && !s.summary().isBlank()) {
                sb.append("      ").append(s.summary()).append('\n');
            }
        }
        if (!evidence.isEmpty()) {
            sb.append("  evidence: ");
            List<String> refs = new ArrayList<>();
            for (ToolResult.Evidence e : evidence) {
                refs.add(e.toString());
            }
            sb.append(String.join(", ", refs)).append('\n');
        }
        sb.append("  model calls: ").append(modelCalls)
                .append(", total ").append(totalMillis).append(" ms\n");
        if (failure != null) {
            sb.append("  FAILED: ").append(failure).append('\n');
        }
        return sb.toString();
    }

    private static String summarise(ToolResult r) {
        if (!r.success()) {
            return r.error();
        }
        String t = r.text() == null ? "" : r.text().strip();
        int nl = t.indexOf('\n');
        String head = nl > 0 ? t.substring(0, nl) : t;
        return head.length() > 120 ? head.substring(0, 120) + "..." : head;
    }
}
