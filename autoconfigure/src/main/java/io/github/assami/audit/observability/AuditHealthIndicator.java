package io.github.assami.audit.observability;

import io.github.assami.audit.port.AuditRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator health indicator for the audit subsystem.
 * Reports {@code UP} when the repository answers a cheap lookup.
 */
public class AuditHealthIndicator implements HealthIndicator {

    private final AuditRepository repository;

    public AuditHealthIndicator(AuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            // Primary-key lookup: COUNT(*) on a growing table would time out the probe.
            repository.findById("health-probe");
            return Health.up().build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
