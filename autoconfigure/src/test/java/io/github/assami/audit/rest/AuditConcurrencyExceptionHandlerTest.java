package io.github.assami.audit.rest;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.port.AuditRepository;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.security.AuditSecurityConfig;
import io.github.assami.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditController.class)
@Import({AuditConcurrencyExceptionHandlerTest.Config.class, AuditConcurrencyExceptionHandler.class,
        AuditExceptionHandler.class, AuditSecurityConfig.class})
class AuditConcurrencyExceptionHandlerTest {

    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(roles = "AUDIT_ADMIN")
    void aLostRace_is409_notAServerError() throws Exception {
        mvc.perform(delete("/api/audit/abc").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
    }

    @Configuration
    static class Config {
        @Bean AuditRepository auditRepository() { return new InMemoryAuditRepository(); }

        @Bean AuditProperties auditProperties() { return new AuditProperties(); }

        @Bean AuditController auditController(AuditService service) { return new AuditController(service); }

        // the security configuration needs a decoder to build the bearer-token chain
        @Bean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        }

        /** The delete loses the race against another change of the same entry. */
        @Bean AuditService auditService(AuditRepository repository, AuditProperties properties) {
            return new AuditService(repository, event -> { }, properties, Optional.empty()) {
                @Override public void delete(String id) {
                    throw new OptimisticLockingFailureException("version changed");
                }
            };
        }
    }
}
