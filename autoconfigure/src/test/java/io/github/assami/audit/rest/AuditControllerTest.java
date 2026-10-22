package io.github.assami.audit.rest;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.service.AuditService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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

    // ── Actor comes from the authenticated user ──────────────────────────────

    @Test
    @WithMockUser(username = "alice", roles = "AUDIT_WRITE")
    void createAuditLog_actorIsTheAuthenticatedUser_notTheBody() throws Exception {
        mvc.perform(post("/api/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACTOR_TEST\",\"actor\":\"mallory\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.lastModifiedBy").value("alice"));
    }

    // ── Batch ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void batch_returns201_andSavesEveryEntry() throws Exception {
        mvc.perform(post("/api/audit/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"action\":\"B1\"},{\"action\":\"B2\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("B1"))
                .andExpect(jsonPath("$[1].action").value("B2"));
    }

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void batch_emptyOrTooLarge_returns400() throws Exception {
        mvc.perform(post("/api/audit/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[]}"))
                .andExpect(status().isBadRequest());

        String tooMany = "{\"entries\":[" + String.join(",", Collections.nCopies(101, "{\"action\":\"X\"}")) + "]}";
        mvc.perform(post("/api/audit/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooMany))
                .andExpect(status().isBadRequest());
    }

    // ── Delete and GDPR erasure ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_ADMIN")
    void delete_asAdmin_returns204_thenEntryIsGone() throws Exception {
        String id = createAndReturnId("{\"action\":\"TO_DELETE\"}");

        mvc.perform(delete("/api/audit/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/audit/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "AUDIT_ADMIN")
    void erasePersonalData_asAdmin_clearsPayloadAndActor() throws Exception {
        String id = createAndReturnId("{\"action\":\"TO_ERASE\",\"payload\":\"secret\"}");

        mvc.perform(delete("/api/audit/" + id + "/personal-data")).andExpect(status().isNoContent());
        mvc.perform(get("/api/audit/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gdprErased").value(true))
                .andExpect(jsonPath("$.payload").isEmpty())
                .andExpect(jsonPath("$.createdBy").isEmpty())
                .andExpect(jsonPath("$.lastModifiedBy").isEmpty());
    }

    // ── Bad cursor ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_READ")
    void getAll_malformedCursor_returns400() throws Exception {
        mvc.perform(get("/api/audit").param("cursor", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ── JWT authorities ──────────────────────────────────────────────────────

    @Test
    void jwt_roleDecidesAccess() throws Exception {
        mvc.perform(get("/api/audit").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_READ"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDIT_WRITE"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit"))
                .andExpect(status().isUnauthorized());
    }

    private String createAndReturnId(String json) throws Exception {
        String body = mvc.perform(post("/api/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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
