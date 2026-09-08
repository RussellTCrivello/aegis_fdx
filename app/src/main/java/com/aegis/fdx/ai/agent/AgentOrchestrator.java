package com.aegis.fdx.ai.agent;

import com.aegis.fdx.ai.model.ChatMessage;
import com.aegis.fdx.ai.model.ChatResponse;
import com.aegis.fdx.ai.model.LocalChatModel;
import com.aegis.fdx.ai.model.LocalModelProvider;
import com.aegis.fdx.ai.model.ModelException;
import com.aegis.fdx.ai.model.ToolCall;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ai.tools.AgentTool;
import com.aegis.fdx.ai.tools.ToolRequest;
import com.aegis.fdx.ai.tools.ToolResult;
import com.aegis.fdx.ai.tools.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the agent loop: understand, choose a tool, observe the result, repeat, answer.
 *
 * <p>The loop is bounded by {@link #maxSteps} so a confused model cannot spin. Every
 * tool call is validated against its schema before execution, and unknown tool names
 * are reported back to the model as an observation so it can correct itself rather
 * than failing the whole run.
 *
 * <p>Grounding is enforced structurally: the answer is assembled from tool
 * observations, and {@link AgentActivity#isGrounded()} records whether any real record
 * was actually retrieved. When nothing was found, the agent is instructed to say so
 * instead of inventing an answer.
 */
public final class AgentOrchestrator {

    /** Ceiling on tool-call rounds in one run. */
    public static final int DEFAULT_MAX_STEPS = 6;

    private final LocalModelProvider provider;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final int maxSteps;

    public AgentOrchestrator(LocalModelProvider provider, List<AgentTool> tools) {
        this(provider, tools, DEFAULT_MAX_STEPS);
    }

    public AgentOrchestrator(LocalModelProvider provider, List<AgentTool> tools, int maxSteps) {
        this.provider = provider;
        this.maxSteps = Math.max(1, maxSteps);
        for (AgentTool t : tools) {
            this.tools.put(t.name(), t);
        }
    }

    /** Tool names currently available, in advertised order. */
    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    public boolean isAvailable() {
        return provider != null && provider.isAvailable();
    }

    /** Why the agent cannot run; null when it can. */
    public String unavailableReason() {
        if (provider == null) {
            return "no local model provider is configured";
        }
        LocalChatModel m = provider.chatModel();
        if (m == null) {
            return "no local chat model is configured";
        }
        return m.isAvailable() ? null : m.unavailableReason();
    }

    /**
     * Answers a question, using tools as needed.
     *
     * @param cancelled polled between steps so the interface can abort a long run
     */
    public AgentActivity ask(String question, AgentContext context, AtomicBoolean cancelled) {
        AgentActivity activity = new AgentActivity(question);
        long t0 = System.nanoTime();

        if (question == null || question.isBlank()) {
            activity.fail("empty question", 0);
            return activity;
        }
        String unavailable = unavailableReason();
        if (unavailable != null) {
            activity.fail(unavailable, elapsed(t0));
            return activity;
        }

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(systemPrompt(context)));
        conversation.add(ChatMessage.user(question));

        try {
            for (int step = 0; step < maxSteps; step++) {
                if (cancelled != null && cancelled.get()) {
                    activity.fail("cancelled by the operator", elapsed(t0));
                    return activity;
                }

                activity.countModelCall();
                ChatResponse response = provider.chatModel().chat(conversation, List.of());

                if (!response.hasToolCalls()) {
                    String answer = response.text();
                    if (answer.isBlank()) {
                        answer = "I could not produce an answer for that.";
                    }
                    activity.finish(withGroundingNote(answer, activity), elapsed(t0));
                    return activity;
                }

                conversation.add(ChatMessage.assistant(rawOf(response)));

                for (ToolCall call : response.toolCalls()) {
                    if (cancelled != null && cancelled.get()) {
                        activity.fail("cancelled by the operator", elapsed(t0));
                        return activity;
                    }
                    ToolResult result = runTool(call, context);
                    activity.addStep(call.tool(), call.arguments(), result);
                    conversation.add(ChatMessage.tool(call.tool(), result.toObservation()));
                }
            }

            // Step budget exhausted: ask for a final answer from what was gathered.
            conversation.add(ChatMessage.user(
                    "Stop calling tools. Using only the tool results above, give your final "
                            + "answer now. If the results do not support an answer, say so plainly."));
            activity.countModelCall();
            ChatResponse last = provider.chatModel().chat(conversation, List.of());
            String answer = last.text().isBlank()
                    ? "I gathered evidence but could not summarise it within the step budget."
                    : last.text();
            activity.finish(withGroundingNote(answer, activity), elapsed(t0));
            return activity;

        } catch (ModelException e) {
            activity.fail(e.getMessage(), elapsed(t0));
            return activity;
        } catch (RuntimeException e) {
            activity.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(t0));
            return activity;
        }
    }

    /** Validates then executes one call. Unknown or malformed calls become observations. */
    private ToolResult runTool(ToolCall call, AgentContext context) {
        AgentTool tool = tools.get(call.tool());
        if (tool == null) {
            return ToolResult.failure("no such tool '" + call.tool()
                    + "'. Available tools: " + String.join(", ", tools.keySet()));
        }
        if (tool.isMutating() && !context.allowMutations()) {
            return ToolResult.failure("'" + call.tool() + "' changes data and the operator "
                    + "has not confirmed it. Answer using read-only tools instead.");
        }
        String invalid = tool.schema().validate(call.arguments());
        if (invalid != null) {
            return ToolResult.failure("invalid call to '" + call.tool() + "': " + invalid
                    + ". Expected " + tool.schema().toPromptLine());
        }
        return tool.execute(new ToolRequest(call.arguments(), context));
    }

    /**
     * Labels every statement with its provenance and appends an explicit caveat when
     * nothing was retrieved. The model is asked to label; the rule in {@link Provenance}
     * only ever demotes, so an unsupported "observation" cannot survive.
     */
    private static String withGroundingNote(String answer, AgentActivity activity) {
        List<String> ids = new ArrayList<>();
        for (ToolResult.Evidence e : activity.evidence()) {
            ids.add(e.id());
        }
        boolean anythingRead = activity.isGrounded();
        Provenance.Labelled labelled = Provenance.label(answer, ids, anythingRead, List.of());
        activity.recordProvenance(labelled.counts());
        String text = labelled.text();
        if (activity.isGrounded() || activity.steps().isEmpty()) {
            return text;
        }
        return text + "\n\n[UNKNOWN] (No matching records were found, so this answer is not "
                + "supported by case data.)";
    }

    private static String rawOf(ChatResponse r) {
        StringBuilder sb = new StringBuilder(r.text());
        for (ToolCall c : r.toolCalls()) {
            sb.append('\n').append(c.rawText());
        }
        return sb.toString();
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /**
     * Builds the instructions the model runs under.
     *
     * <p>Public and static so the prompt contract can be asserted in tests without a
     * running model.
     */
    public String systemPrompt(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an analysis assistant inside a file-analysis application. ")
                .append("You help an operator investigate material that the application has ")
                .append("already read, extracted and indexed.\n\n");

        sb.append("HOW TO ACT\n")
                .append("- Answer only from data returned by tools. Never invent file names, ")
                .append("identifiers, counts or quotations.\n")
                .append("- If the tools return nothing relevant, say so plainly. That is a ")
                .append("correct answer, not a failure.\n")
                .append("- Work in steps: call a tool, read the result, then decide the next ")
                .append("step. Several tools may be needed for one question.\n")
                .append("- Cite the record identifiers the tools return, e.g. item:E-000001.\n")
                .append("- Start every statement of your final answer with one label: ")
                .append("[OBSERVED] for a fact read from a record a tool returned, ")
                .append("[DERIVED] for a count or comparison computed from such records, ")
                .append("[INFERRED] for your own conclusion, ")
                .append("[USER-PROVIDED] for something the operator told you, ")
                .append("[UNKNOWN] when the tools did not supply it.\n")
                .append("- Be concise and factual.\n\n");

        sb.append("CALLING A TOOL\n")
                .append("Emit exactly this JSON, alone on its own line, and then stop:\n")
                .append("{\"tool\": \"tool_name\", \"arguments\": {\"name\": \"value\"}}\n")
                .append("You will receive the result as a TOOL RESULT message. ")
                .append("When you have enough information, reply with prose and no JSON.\n\n");

        sb.append("AVAILABLE TOOLS\n");
        for (AgentTool t : tools.values()) {
            sb.append("- ").append(t.schema().toPromptLine()).append('\n');
        }
        sb.append('\n');

        sb.append("CURRENT CONTEXT\n").append(context.toPromptBlock()).append('\n');
        if (!context.allowMutations()) {
            sb.append("You are in read-only mode. Tools that change data are unavailable.\n");
        }
        return sb.toString();
    }

    /** Schemas of the registered tools, for documentation and tests. */
    public List<ToolSchema> schemas() {
        List<ToolSchema> out = new ArrayList<>();
        for (AgentTool t : tools.values()) {
            out.add(t.schema());
        }
        return out;
    }
}
