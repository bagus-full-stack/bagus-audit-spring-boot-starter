package io.github.assami.audit.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published after an audit log entry is soft-deleted.
 */
public class AuditLogDeletedEvent extends ApplicationEvent {

    private final String auditLogId;

    public AuditLogDeletedEvent(Object source, String auditLogId) {
        super(source);
        this.auditLogId = auditLogId;
    }

    public String getAuditLogId() {
        return auditLogId;
    }
}
