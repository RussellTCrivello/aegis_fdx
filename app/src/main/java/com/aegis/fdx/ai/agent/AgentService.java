package com.aegis.fdx.ai.agent;

import com.aegis.fdx.ai.model.HttpLocalModelProvider;
import com.aegis.fdx.ai.model.LocalModelProvider;
import com.aegis.fdx.ai.model.ModelConfig;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ai.tools.AgentTool;
import com.aegis.fdx.ai.tools.CaseTools;
import com.aegis.fdx.ai.tools.MutatingTools;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.store.CorpusDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the local agent.
 *
 * <p>Owns tool registration and keeps the history of runs so the interface can show an
 * activity trail. The application works normally when no local runtime is installed:
 * {@link #isAvailable()} reports false and the interface explains what to install
 * rather than failing.
 */
public final class AgentService {

    private final AegisFacades facades;
    private final CorpusDatabase dao;
    private final LocalModelProvider provider;
    private final List<AgentActivity> history =
            Collections.synchronizedList(new ArrayList<>());

    public AgentService(AegisFacades facades, CorpusDatabase dao, LocalModelProvider provider) {
        this.facades = facades;
        this.dao = dao;
        this.provider = provider;
    }

    /** Builds a service from system properties. */
    public static AgentService fromEnvironment(AegisFacades facades, CorpusDatabase dao) {
        ModelConfig cfg = ModelConfig.fromEnvironment();
        if (!cfg.enabled()) {
            return new AgentService(facades, dao, null);
        }
        try {
            return new AgentService(facades, dao, new HttpLocalModelProvider(cfg));
        } catch (RuntimeException e) {
            // A misconfigured endpoint must not stop the application from starting.
            return new AgentService(facades, dao, null);
        }
    }

    public boolean isAvailable() {
        return provider != null && provider.isAvailable();
    }

    /** Why the agent is unavailable, phrased for an operator; null when available. */
    public String unavailableReason() {
        if (provider == null) {
            return "The local AI agent is disabled or no runtime is configured. "
                    + "Start a local model runtime and set -Daegis.ai.endpoint if it is "
                    + "not on the default port.";
        }
        String r = orchestrator(AgentContext.empty()).unavailableReason();
        return r == null ? null : r;
    }

    public ModelConfig config() {
        return provider == null ? ModelConfig.fromEnvironment() : provider.config();
    }

    public String modelId() {
        return provider == null ? "(none)" : provider.chatModel().modelId();
    }

    /**
     * Answers a question in the given interface context.
     *
     * <p>Read-only unless the context explicitly permits changes.
     */
    public AgentActivity ask(String question, AgentContext context) {
        return ask(question, context, new AtomicBoolean(false));
    }

    public AgentActivity ask(String question, AgentContext context, AtomicBoolean cancelled) {
        AgentContext ctx = context == null ? AgentContext.empty() : context;
        AgentActivity activity = orchestrator(ctx).ask(question, ctx, cancelled);
        history.add(activity);
        return activity;
    }

    /** Runs so far, most recent last. */
    public List<AgentActivity> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    public void clearHistory() {
        history.clear();
    }

    /** Tool names available in a given context; mutating tools appear only if allowed. */
    public List<String> availableTools(AgentContext context) {
        return orchestrator(context == null ? AgentContext.empty() : context).toolNames();
    }

    private AgentOrchestrator orchestrator(AgentContext context) {
        List<AgentTool> tools = new ArrayList<>(new CaseTools(facades, dao).all());
        if (context.allowMutations()) {
            tools.addAll(new MutatingTools(facades, dao).all());
        }
        return new AgentOrchestrator(provider, tools);
    }

    /** Suggested questions for the current screen, shown as one-click prompts. */
    public static List<String> suggestionsFor(AgentContext context) {
        String screen = context.screen() == null ? "" : context.screen();
        return switch (screen) {
            case "Search" -> List.of(
                    "Summarise these search results.",
                    "Which sources do these results come from?",
                    "What keywords appear most across these results?");
            case "Sources" -> List.of(
                    "Summarise this source and what was collected from it.",
                    "Which files came from this source?",
                    "Compare this source with the others on the case.");
            case "File Library" -> List.of(
                    "Summarise this file.",
                    "What is this file related to?",
                    "Which categories and keywords apply to this file?");
            case "Keywords" -> List.of(
                    "Which files contain this keyword?",
                    "Which keywords occur most frequently?",
                    "Which categories do these keywords belong to?");
            case "Categories" -> List.of(
                    "How many files fall under each category?",
                    "What is in this category?");
            default -> List.of(
                    "What is on this case?",
                    "How much material has been processed, and did anything fail?",
                    "What are the most common file types and categories?");
        };
    }
}
