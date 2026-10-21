package io.github.assami.audit.event;

import io.github.assami.audit.model.AuditLog;
import org.springframework.context.ApplicationEvent;

/**
 * Published after an {@link AuditLog} is updated.
 */
public class AuditLogUpdatedEvent extends ApplicationEvent {

    private final AuditLog auditLog;

    public AuditLogUpdatedEvent(Object source, AuditLog auditLog) {
        super(source);
        this.auditLog = auditLog;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }
}
