package io.github.assami.audit.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain model for an audit log entry.
 * This is the core domain object — it is adapter-agnostic.
 */
public class AuditLog {

    private final String id;
    private final String tenantId;
    private final String serviceName;
    private final String action;
    private final AuditLevel level;
    private final String payload;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String createdBy;
    private final String lastModifiedBy;
    private final Long version;
    private final Instant deletedAt;
    private final Instant gdprErasedAt;

    private AuditLog(Builder b) {
        this.id               = b.id;
        this.tenantId         = b.tenantId;
        this.serviceName      = b.serviceName;
        this.action           = b.action;
        this.level            = b.level;
        this.payload          = b.payload;
        this.createdAt        = b.createdAt;
        this.updatedAt        = b.updatedAt;
        this.createdBy        = b.createdBy;
        this.lastModifiedBy   = b.lastModifiedBy;
        this.version          = b.version;
        this.deletedAt        = b.deletedAt;
        this.gdprErasedAt     = b.gdprErasedAt;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getId()               { return id; }
    public String getTenantId()         { return tenantId; }
    public String getServiceName()      { return serviceName; }
    public String getAction()           { return action; }
    public AuditLevel getLevel()        { return level; }
    public String getPayload()          { return payload; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }
    public String getCreatedBy()        { return createdBy; }
    public String getLastModifiedBy()   { return lastModifiedBy; }
    public Long getVersion()            { return version; }
    public Instant getDeletedAt()       { return deletedAt; }
    public Instant getGdprErasedAt()    { return gdprErasedAt; }

    public boolean isDeleted()          { return deletedAt != null; }
    public boolean isGdprErased()       { return gdprErasedAt != null; }

    // ── Fluent mutation ──────────────────────────────────────────────────────

    public AuditLog markGdprErased(Instant erasedAt) {
        return toBuilder()
                .payload(null)
                .createdBy(null)
                .lastModifiedBy(null)
                .gdprErasedAt(erasedAt)
                .build();
    }

    public AuditLog softDelete(Instant deletedAt) {
        // A deleted entry is hidden from the API, so personal data could never be erased afterwards: drop it now.
        return toBuilder().payload(null).createdBy(null).lastModifiedBy(null).deletedAt(deletedAt).build();
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String tenantId;
        private String serviceName;
        private String action;
        private AuditLevel level = AuditLevel.INFO;
        private String payload;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private String createdBy;
        private String lastModifiedBy;
        private Long version = 0L;
        private Instant deletedAt;
        private Instant gdprErasedAt;

        private Builder() {}

        private Builder(AuditLog src) {
            this.id             = src.id;
            this.tenantId       = src.tenantId;
            this.serviceName    = src.serviceName;
            this.action         = src.action;
            this.level          = src.level;
            this.payload        = src.payload;
            this.createdAt      = src.createdAt;
            this.updatedAt      = src.updatedAt;
            this.createdBy      = src.createdBy;
            this.lastModifiedBy = src.lastModifiedBy;
            this.version        = src.version;
            this.deletedAt      = src.deletedAt;
            this.gdprErasedAt   = src.gdprErasedAt;
        }

        public Builder id(String id)                           { this.id = id; return this; }
        public Builder tenantId(String tenantId)               { this.tenantId = tenantId; return this; }
        public Builder serviceName(String serviceName)         { this.serviceName = serviceName; return this; }
        public Builder action(String action)                   { this.action = action; return this; }
        public Builder level(AuditLevel level)                 { this.level = level; return this; }
        public Builder payload(String payload)                 { this.payload = payload; return this; }
        public Builder createdAt(Instant createdAt)            { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt)            { this.updatedAt = updatedAt; return this; }
        public Builder createdBy(String createdBy)             { this.createdBy = createdBy; return this; }
        public Builder lastModifiedBy(String lastModifiedBy)   { this.lastModifiedBy = lastModifiedBy; return this; }
        public Builder version(Long version)                   { this.version = version; return this; }
        public Builder deletedAt(Instant deletedAt)            { this.deletedAt = deletedAt; return this; }
        public Builder gdprErasedAt(Instant gdprErasedAt)      { this.gdprErasedAt = gdprErasedAt; return this; }

        public AuditLog build() {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalStateException("serviceName is required");
            }
            if (action == null || action.isBlank()) {
                throw new IllegalStateException("action is required");
            }
            return new AuditLog(this);
        }
    }

    @Override
    public String toString() {
        return "AuditLog{id='" + id + "', service='" + serviceName +
               "', action='" + action + "', level=" + level + '}';
    }
}
