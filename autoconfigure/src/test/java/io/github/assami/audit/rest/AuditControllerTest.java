package io.github.assami.audit.rest;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuditController.class)
@Import({AuditControllerTest.TestConfig.class, AuditExceptionHandler.class, io.github.assami.audit.security.AuditSecurityConfig.class})
class AuditControllerTest {

    @Autowired
    MockMvc mvc;

    // ── POST — 201 Created ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void createAuditLog_returns201() throws Exception {
        mvc.perform(post("/api/audit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ORDER_PLACED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.action").value("ORDER_PLACED"));
    }

    // ── POST — 403 Forbidden (wrong role) ─────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_READ")
    void createAuditLog_withReadRole_returns403() throws Exception {
        mvc.perform(post("/api/audit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    // ── POST — 401 Unauthenticated ────────────────────────────────────────────

    @Test
    void createAuditLog_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/audit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET — paginated list ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_READ")
    void getAll_returns200() throws Exception {
        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── GET — 403 without read role ───────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void getAll_withWriteRoleOnly_returns403() throws Exception {
        mvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden());
    }

    // ── DELETE — admin only ───────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_READ")
    void delete_withReadRole_returns403() throws Exception {
        mvc.perform(delete("/api/audit/some-id").with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── POST validation — missing action ─────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void createAuditLog_missingAction_returns400() throws Exception {
        mvc.perform(post("/api/audit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"no action\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── Test configuration ────────────────────────────────────────────────────

    @Configuration
    static class TestConfig {

        @Bean
        AuditRepository auditRepository() {
            return new InMemoryAuditRepository();
        }

        @Bean
        AuditProperties auditProperties() {
            AuditProperties p = new AuditProperties();
            p.setName("test-service");
            return p;
        }

        @Bean
        AuditService auditService(AuditRepository repo,
                                  org.springframework.context.ApplicationEventPublisher pub,
                                  AuditProperties props) {
            return new AuditService(repo, pub, props, Optional.empty());
        }

        @Bean
        org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        }

        @Bean
        AuditController auditController(AuditService service) {
            return new AuditController(service);
        }
    }
}
