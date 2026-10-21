package io.github.assami.audit.exception;

/**
 * Thrown when the tenant named by the request (header or host) differs from the tenant claim of the caller's token.
 */
public class TenantMismatchException extends AuditException {

    public TenantMismatchException() {
        super("TENANT_MISMATCH", "The requested tenant does not match the tenant of the authenticated token.");
    }
}
