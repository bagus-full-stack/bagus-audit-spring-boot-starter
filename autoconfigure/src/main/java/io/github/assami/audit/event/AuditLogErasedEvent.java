package io.github.assami.audit.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published after personal data is erased from an audit log entry (GDPR).
 */
public class AuditLogErasedEvent extends ApplicationEvent {

    private final String auditLogId;

    public AuditLogErasedEvent(Object source, String auditLogId) {
        super(source);
        this.auditLogId = auditLogId;
    }

    public String getAuditLogId() {
        return auditLogId;
    }
}
