package io.github.assami.audit.adapter.jpa;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditCursor;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.port.AuditRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
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

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

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
        // Fetch pageSize+1 to detect next page
        int fetchSize = query.getPageSize() + 1;
        List<AuditLogEntity> rows = jpa.findBy(matching(query),
                q -> q.sortBy(NEWEST_FIRST).limit(fetchSize).all());

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

    /**
     * Only the filters that were given become predicates. A fixed {@code (:p IS NULL OR col = :p)}
     * query gets one cached plan for every combination, which cannot use the tenant index well.
     */
    private static Specification<AuditLogEntity> matching(AuditQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (query.getTenantId() != null)    p.add(cb.equal(root.get("tenantId"), query.getTenantId()));
            if (query.getServiceName() != null) p.add(cb.equal(root.get("serviceName"), query.getServiceName()));
            if (query.getAction() != null)      p.add(cb.equal(root.get("action"), query.getAction()));
            if (query.getLevel() != null)       p.add(cb.equal(root.get("level"), query.getLevel()));
            if (query.getFrom() != null)        p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), query.getFrom()));
            if (query.getTo() != null)          p.add(cb.lessThanOrEqualTo(root.<Instant>get("createdAt"), query.getTo()));
            if (query.getCursor() != null) {
                AuditCursor c = AuditCursor.decode(query.getCursor());
                // The OR below cannot bound an index scan. Without this range, PostgreSQL walks the index from the
                // newest row and filters: 436 ms for a page 40 days deep on 2M rows, 0.16 ms with it.
                p.add(cb.lessThanOrEqualTo(root.<Instant>get("createdAt"), c.createdAt()));
                p.add(cb.or(
                        cb.lessThan(root.<Instant>get("createdAt"), c.createdAt()),
                        cb.and(cb.equal(root.get("createdAt"), c.createdAt()),
                               cb.lessThan(root.<String>get("id"), c.id()))));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
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
    public long purgeDeleted(Instant before, int limit) {
        return jpa.purgeDeletedBefore(before, limit);
    }

    @Override
    public long count() {
        return jpa.countActive();
    }
}
