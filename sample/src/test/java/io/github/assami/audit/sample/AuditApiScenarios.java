package io.github.assami.audit.sample;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full-stack scenarios (real security chain, real JPA, real Flyway schema) shared by the H2 and PostgreSQL tests. */
final class AuditApiScenarios {

    private AuditApiScenarios() {}

    static RequestPostProcessor user(String name, String tenant, String... roles) {
        SimpleGrantedAuthority[] authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toArray(SimpleGrantedAuthority[]::new);
        return jwt().jwt(j -> { j.subject(name); if (tenant != null) j.claim("tenant_id", tenant); })
                .authorities(authorities);
    }

    static String create(MockMvc mvc, RequestPostProcessor who, String json) throws Exception {
        String body = mvc.perform(post("/api/audit").with(who).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    static void crudLifecycle(MockMvc mvc, RequestPostProcessor admin) throws Exception {
        String id = create(mvc, admin, "{\"action\":\"E2E_CREATE\",\"level\":\"WARN\",\"payload\":\"{\\\"k\\\":1}\"}");

        mvc.perform(get("/api/audit/" + id).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("E2E_CREATE"))
                .andExpect(jsonPath("$.level").value("WARN"))
                .andExpect(jsonPath("$.createdBy").value(org.hamcrest.Matchers.not("")))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/audit").param("action", "E2E_CREATE").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id));

        mvc.perform(delete("/api/audit/" + id + "/personal-data").with(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/audit/" + id).with(admin)).andExpect(jsonPath("$.gdprErased").value(true))
                .andExpect(jsonPath("$.payload").isEmpty()).andExpect(jsonPath("$.createdBy").isEmpty());

        mvc.perform(delete("/api/audit/" + id).with(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/audit/" + id).with(admin)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/audit/" + id).with(admin)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/audit/" + id + "/personal-data").with(admin)).andExpect(status().isNotFound());
    }

    static void paginationWalk(MockMvc mvc, RequestPostProcessor admin, int count, int pageSize) throws Exception {
        Set<String> created = new HashSet<>();
        for (int i = 0; i < count; i++) created.add(create(mvc, admin, "{\"action\":\"WALK\"}"));

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/api/audit").param("action", "WALK").param("size", String.valueOf(pageSize)).with(admin);
            if (cursor != null) request.param("cursor", cursor);
            String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            java.util.List<String> ids = JsonPath.read(body, "$.content[*].id");
            for (String id : ids) assertThat(seen.add(id)).as("no row repeated").isTrue();
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null && pages < 1000);

        assertThat(seen).containsAll(created);
        assertThat(pages).isGreaterThanOrEqualTo(count / pageSize);
    }

    static void tenantIsolation(MockMvc mvc) throws Exception {
        RequestPostProcessor acmeWriter = user("w", "acme", "AUDIT_WRITE");
        RequestPostProcessor acmeReader = user("r", "acme", "AUDIT_READ");
        RequestPostProcessor acmeAdmin = user("a", "acme", "AUDIT_ADMIN");
        RequestPostProcessor globexReader = user("g", "globex", "AUDIT_READ");
        RequestPostProcessor globexAdmin = user("ga", "globex", "AUDIT_ADMIN");
        String id = create(mvc, acmeWriter, "{\"action\":\"TENANT_ONLY\"}");

        mvc.perform(get("/api/audit/" + id).with(acmeReader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"));
        mvc.perform(get("/api/audit/" + id).with(globexReader)).andExpect(status().isNotFound());
        mvc.perform(get("/api/audit").param("action", "TENANT_ONLY").with(globexReader))
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/audit").param("action", "TENANT_ONLY").param("tenantId", "acme").with(globexReader))
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(delete("/api/audit/" + id).with(globexAdmin)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/audit/" + id + "/personal-data").with(globexAdmin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/audit/" + id).with(acmeAdmin)).andExpect(status().isOk());
    }

    static void security(MockMvc mvc) throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit").with(user("w", null, "AUDIT_WRITE"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/audit").with(user("r", null, "AUDIT_READ")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"A\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        int other = mvc.perform(get("/not-an-audit-route")).andReturn().getResponse().getStatus();
        assertThat(other).as("routes outside the audit path stay protected").isIn(302, 401, 403);
    }
}
