package io.github.assami.audit.autoconfigure;

import io.github.assami.audit.port.AuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOptInTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AuditAutoConfiguration.class, SecurityAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void withoutOptIn_noInMemoryRepositoryIsCreated() {
        runner.run(ctx -> assertThat(ctx).hasFailed().getFailure().hasMessageContaining("AuditRepository"));
    }

    @Test
    void withOptIn_inMemoryRepositoryIsCreated() {
        runner.withPropertyValues("audit.in-memory=true")
                .run(ctx -> assertThat(ctx).hasBean("inMemoryAuditRepository"));
    }
}
