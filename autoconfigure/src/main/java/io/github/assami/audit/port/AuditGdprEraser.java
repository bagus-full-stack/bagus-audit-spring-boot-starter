package io.github.assami.audit.port;

/**
 * Optional SPI: implement this bean to customize what "personal data erasure"
 * means for your application domain before the log row is cleared.
 *
 * <p>If no bean of this type is registered, nothing extra happens: the repository
 * always clears {@code payload}, {@code createdBy} and {@code lastModifiedBy}.</p>
 *
 * <pre>{@code
 * @Bean
 * public AuditGdprEraser myEraser() {
 *     return (auditLog) -> {
 *         // custom logic, e.g. call a vault, notify a DPO system, etc.
 *     };
 * }
 * }</pre>
 */
@FunctionalInterface
public interface AuditGdprEraser {

    /**
     * Called before the repository's {@code gdprErase(id)} is invoked.
     * Implementations may perform additional side-effects (notifications,
     * external redaction, audit trail in a separate system, etc.).
     *
     * @param id the identifier of the log entry being erased
     */
    void beforeErase(String id);
}
