package io.github.assami.audit.exception;

/**
 * Thrown when multi-tenancy is enabled with isolation enforcement
 * but no tenant identifier could be resolved from the request.
 */
public class MissingTenantException extends AuditException {

    public MissingTenantException() {
        super("MISSING_TENANT", "No tenant identifier could be resolved from the current request. " +
                "Ensure the configured tenancy strategy provides a valid tenant.");
    }
}
