package io.github.assami.audit.adapter.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Spring Data JPA repository for {@link AuditLogEntity}.
 * Used internally by {@link JpaAuditRepository}, which builds the keyset query as a Specification.
 */
public interface AuditLogJpaRepository
        extends JpaRepository<AuditLogEntity, String>, JpaSpecificationExecutor<AuditLogEntity> {

    // ── GDPR erasure ─────────────────────────────────────────────────────────

    @Modifying
    @Query("""
           UPDATE AuditLogEntity a
           SET a.payload = NULL, a.createdBy = NULL, a.lastModifiedBy = NULL,
               a.gdprErasedAt = :erasedAt, a.updatedAt = :erasedAt, a.version = a.version + 1
           WHERE a.id = :id
           """)
    void gdprErase(@Param("id") String id, @Param("erasedAt") Instant erasedAt);

    // ── Retention ─────────────────────────────────────────────────────────────

    /** Native on purpose: HQL would add the soft-delete restriction and match nothing. */
    @Modifying
    @Query(value = "DELETE FROM audit_log WHERE deleted_at IS NOT NULL AND deleted_at < :before", nativeQuery = true)
    int purgeDeletedBefore(@Param("before") Instant before);

    // ── Count non-deleted ─────────────────────────────────────────────────────

    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.deletedAt IS NULL")
    long countActive();
}
