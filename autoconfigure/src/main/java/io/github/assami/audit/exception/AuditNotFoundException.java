package io.github.assami.audit.exception;

/**
 * Thrown when an audit log entry cannot be found.
 */
public class AuditNotFoundException extends AuditException {

    public AuditNotFoundException(String id) {
        super("AUDIT_NOT_FOUND", "Audit log not found: " + id);
    }
}
