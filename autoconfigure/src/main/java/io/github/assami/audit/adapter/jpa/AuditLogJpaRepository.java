package io.github.assami.audit.adapter.jpa;

import io.github.assami.audit.model.AuditLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link AuditLogEntity}.
 * Used internally by {@link JpaAuditRepository}.
 */
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, String> {

    // ── Keyset pagination query (primary) ────────────────────────────────────

    @Query("""
           SELECT a FROM AuditLogEntity a
           WHERE (:tenantId     IS NULL OR a.tenantId    = :tenantId)
             AND (:serviceName  IS NULL OR a.serviceName = :serviceName)
             AND (:action       IS NULL OR a.action      = :action)
             AND (:level        IS NULL OR a.level       = :level)
             AND (:from         IS NULL OR a.createdAt  >= :from)
             AND (:to           IS NULL OR a.createdAt  <= :to)
             AND (
               :cursorTs IS NULL
               OR a.createdAt < :cursorTs
               OR (a.createdAt = :cursorTs AND a.id < :cursorId)
             )
           ORDER BY a.createdAt DESC, a.id DESC
           """)
    List<AuditLogEntity> findPageWithKeyset(
            @Param("tenantId")    String tenantId,
            @Param("serviceName") String serviceName,
            @Param("action")      String action,
            @Param("level")       AuditLevel level,
            @Param("from")        Instant from,
            @Param("to")          Instant to,
            @Param("cursorTs")    Instant cursorTs,
            @Param("cursorId")    String cursorId,
            Pageable pageable
    );

    // ── GDPR erasure ─────────────────────────────────────────────────────────

    @Modifying
    @Query("""
           UPDATE AuditLogEntity a
           SET a.payload = NULL, a.createdBy = NULL, a.lastModifiedBy = NULL,
               a.gdprErasedAt = :erasedAt, a.updatedAt = :erasedAt
           WHERE a.id = :id
           """)
    void gdprErase(@Param("id") String id, @Param("erasedAt") Instant erasedAt);

    // ── Count non-deleted ─────────────────────────────────────────────────────

    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.deletedAt IS NULL")
    long countActive();
}
