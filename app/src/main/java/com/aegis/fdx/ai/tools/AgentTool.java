package com.aegis.fdx.ai.tools;

/**
 * One capability the agent may invoke.
 *
 * <p>This interface is the security boundary. The model never touches SQL, the shell,
 * the filesystem or the network; it can only name a registered tool, and that tool
 * calls the same application facades a human operator's clicks would.
 */
public interface AgentTool {

    /** Registered name the model uses to call this tool. */
    String name();

    ToolSchema schema();

    /**
     * Runs the tool.
     *
     * <p>Implementations must not throw for ordinary failures; return
     * {@link ToolResult#failure} so the agent can reason about the problem and retry
     * or explain.
     */
    ToolResult execute(ToolRequest request);

    /** True when this tool changes application state. */
    default boolean isMutating() {
        return schema().mutating();
    }
}
