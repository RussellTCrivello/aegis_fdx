package com.aegis.fdx.facade;

/**
 * The single error type raised by the interface layer.
 *
 * <p>Facade signatures never leak the engine's internal checked exceptions
 * ({@code SQLException}, {@code IOException}, {@code QuerySyntaxException}); those are
 * wrapped here with a {@link Kind} that tells the caller how to react.
 *
 * <p>Unchecked deliberately: a UI action handler cannot do anything useful with a
 * checked exception on every call, and the kind already carries the distinction that
 * matters.
 */
public class FacadeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What kind of failure occurred. */
    public enum Kind {
        /** Input failed validation at the interface boundary. */
        VALIDATION,
        /** The referenced entity does not exist. */
        NOT_FOUND,
        /** Unique-constraint style conflict (duplicate name). */
        CONFLICT,
        /** The operation exists but cannot be serviced. */
        UNSUPPORTED,
        /** Anything else that escaped the internal implementation. */
        INTERNAL
    }

    private final Kind kind;

    public FacadeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FacadeException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    // ---- factories -------------------------------------------------------

    public static FacadeException validation(String message) {
        return new FacadeException(Kind.VALIDATION, message);
    }

    public static FacadeException notFound(String entity, Object id) {
        return new FacadeException(Kind.NOT_FOUND, entity + " not found: " + id);
    }

    public static FacadeException conflict(String message) {
        return new FacadeException(Kind.CONFLICT, message);
    }

    public static FacadeException unsupported(String message) {
        return new FacadeException(Kind.UNSUPPORTED, message);
    }

    public static FacadeException internal(String message, Throwable cause) {
        return new FacadeException(Kind.INTERNAL, message, cause);
    }
}
