package io.github.assami.audit.model;

import java.time.Instant;

/**
 * Filtering + pagination criteria for querying audit logs.
 */
public class AuditQuery {

    private final String tenantId;
    private final String serviceName;
    private final String action;
    private final AuditLevel level;
    private final Instant from;
    private final Instant to;
    private final String cursor;       // opaque Base64 keyset cursor
    private final int pageSize;

    private AuditQuery(Builder b) {
        this.tenantId    = b.tenantId;
        this.serviceName = b.serviceName;
        this.action      = b.action;
        this.level       = b.level;
        this.from        = b.from;
        this.to          = b.to;
        this.cursor      = b.cursor;
        this.pageSize    = b.pageSize;
    }

    public String getTenantId()     { return tenantId; }
    public String getServiceName()  { return serviceName; }
    public String getAction()       { return action; }
    public AuditLevel getLevel()    { return level; }
    public Instant getFrom()        { return from; }
    public Instant getTo()          { return to; }
    public String getCursor()       { return cursor; }
    public int getPageSize()        { return pageSize; }

    public static Builder builder() { return new Builder(); }

    /** Same criteria, with the tenant forced (never trust a caller-supplied tenant). */
    public AuditQuery forTenant(String tenant) {
        return builder().tenantId(tenant).serviceName(serviceName).action(action).level(level)
                .from(from).to(to).cursor(cursor).pageSize(pageSize).build();
    }

    public static final class Builder {
        private String tenantId;
        private String serviceName;
        private String action;
        private AuditLevel level;
        private Instant from;
        private Instant to;
        private String cursor;
        private int pageSize = 20;

        public Builder tenantId(String tenantId)        { this.tenantId = tenantId; return this; }
        public Builder serviceName(String serviceName)  { this.serviceName = serviceName; return this; }
        public Builder action(String action)            { this.action = action; return this; }
        public Builder level(AuditLevel level)          { this.level = level; return this; }
        public Builder from(Instant from)               { this.from = from; return this; }
        public Builder to(Instant to)                   { this.to = to; return this; }
        public Builder cursor(String cursor)            { this.cursor = cursor; return this; }
        public Builder pageSize(int pageSize)           { this.pageSize = Math.max(1, Math.min(pageSize, 200)); return this; }

        public AuditQuery build() { return new AuditQuery(this); }
    }
}
