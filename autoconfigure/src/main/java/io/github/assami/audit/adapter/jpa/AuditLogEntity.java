package io.github.assami.audit.adapter.jpa;

import io.github.assami.audit.model.AuditLevel;
import io.github.assami.audit.model.AuditLog;
import jakarta.persistence.*;
import org.hibernate.annotations.ResultCheckStyle;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * JPA entity for the {@code audit_log} table.
 * Soft-delete is implemented via {@code @SQLDelete} + {@code @SQLRestriction}.
 */
@Entity
@Table(
    name = "audit_log",
    indexes = {
        @Index(name = "idx_audit_log_keyset",       columnList = "created_at DESC, id DESC"),
        @Index(name = "idx_audit_log_service_name", columnList = "service_name"),
        @Index(name = "idx_audit_log_tenant_keyset", columnList = "tenant_id, created_at DESC, id DESC")
    }
)
// Hibernate binds (id, version) for a @Version entity, so the SQL needs both parameters.
// It also drops personal data: a deleted row is invisible to the API, so it could not be erased later.
// check = COUNT: by default Hibernate does not look at how many rows a custom statement changed, so a delete that
// raced an erase (which bumps the version) matched nothing and still answered 204 with the entry left in place.
@SQLDelete(sql = "UPDATE audit_log SET deleted_at = NOW(), payload = NULL, created_by = NULL, "
        + "last_modified_by = NULL WHERE id = ? AND version = ?", check = ResultCheckStyle.COUNT)
@SQLRestriction("deleted_at IS NULL")
public class AuditLogEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditLevel level = AuditLevel.INFO;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "gdpr_erased_at")
    private Instant gdprErasedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // ── Domain mapping ───────────────────────────────────────────────────────

    public static AuditLogEntity from(AuditLog domain) {
        AuditLogEntity e = new AuditLogEntity();
        e.id             = domain.getId();
        e.tenantId       = domain.getTenantId();
        e.serviceName    = domain.getServiceName();
        e.action         = domain.getAction();
        e.level          = domain.getLevel();
        e.payload        = domain.getPayload();
        e.createdAt      = domain.getCreatedAt();
        e.updatedAt      = domain.getUpdatedAt();
        e.createdBy      = domain.getCreatedBy();
        e.lastModifiedBy = domain.getLastModifiedBy();
        e.version        = null; // new row: a non-null version makes Spring Data merge (extra SELECT per row)
        e.deletedAt      = domain.getDeletedAt();
        e.gdprErasedAt   = domain.getGdprErasedAt();
        return e;
    }

    public AuditLog toDomain() {
        return AuditLog.builder()
                .id(id)
                .tenantId(tenantId)
                .serviceName(serviceName)
                .action(action)
                .level(level)
                .payload(payload)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .createdBy(createdBy)
                .lastModifiedBy(lastModifiedBy)
                .version(version)
                .deletedAt(deletedAt)
                .gdprErasedAt(gdprErasedAt)
                .build();
    }

    // ── Getters / setters ────────────────────────────────────────────────────

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
}
