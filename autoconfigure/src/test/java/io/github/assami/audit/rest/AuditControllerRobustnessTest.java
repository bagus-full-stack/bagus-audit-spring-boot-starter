package io.github.assami.audit.rest;

import io.github.assami.audit.security.AuditSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuditController.class)
@ContextConfiguration(classes = AuditControllerTest.TestConfig.class)
@Import({AuditControllerTest.TestConfig.class, AuditExceptionHandler.class, AuditSecurityConfig.class})
class AuditControllerRobustnessTest {

    @Autowired MockMvc mvc;

    private String postJson(String json, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/audit").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    // ── Input validation ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void actionLength_boundaryIs255() throws Exception {
        postJson("{\"action\":\"" + "a".repeat(255) + "\"}", 201);
        postJson("{\"action\":\"" + "a".repeat(256) + "\"}", 400);
    }

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void payloadLength_boundaryIs65536() throws Exception {
        postJson("{\"action\":\"A\",\"payload\":\"" + "p".repeat(65_536) + "\"}", 201);
        postJson("{\"action\":\"A\",\"payload\":\"" + "p".repeat(65_537) + "\"}", 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"action\":\"\"}", "{\"action\":\"   \"}", "{\"action\":null}",
            "{\"action\":\"A\",\"level\":\"NOPE\"}", "{\"action\":\"A\",\"level\":\"info\"}",
            "{\"action\":[\"A\"]}",
            "not json", "{\"action\":\"A\"", "[]", "\"A\"", "null", ""})
    @WithMockUser(roles = "AUDIT_WRITE")
    void malformedOrInvalidBodies_are400_never500(String body) throws Exception {
        int status = mvc.perform(post("/api/audit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();

        assertThat(status).isBetween(400, 499);
    }

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void wrongContentType_is415() throws Exception {
        mvc.perform(post("/api/audit").contentType(MediaType.TEXT_PLAIN).content("{\"action\":\"A\"}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/audit").content("{\"action\":\"A\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void unknownJsonFields_areIgnored() throws Exception {
        postJson("{\"action\":\"A\",\"tenantId\":\"evil\",\"id\":\"forged\",\"createdBy\":\"mallory\",\"version\":99}", 201);
    }

    @Test
    @WithMockUser(username = "alice", roles = "AUDIT_WRITE")
    void forgedFields_neverReachTheStoredEntry() throws Exception {
        String body = postJson("{\"action\":\"A\",\"id\":\"forged\",\"createdBy\":\"mallory\",\"tenantId\":\"evil\"}", 201);

        assertThat(body).doesNotContain("forged").doesNotContain("mallory").doesNotContain("evil");
        assertThat(body).contains("\"createdBy\":\"alice\"");
    }

    // ── Query parameters ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"0,400", "-1,400", "201,400", "abc,400", "1,200", "200,200"})
    @WithMockUser(roles = "AUDIT_READ")
    void pageSize_boundaries(String size, int expected) throws Exception {
        mvc.perform(get("/api/audit").param("size", size)).andExpect(status().is(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "to"})
    @WithMockUser(roles = "AUDIT_READ")
    void badInstants_are400(String param) throws Exception {
        mvc.perform(get("/api/audit").param(param, "yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "AUDIT_READ")
    void badLevelFilter_is400() throws Exception {
        mvc.perform(get("/api/audit").param("level", "LOUD")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "!!!!", "bm90LWEtY3Vyc29y", "fHx8", "%00", "AAAA"})
    @WithMockUser(roles = "AUDIT_READ")
    void garbageCursors_are400(String cursor) throws Exception {
        mvc.perform(get("/api/audit").param("cursor", cursor)).andExpect(status().isBadRequest());
    }

    // ── Errors ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_READ")
    void unknownId_is404_withAProblemDetailBody() throws Exception {
        mvc.perform(get("/api/audit/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "AUDIT_ADMIN")
    void deleteAndEraseUnknownId_are404() throws Exception {
        mvc.perform(delete("/api/audit/nope")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/audit/nope/personal-data")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"..%2F..%2Fetc%2Fpasswd", "%00", "' OR 1=1 --", "<script>", "a%20b", "%E2%82%AC"})
    @WithMockUser(roles = "AUDIT_ADMIN")
    void hostileIds_neverCauseA5xx(String id) throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                get("/api/audit/{id}", id), delete("/api/audit/{id}", id), delete("/api/audit/{id}/personal-data", id)}) {
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isLessThan(500);
        }
    }

    @Test
    @WithMockUser(roles = "AUDIT_ADMIN")
    void unsupportedMethods_are405() throws Exception {
        mvc.perform(put("/api/audit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(patch("/api/audit/x")).andExpect(status().isMethodNotAllowed());
    }

    // ── Batch ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_WRITE")
    void batch_boundaries() throws Exception {
        String ok = batch(500, "{\"action\":\"X\"}");
        mvc.perform(post("/api/audit/batch").contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(500));
        for (String bad : new String[]{batch(501, "{\"action\":\"X\"}"), "{\"entries\":null}", "{}",
                "{\"entries\":[null]}", "{\"entries\":[{\"action\":\"\"}]}", "{\"entries\":[{\"action\":\"A\"},{}]}"}) {
            mvc.perform(post("/api/audit/batch").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    private static String batch(int n, String entry) {
        return "{\"entries\":[" + String.join(",", Collections.nCopies(n, entry)) + "]}";
    }

    // ── Payload fidelity ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AUDIT_ADMIN")
    void payloadsAreStoredVerbatim() throws Exception {
        for (String payload : new String[]{"<script>alert(1)<\\/script>", "caf\\u00e9 \\ud83d\\ude80", "line1\\nline2\\ttab",
                "{\\\"nested\\\":true}", "'; DROP TABLE audit_log; --"}) {
            String created = postJson("{\"action\":\"A\",\"payload\":\"" + payload + "\"}", 201);
            String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
            String expected = com.jayway.jsonpath.JsonPath.read("{\"p\":\"" + payload + "\"}", "$.p");

            mvc.perform(get("/api/audit/" + id)).andExpect(jsonPath("$.payload").value(expected));
        }
    }

    // ── Security matrix ──────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"GET,/api/audit", "GET,/api/audit/x", "POST,/api/audit", "POST,/api/audit/batch",
            "DELETE,/api/audit/x", "DELETE,/api/audit/x/personal-data"})
    void everyEndpointRequiresAuthentication(String method, String path) throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({
            "AUDIT_READ,GET,/api/audit,200", "AUDIT_READ,POST,/api/audit,403", "AUDIT_READ,DELETE,/api/audit/x,403",
            "AUDIT_WRITE,GET,/api/audit,403", "AUDIT_WRITE,POST,/api/audit,201", "AUDIT_WRITE,DELETE,/api/audit/x,403",
            "AUDIT_WRITE,DELETE,/api/audit/x/personal-data,403", "AUDIT_READ,DELETE,/api/audit/x/personal-data,403",
            "AUDIT_ADMIN,GET,/api/audit,200", "AUDIT_ADMIN,POST,/api/audit,201", "AUDIT_ADMIN,DELETE,/api/audit/x,404",
            "OTHER,GET,/api/audit,403", "OTHER,POST,/api/audit,403", "OTHER,DELETE,/api/audit/x,403"})
    void roleMatrix(String role, String method, String path, int expected) throws Exception {
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("u").roles(role);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"A\"}"))
                .andExpect(status().is(expected));
    }
}
