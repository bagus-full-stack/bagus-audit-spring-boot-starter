package io.github.assami.audit.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole sample (default profile: H2 + Flyway migration + Hibernate "validate") and drives it over HTTP.
 * Fails if the Flyway schema and the JPA entity disagree.
 */
@SpringBootTest(properties = {
        "audit.security.jwk-set-uri=http://localhost:1/jwks",
        "audit.security.issuer=http://test-issuer",   // builds a decoder lazily, never called here
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

    /**
     * POST /ask only reads, so it must need AUDIT_READ, not the write role of other POSTs. The sample has no assistant,
     * so a reader passes security and hits "/{id}", which only allows GET (405), while a writer is refused (403): both flip if the rule goes.
     */
    @Test
    void askRule_needsTheReadRole_notTheWriteRole() throws Exception {
        String body = "{\"question\":\"what happened\"}";
        mvc.perform(post("/api/audit/ask").with(AuditApiScenarios.user("r", "acme", "AUDIT_READ"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/audit/ask").with(AuditApiScenarios.user("w", "acme", "AUDIT_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void garbageCursor_is400_notAServerError() throws Exception {
        mvc.perform(get("/api/audit").param("cursor", "abc")
                        .with(AuditApiScenarios.user("admin", "acme", "AUDIT_ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSessionCookieDoesNotAuthenticateTheAuditApi() throws Exception {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_AUDIT_ADMIN"))));
        // Bearer tokens only: with CSRF off, a browser session must not be able to reach DELETE.
        mvc.perform(get("/api/audit").sessionAttr(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context))
                .andExpect(status().isUnauthorized());
    }
}
