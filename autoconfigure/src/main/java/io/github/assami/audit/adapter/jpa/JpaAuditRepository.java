package io.github.assami.audit.adapter.jpa;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditCursor;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.port.AuditRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the {@link AuditRepository} port.
 * <p>
 * Uses composite keyset pagination {@code (created_at DESC, id DESC)} for
 * stable cursor-based traversal.
 * </p>
 */
@Transactional
public class JpaAuditRepository implements AuditRepository {

    private final AuditLogJpaRepository jpa;

    public JpaAuditRepository(AuditLogJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuditLog save(AuditLog log) {
        return jpa.save(AuditLogEntity.from(log)).toDomain();
    }

    @Override
    public List<AuditLog> saveAll(List<AuditLog> logs) {
        List<AuditLogEntity> entities = logs.stream().map(AuditLogEntity::from).toList();
        return jpa.saveAll(entities).stream().map(AuditLogEntity::toDomain).toList();
    }

    @Override
    public Optional<AuditLog> findById(String id) {
        return jpa.findById(id).map(AuditLogEntity::toDomain);
    }

    @Override
    public AuditPage findAll(AuditQuery query) {
        // Decode cursor if present
        Instant cursorTs = null;
        String  cursorId = null;
        if (query.getCursor() != null) {
            AuditCursor c = AuditCursor.decode(query.getCursor());
            cursorTs = c.createdAt();
            cursorId = c.id();
        }

        // Fetch pageSize+1 to detect next page
        int fetchSize = query.getPageSize() + 1;
        List<AuditLogEntity> rows = jpa.findPageWithKeyset(
                query.getTenantId(),
                query.getServiceName(),
                query.getAction(),
                query.getLevel(),
                query.getFrom(),
                query.getTo(),
                cursorTs,
                cursorId,
                PageRequest.of(0, fetchSize)
        );

        boolean hasNext = rows.size() > query.getPageSize();
        List<AuditLog> page = rows.subList(0, Math.min(rows.size(), query.getPageSize()))
                .stream().map(AuditLogEntity::toDomain).toList();

        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            AuditLog last = page.get(page.size() - 1);
            nextCursor    = new AuditCursor(last.getCreatedAt(), last.getId()).encode();
        }

        return AuditPage.of(page, nextCursor);
    }

    @Override
    public void softDelete(String id) {
        if (!jpa.existsById(id)) throw new AuditNotFoundException(id);
        jpa.deleteById(id); // @SQLDelete intercepts this and sets deleted_at
    }

    @Override
    public void gdprErase(String id) {
        if (!jpa.existsById(id)) throw new AuditNotFoundException(id);
        jpa.gdprErase(id, Instant.now());
    }

    @Override
    public long count() {
        return jpa.countActive();
    }
}
