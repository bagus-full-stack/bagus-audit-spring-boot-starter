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
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        // A zone with daylight saving: every test runs where a plain TIMESTAMP column would be ambiguous.
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'Europe/Paris'"})
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

    @Autowired io.github.assami.audit.port.AuditRepository repository;

    @Test
    void instantColumns_areTimestamptz() {
        var types = jdbc.queryForList("select data_type from information_schema.columns where table_name = 'audit_log' "
                + "and column_name in ('created_at','updated_at','deleted_at','gdpr_erased_at')", String.class);

        assertThat(types).hasSize(4).containsOnly("timestamp with time zone");
    }

    @Test
    void instantsInTheRepeatedDaylightSavingHour_keepTheirIdentityAndOrder() {
        // 2026-10-25 in Paris: 02:30 happens twice (00:30Z and 01:30Z).
        var first = java.time.Instant.parse("2026-10-25T00:30:00Z");
        var second = java.time.Instant.parse("2026-10-25T01:30:00Z");
        String a = repository.save(io.github.assami.audit.model.AuditLog.builder().serviceName("dst").action("DST")
                .id("dst-a").createdAt(first).build()).getId();
        String b = repository.save(io.github.assami.audit.model.AuditLog.builder().serviceName("dst").action("DST")
                .id("dst-b").createdAt(second).build()).getId();

        var page = repository.findAll(io.github.assami.audit.model.AuditQuery.builder().action("DST").pageSize(1).build());
        var next = repository.findAll(io.github.assami.audit.model.AuditQuery.builder().action("DST").pageSize(1)
                .cursor(page.nextCursor()).build());

        assertThat(page.content()).extracting(io.github.assami.audit.model.AuditLog::getId).containsExactly(b);
        assertThat(page.content().get(0).getCreatedAt()).isEqualTo(second);
        assertThat(next.content()).extracting(io.github.assami.audit.model.AuditLog::getId).containsExactly(a);
        assertThat(next.content().get(0).getCreatedAt()).isEqualTo(first);
    }

    @Test
    void purgeDeleted_removesOnlyOldDeletedRows() {
        jdbc.update("insert into audit_log (id, service_name, action, deleted_at) values "
                + "('purge-old','s','PURGE', now() - interval '40 days'),"
                + "('purge-new','s','PURGE', now() - interval '1 day'),"
                + "('purge-live','s','PURGE', null)");

        long removed = repository.purgeDeleted(java.time.Instant.now().minus(java.time.Duration.ofDays(30)));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("select id from audit_log where action = 'PURGE' order by id", String.class))
                .containsExactly("purge-live", "purge-new");
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
