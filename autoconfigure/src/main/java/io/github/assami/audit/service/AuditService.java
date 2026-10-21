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
import org.springframework.context.ApplicationEventPublisher;

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
        // Inject service name and current tenant
        builder.serviceName(properties.getName());
        if (TenantContext.get() != null) {
            builder.tenantId(TenantContext.get());
        }
        AuditLog saved = repository.save(builder.build());
        eventPublisher.publishEvent(new AuditLogCreatedEvent(this, saved));
        log.debug("Audit log created: {}", saved.getId());
        return saved;
    }

    public List<AuditLog> createBatch(List<AuditLog.Builder> builders) {
        List<AuditLog> logs = builders.stream()
                .map(b -> {
                    b.serviceName(properties.getName());
                    if (TenantContext.get() != null) b.tenantId(TenantContext.get());
                    return b.build();
                })
                .toList();
        List<AuditLog> saved = repository.saveAll(logs);
        saved.forEach(l -> eventPublisher.publishEvent(new AuditLogCreatedEvent(this, l)));
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
        eventPublisher.publishEvent(new AuditLogDeletedEvent(this, id));
        log.debug("Audit log soft-deleted: {}", id);
    }

    // ── GDPR Erasure ─────────────────────────────────────────────────────────

    public void erasePersonalData(String id) {
        findById(id); // exists + tenant check

        // Call custom eraser hook if present
        gdprEraser.ifPresent(e -> e.beforeErase(id));

        repository.gdprErase(id);
        eventPublisher.publishEvent(new AuditLogErasedEvent(this, id));
        log.info("GDPR erasure performed on audit log: {}", id);
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    public long count() {
        return repository.count();
    }
}
