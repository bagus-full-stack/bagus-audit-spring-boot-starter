package io.github.assami.audit.autoconfigure;

import io.github.assami.audit.adapter.jpa.AuditLogEntity;
import io.github.assami.audit.adapter.jpa.AuditLogJpaRepository;
import io.github.assami.audit.adapter.jpa.JpaAuditRepository;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.rest.AuditConcurrencyExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import jakarta.persistence.EntityManager;

/**
 * JPA-specific auto-configuration.
 * Activated only when Spring Data JPA is on the classpath.
 */
@AutoConfiguration(before = AuditAutoConfiguration.class)
@ConditionalOnClass({EntityManager.class, AuditLogEntity.class})
// Adds this package to the entity and repository scan of Boot's own JPA auto-configuration. Do not use
// @EnableJpaRepositories here: it switches that scan off, and the host's own repositories are no longer found.
@AutoConfigurationPackage(basePackages = "io.github.assami.audit.adapter.jpa")
public class AuditJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditRepository.class)
    public AuditRepository jpaAuditRepository(AuditLogJpaRepository jpaRepository) {
        return new JpaAuditRepository(jpaRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditConcurrencyExceptionHandler auditConcurrencyExceptionHandler() {
        return new AuditConcurrencyExceptionHandler();
    }
}
