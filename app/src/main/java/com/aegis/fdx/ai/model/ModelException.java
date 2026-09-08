package com.aegis.fdx.ai.model;

/**
 * A local model runtime failed.
 *
 * <p>Carries a {@link Kind} so the interface can tell an operator whether the problem
 * is fixable by them (no runtime installed, model file missing) or transient (timeout).
 */
public class ModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** No local runtime is reachable at the configured endpoint. */
        RUNTIME_UNAVAILABLE,
        /** The runtime is up but the requested model is not loaded or installed. */
        MODEL_UNAVAILABLE,
        /** The call exceeded its time budget. */
        TIMEOUT,
        /** The operator cancelled the request. */
        CANCELLED,
        /** The runtime replied with something unusable. */
        BAD_RESPONSE,
        /** Anything else. */
        INTERNAL
    }

    private final Kind kind;

    public ModelException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ModelException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** True when installing or starting a local runtime would fix this. */
    public boolean isSetupProblem() {
        return kind == Kind.RUNTIME_UNAVAILABLE || kind == Kind.MODEL_UNAVAILABLE;
    }
}
