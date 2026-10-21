package io.github.assami.audit.exception;

/**
 * Thrown when an incoming request fails business validation.
 */
public class AuditValidationException extends AuditException {

    public AuditValidationException(String field, String message) {
        super("AUDIT_VALIDATION_ERROR", "Validation failed on field '" + field + "': " + message);
    }

    public AuditValidationException(String message) {
        super("AUDIT_VALIDATION_ERROR", message);
    }
}
