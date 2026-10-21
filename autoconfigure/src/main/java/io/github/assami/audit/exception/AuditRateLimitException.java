package io.github.assami.audit.exception;

/**
 * Thrown when the rate-limit window is exhausted.
 */
public class AuditRateLimitException extends AuditException {

    public AuditRateLimitException(String key) {
        super("AUDIT_RATE_LIMIT_EXCEEDED", "Rate limit exceeded for key: " + key);
    }
}
