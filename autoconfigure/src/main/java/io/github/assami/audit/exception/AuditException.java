package io.github.assami.audit.exception;

/**
 * Base runtime exception for all audit library errors.
 */
public class AuditException extends RuntimeException {

    private final String errorCode;

    public AuditException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AuditException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
