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
import java.util.function.Supplier;

/**
 * Entry point for the local agent.
 *
 * <p>Owns tool registration and keeps the history of runs so the interface can show an
 * activity trail. The application works normally when no local runtime is installed:
 * {@link #isAvailable()} reports false and the interface explains what to install
 * rather than failing.
 *
 * <p><b>Nothing AI-related is loaded until someone asks for it.</b> {@link
 * #fromEnvironment} does not read the model configuration, does not construct a model
 * provider and does not touch the network; it only remembers how to build one. The
 * provider is created on the first call that genuinely needs a model — opening the
 * Assistant, or pressing an analyse action — so starting the application, ingesting,
 * extracting, indexing, searching and exporting all run with the model classes
 * untouched. {@link #isModelLoaded()} reports whether that has happened, and the
 * boundary tests assert it is still false after a full processing cycle.
 */
public final class AgentService {

    private final AegisFacades facades;
    private final CorpusDatabase dao;

    /** How to build a provider on demand; null when one was supplied directly. */
    private final Supplier<LocalModelProvider> providerFactory;

    private final Object providerLock = new Object();
    private volatile boolean resolved;
    private volatile LocalModelProvider provider;
    private volatile String resolutionError;

    private final List<AgentActivity> history =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * Builds a service around an already-constructed provider.
     *
     * <p>A null provider means "no model", which is a supported, fully functional state:
     * every AI affordance reports itself unavailable and everything else works.
     */
    public AgentService(AegisFacades facades, CorpusDatabase dao, LocalModelProvider provider) {
        this.facades = facades;
        this.dao = dao;
        this.providerFactory = null;
        this.provider = provider;
        this.resolved = true;
    }

    private AgentService(AegisFacades facades, CorpusDatabase dao,
                         Supplier<LocalModelProvider> factory, boolean lazy) {
        this.facades = facades;
        this.dao = dao;
        this.providerFactory = factory;
        this.resolved = false;
    }

    /**
     * Builds a service from system properties, without loading anything yet.
     *
     * <p>Deliberately cheap: no configuration is read and no runtime is contacted here,
     * because this is called while the application window is being built.
     */
    public static AgentService fromEnvironment(AegisFacades facades, CorpusDatabase dao) {
        return new AgentService(facades, dao, AgentService::buildFromEnvironment, true);
    }

    /** Constructs the configured provider, or null when the agent is switched off. */
    private static LocalModelProvider buildFromEnvironment() {
        ModelConfig cfg = ModelConfig.fromEnvironment();
        if (!cfg.enabled()) {
            return null;
        }
        return new HttpLocalModelProvider(cfg);
    }

    /**
     * Whether the model provider has actually been constructed.
     *
     * <p>Exists so the no-AI-at-startup rule can be asserted rather than asserted about.
     */
    public boolean isModelLoaded() {
        return resolved && provider != null;
    }

    /**
     * Resolves the provider on first demand.
     *
     * <p>A misconfigured or missing runtime is not an error condition for the
     * application: it resolves to "no provider" with the reason kept for display.
     */
    private LocalModelProvider provider() {
        if (resolved) {
            return provider;
        }
        synchronized (providerLock) {
            if (resolved) {
                return provider;
            }
            try {
                provider = providerFactory == null ? null : providerFactory.get();
            } catch (RuntimeException e) {
                // A misconfigured endpoint must not stop the application from working.
                provider = null;
                resolutionError = e.getMessage();
            }
            resolved = true;
            return provider;
        }
    }

    public boolean isAvailable() {
        LocalModelProvider p = provider();
        return p != null && p.isAvailable();
    }

    /** Why the agent is unavailable, phrased for an operator; null when available. */
    public String unavailableReason() {
        LocalModelProvider p = provider();
        if (p == null) {
            String detail = resolutionError;
            if (detail != null && !detail.isBlank()) {
                return "The local AI agent could not be started: " + detail;
            }
            return "The local AI agent is disabled or no runtime is configured. "
                    + "Start a local model runtime and set -Daegis.ai.endpoint if it is "
                    + "not on the default port.";
        }
        String r = orchestrator(AgentContext.empty()).unavailableReason();
        return r == null ? null : r;
    }

    public ModelConfig config() {
        LocalModelProvider p = provider();
        return p == null ? ModelConfig.fromEnvironment() : p.config();
    }

    public String modelId() {
        LocalModelProvider p = provider();
        return p == null ? "(none)" : p.chatModel().modelId();
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
        return new AgentOrchestrator(provider(), tools);
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
