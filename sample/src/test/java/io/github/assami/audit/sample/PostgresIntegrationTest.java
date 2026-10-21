package io.github.assami.audit.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same scenarios as the H2 test, on a real PostgreSQL 16: Flyway V1 (partial and DESC indexes),
 * Hibernate schema validation, keyset queries and soft delete. Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "audit.security.jwk-set-uri=http://localhost:1/jwks",
        "audit.tenancy.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.driver-class-name=org.postgresql.Driver"})
@AutoConfigureMockMvc
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatedTheKeysetIndexes_andTheEntityValidates() {
        var indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'audit_log'", String.class);

        assertThat(indexes).contains("idx_audit_log_keyset", "idx_audit_log_tenant_keyset", "idx_audit_log_service_name");
    }

    @Test
    void crudLifecycle() throws Exception {
        AuditApiScenarios.crudLifecycle(mvc, AuditApiScenarios.user("admin", "acme", "AUDIT_ADMIN"));
    }

    @Test
    void softDelete_setsDeletedAt_insteadOfRemovingTheRow() throws Exception {
        var admin = AuditApiScenarios.user("admin", "acme", "AUDIT_ADMIN");
        String id = AuditApiScenarios.create(mvc, admin, "{\"action\":\"SOFT\",\"payload\":\"PII\"}");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/audit/" + id).with(admin));

        assertThat(jdbc.queryForObject("select count(*) from audit_log where id = ? and deleted_at is not null",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where id = ? "
                + "and payload is null and created_by is null and last_modified_by is null", Integer.class, id))
                .as("personal data is dropped on delete").isEqualTo(1);
    }

    @Test
    void paginationWalk_withManyRows() throws Exception {
        AuditApiScenarios.paginationWalk(mvc, AuditApiScenarios.user("admin", "acme", "AUDIT_ADMIN"), 60, 7);
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        AuditApiScenarios.tenantIsolation(mvc);
    }

    @Test
    void securityRules() throws Exception {
        AuditApiScenarios.security(mvc);
    }

    @Test
    void batch_isAtomic_aFailingRowRollsBackTheWholeBatch() throws Exception {
        var writer = AuditApiScenarios.user("w", "acme", "AUDIT_WRITE");
        int before = jdbc.queryForObject("select count(*) from audit_log", Integer.class);
        String tooLong = "x".repeat(300);   // action column is VARCHAR(255): the database rejects it

        int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/audit/batch")
                        .with(writer).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"action\":\"OK\"},{\"action\":\"" + tooLong + "\"}]}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(before);
    }
}
