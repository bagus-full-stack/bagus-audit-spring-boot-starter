package io.github.assami.audit.observability;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.native_.AuditRuntimeHints;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.rest.AuditController;
import io.github.assami.audit.service.AuditService;
import io.github.assami.audit.tenancy.TenantStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityAndConfigTest {

    @Test
    void health_isUp_whenTheRepositoryAnswers() {
        assertThat(new AuditHealthIndicator(new InMemoryAuditRepository()).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_isDown_andCarriesTheError_whenTheRepositoryFails() {
        AuditRepository broken = mock(AuditRepository.class);
        when(broken.findById("health-probe")).thenThrow(new IllegalStateException("db down"));

        var health = new AuditHealthIndicator(broken).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void health_doesNotCountRows() {
        AuditRepository repo = mock(AuditRepository.class);
        when(repo.findById("health-probe")).thenReturn(Optional.empty());

        new AuditHealthIndicator(repo).health();

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).count();
    }

    @Test
    void metrics_gaugeTracksTheNumberOfLiveEntries() {
        AuditProperties props = new AuditProperties();
        props.setName("svc");
        InMemoryAuditRepository repo = new InMemoryAuditRepository();
        AuditService service = new AuditService(repo, e -> {}, props, Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AuditMetrics(registry, service);

        assertThat(registry.get(AuditMetrics.METRIC_REPO_SIZE).gauge().value()).isZero();
        AuditLog a = service.create(AuditLog.builder().action("A"));
        service.create(AuditLog.builder().action("B"));
        assertThat(registry.get(AuditMetrics.METRIC_REPO_SIZE).gauge().value()).isEqualTo(2.0);
        service.delete(a.getId());
        assertThat(registry.get(AuditMetrics.METRIC_REPO_SIZE).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void nativeHints_coverTheModelAndTheRestDtos() {
        RuntimeHints hints = new RuntimeHints();
        new AuditRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{AuditLog.class,
                io.github.assami.audit.model.AuditPage.class, io.github.assami.audit.model.AuditQuery.class,
                io.github.assami.audit.model.AuditLevel.class,
                AuditController.CreateAuditRequest.class, AuditController.AuditLogResponse.class,
                AuditController.AuditPageResponse.class, AuditController.BatchAuditRequest.class,
                AuditController.BatchAuditResponse.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(type.getSimpleName()).accepts(hints);
        }
    }

    @Test
    void properties_haveSafeDefaults() {
        AuditProperties p = new AuditProperties();

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getApi().getBasePath()).isEqualTo("/api/audit");
        assertThat(p.getTenancy().isEnabled()).isFalse();
        assertThat(p.getTenancy().getStrategy()).isEqualTo(TenantStrategy.JWT_CLAIM);
        assertThat(p.getTenancy().isEnforceIsolation()).isTrue();
        assertThat(p.getSecurity().getRolesClaim()).isEqualTo("roles");
        assertThat(p.getSecurity().getJwkSetUri()).isNull();
    }

    @Test
    void properties_bindFromRelaxedNames() {
        var source = new MapConfigurationPropertySource(Map.of(
                "audit.name", "orders",
                "audit.tenancy.enabled", "true",
                "audit.tenancy.strategy", "subdomain",
                "audit.tenancy.enforce-isolation", "true",
                "audit.tenancy.jwt-claim", "org",
                "audit.security.roles-claim", "groups",
                "audit.security.jwk-set-uri", "https://idp/jwks",
                "audit.api.base-path", "/internal/audit"));

        AuditProperties p = new Binder(source).bind("audit", AuditProperties.class).get();

        assertThat(p.getName()).isEqualTo("orders");
        assertThat(p.getTenancy().isEnabled()).isTrue();
        assertThat(p.getTenancy().getStrategy()).isEqualTo(TenantStrategy.SUBDOMAIN);
        assertThat(p.getTenancy().isEnforceIsolation()).isTrue();
        assertThat(p.getTenancy().getJwtClaim()).isEqualTo("org");
        assertThat(p.getSecurity().getRolesClaim()).isEqualTo("groups");
        assertThat(p.getSecurity().getJwkSetUri()).isEqualTo("https://idp/jwks");
        assertThat(p.getApi().getBasePath()).isEqualTo("/internal/audit");
    }
}
