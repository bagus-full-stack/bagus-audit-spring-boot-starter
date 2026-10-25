package io.github.assami.audit.service;

import io.github.assami.audit.event.AuditLogCreatedEvent;
import io.github.assami.audit.event.AuditLogDeletedEvent;
import io.github.assami.audit.event.AuditLogErasedEvent;
import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.port.AuditGdprEraser;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

/**
 * Core domain service — all business logic lives here.
 * Adapters and the REST layer delegate to this class.
 */
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditProperties properties;
    private final Optional<AuditGdprEraser> gdprEraser;

    public AuditService(AuditRepository repository,
                        ApplicationEventPublisher eventPublisher,
                        AuditProperties properties,
                        Optional<AuditGdprEraser> gdprEraser) {
        this.repository     = repository;
        this.eventPublisher = eventPublisher;
        this.properties     = properties;
        this.gdprEraser     = gdprEraser;
    }

    // ── Create ───────────────────────────────────────────────────────────────

    public AuditLog create(AuditLog.Builder builder) {
        AuditLog saved = repository.save(build(builder));
        publish(new AuditLogCreatedEvent(this, saved));
        log.debug("Audit log created: {}", saved.getId());
        return saved;
    }

    public List<AuditLog> createBatch(List<AuditLog.Builder> builders) {
        List<AuditLog> saved = repository.saveAll(builders.stream().map(this::build).toList());
        saved.forEach(l -> publish(new AuditLogCreatedEvent(this, l)));
        return saved;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public AuditLog findById(String id) {
        AuditLog found = repository.findById(id)
                .orElseThrow(() -> new AuditNotFoundException(id));
        // Another tenant's entry looks like a missing one.
        String tenant = TenantContext.get();
        if (tenant != null && !tenant.equals(found.getTenantId())) {
            throw new AuditNotFoundException(id);
        }
        return found;
    }

    public AuditPage query(AuditQuery query) {
        String tenant = TenantContext.get();
        return repository.findAll(tenant != null ? query.forTenant(tenant) : query);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void delete(String id) {
        findById(id); // tenant check
        repository.softDelete(id);
        publish(new AuditLogDeletedEvent(this, id));
        log.info("Audit log soft-deleted: {} by {}", id, actor());
    }

    // ── GDPR Erasure ─────────────────────────────────────────────────────────

    public void erasePersonalData(String id) {
        findById(id); // exists + tenant check

        // Call custom eraser hook if present
        gdprEraser.ifPresent(e -> e.beforeErase(id));

        repository.gdprErase(id);
        publish(new AuditLogErasedEvent(this, id));
        log.info("GDPR erasure performed on audit log: {} by {}", id, actor());
    }

    // ── Retention ────────────────────────────────────────────────────────────

    /**
     * Permanently removes entries soft-deleted more than {@code olderThan} ago, for every tenant.
     * Works in batches of {@code audit.retention.batch-size}, each in its own transaction, so a large backlog
     * never holds one long lock.
     */
    public long purgeDeleted(java.time.Duration olderThan) {
        java.time.Instant cutoff = java.time.Instant.now().minus(olderThan);
        int batch = Math.max(1, properties.getRetention().getBatchSize());
        long total = 0, removed;
        do {
            removed = repository.purgeDeleted(cutoff, batch);
            total += removed;
        } while (removed >= batch);
        if (total > 0) log.info("Purged {} soft-deleted audit entries older than {}", total, olderThan);
        return total;
    }

    /** Stamps the service name and the current tenant, which a caller never supplies. */
    private AuditLog build(AuditLog.Builder builder) {
        builder.serviceName(properties.getName());
        String tenant = TenantContext.get();
        if (tenant != null) builder.tenantId(tenant);
        return builder.build();
    }

    private static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    /**
     * The row is already committed when events go out, so a failing listener must not turn
     * a successful write into an error response (the caller would retry and duplicate it).
     */
    private void publish(ApplicationEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException ex) {
            log.warn("Listener failed for {}: {}", event.getClass().getSimpleName(), ex.toString());
        }
    }
}
