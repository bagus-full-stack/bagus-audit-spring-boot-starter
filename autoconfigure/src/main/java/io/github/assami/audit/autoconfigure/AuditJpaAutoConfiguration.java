package io.github.assami.audit.autoconfigure;

import io.github.assami.audit.adapter.jpa.AuditLogEntity;
import io.github.assami.audit.adapter.jpa.AuditLogJpaRepository;
import io.github.assami.audit.adapter.jpa.JpaAuditRepository;
import io.github.assami.audit.port.AuditRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import jakarta.persistence.EntityManager;

/**
 * JPA-specific auto-configuration.
 * Activated only when Spring Data JPA is on the classpath.
 */
@AutoConfiguration(before = AuditAutoConfiguration.class)
@ConditionalOnClass({EntityManager.class, AuditLogEntity.class})
@EnableJpaRepositories(
        basePackages = "io.github.assami.audit.adapter.jpa",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef   = "transactionManager"
)
public class AuditJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditRepository.class)
    public AuditRepository jpaAuditRepository(AuditLogJpaRepository jpaRepository) {
        return new JpaAuditRepository(jpaRepository);
    }
}
