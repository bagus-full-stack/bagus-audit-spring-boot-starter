package io.github.assami.audit.autoconfigure;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.condition.ConditionalOnAuditEnabled;
import io.github.assami.audit.native_.AuditRuntimeHints;
import io.github.assami.audit.retention.AuditPurgeJob;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.github.assami.audit.observability.AuditHealthIndicator;
import io.github.assami.audit.observability.AuditMetrics;
import io.github.assami.audit.port.AuditGdprEraser;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.rest.AuditController;
import io.github.assami.audit.rest.AuditExceptionHandler;
import io.github.assami.audit.security.AuditSecurityConfig;
import io.github.assami.audit.service.AuditService;
import io.github.assami.audit.tenancy.AuditTenantFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Optional;

/**
 * Root auto-configuration for the audit starter.
 *
 * <p>Order of bean registration:
 * <ol>
 *   <li>Properties binding</li>
 *   <li>Repository (in-memory fallback)</li>
 *   <li>Service</li>
 *   <li>REST layer (controller + exception handler)</li>
 *   <li>Security configuration</li>
 *   <li>Tenancy filter</li>
 *   <li>Observability (metrics + health)</li>
 * </ol>
 * </p>
 */
@AutoConfiguration
@ConditionalOnAuditEnabled
@EnableConfigurationProperties(AuditProperties.class)
@Import({AuditSecurityConfig.class})
@ImportRuntimeHints(AuditRuntimeHints.class)
public class AuditAutoConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuditAutoConfiguration.class);

    // ── Repository (JPA takes precedence; in-memory only with audit.in-memory=true) ──

    @Bean
    @ConditionalOnMissingBean(AuditRepository.class)
    @ConditionalOnProperty(name = "audit.in-memory", havingValue = "true")
    public AuditRepository inMemoryAuditRepository() {
        log.warn("audit.in-memory=true: using the in-memory repository. "
                + "Audit entries are lost on restart and memory is unbounded. "
                + "Add audit-starter-jpa (or your own AuditRepository) for production.");
        return new InMemoryAuditRepository();
    }

    // ── Service ───────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public AuditService auditService(AuditRepository repository,
                                     ApplicationEventPublisher publisher,
                                     AuditProperties properties,
                                     Optional<AuditGdprEraser> gdprEraser) {
        return new AuditService(repository, publisher, properties, gdprEraser);
    }

    // ── REST ─────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public AuditController auditController(AuditService service) {
        return new AuditController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditExceptionHandler auditExceptionHandler() {
        return new AuditExceptionHandler();
    }

    // ── Tenancy filter ────────────────────────────────────────────────────────

    // Always registered and switched at runtime: in a GraalVM native image @ConditionalOn* is decided at build time,
    // so an environment variable set when the container starts would be ignored.
    @Bean
    @ConditionalOnMissingBean
    public AuditTenantFilter auditTenantFilter(
            AuditProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
            org.springframework.web.servlet.HandlerExceptionResolver exceptionResolver) {
        return new AuditTenantFilter(properties, exceptionResolver);
    }

    // ── Retention (opt-in) ────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class RetentionConfiguration {

        @Bean
        @ConditionalOnMissingBean
        AuditPurgeJob auditPurgeJob(AuditService service, AuditProperties properties) {
            return new AuditPurgeJob(service, properties.getRetention().getDeletedDays());
        }
    }

    // ── Observability ─────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnMissingBean
    public AuditMetrics auditMetrics(MeterRegistry registry) {
        return new AuditMetrics(registry);
    }

    @Bean(name = "auditHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "auditHealthIndicator")
    public AuditHealthIndicator auditHealthIndicator(AuditRepository repository) {
        return new AuditHealthIndicator(repository);
    }
}
