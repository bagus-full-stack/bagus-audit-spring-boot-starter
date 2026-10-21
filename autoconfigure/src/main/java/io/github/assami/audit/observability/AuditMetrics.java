package io.github.assami.audit.observability;

import io.github.assami.audit.event.AuditLogCreatedEvent;
import io.github.assami.audit.event.AuditLogDeletedEvent;
import io.github.assami.audit.event.AuditLogErasedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

/**
 * Micrometer counters fed by the audit events. Counting rows with a query on every scrape
 * would scan the whole table, so nothing here touches the database.
 *
 * <ul>
 *   <li>{@code audit.entries.created}, {@code audit.entries.deleted}, {@code audit.entries.erased}</li>
 * </ul>
 */
public class AuditMetrics {

    public static final String METRIC_CREATED = "audit.entries.created";
    public static final String METRIC_DELETED = "audit.entries.deleted";
    public static final String METRIC_ERASED  = "audit.entries.erased";

    private final Counter created;
    private final Counter deleted;
    private final Counter erased;

    public AuditMetrics(MeterRegistry registry) {
        this.created = Counter.builder(METRIC_CREATED).description("Audit entries created").register(registry);
        this.deleted = Counter.builder(METRIC_DELETED).description("Audit entries soft-deleted").register(registry);
        this.erased  = Counter.builder(METRIC_ERASED).description("Audit entries with personal data erased").register(registry);
    }

    @EventListener
    void onCreated(AuditLogCreatedEvent event) { created.increment(); }

    @EventListener
    void onDeleted(AuditLogDeletedEvent event) { deleted.increment(); }

    @EventListener
    void onErased(AuditLogErasedEvent event) { erased.increment(); }
}
