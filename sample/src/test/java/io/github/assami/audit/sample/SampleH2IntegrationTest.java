package io.github.assami.audit.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts the whole sample (default profile: H2 + Flyway migration + Hibernate "validate") and drives it over HTTP.
 * Fails if the Flyway schema and the JPA entity disagree.
 */
@SpringBootTest(properties = {
        "audit.security.jwk-set-uri=http://localhost:1/jwks",   // builds a decoder lazily, never called here
        "audit.tenancy.enabled=true"})
@AutoConfigureMockMvc
class SampleH2IntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void contextStarts_andTheSchemaMatchesTheEntity() {
        // reaching this line means Flyway ran V1 and Hibernate validated it
    }

    @Test
    void crudLifecycle() throws Exception {
        AuditApiScenarios.crudLifecycle(mvc, AuditApiScenarios.user("admin", "acme", "AUDIT_ADMIN"));
    }

    @Test
    void paginationWalk_withManyRows() throws Exception {
        AuditApiScenarios.paginationWalk(mvc, AuditApiScenarios.user("admin", "acme", "AUDIT_ADMIN"), 45, 7);
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        AuditApiScenarios.tenantIsolation(mvc);
    }

    @Test
    void securityRules() throws Exception {
        AuditApiScenarios.security(mvc);
    }
}
