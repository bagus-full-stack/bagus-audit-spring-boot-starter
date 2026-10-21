package io.github.assami.audit.observability;

import io.github.assami.audit.service.AuditService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer metrics for the audit library.
 *
 * <ul>
 *   <li>{@code audit.repository.size} — gauge of non-deleted entries</li>
 * </ul>
 */
public class AuditMetrics {

    public static final String METRIC_REPO_SIZE = "audit.repository.size";

    public AuditMetrics(MeterRegistry registry, AuditService service) {
        Gauge.builder(METRIC_REPO_SIZE, service, AuditService::count)
                .description("Total number of non-deleted audit log entries")
                .register(registry);
    }
}
