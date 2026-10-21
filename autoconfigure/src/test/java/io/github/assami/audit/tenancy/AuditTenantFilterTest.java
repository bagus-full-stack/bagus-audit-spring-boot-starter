package io.github.assami.audit.tenancy;

import io.github.assami.audit.exception.MissingTenantException;
import io.github.assami.audit.properties.AuditProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditTenantFilterTest {

    private AuditProperties props;
    private HandlerExceptionResolver resolver;
    private AuditTenantFilter filter;

    @BeforeEach
    void setUp() {
        props = new AuditProperties();
        props.getTenancy().setEnabled(true);
        resolver = mock(HandlerExceptionResolver.class);
        filter = new AuditTenantFilter(props, resolver);
    }

    @Test
    void headerStrategy_setsTenantDuringChain_andClearsAfter() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "acme");
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.get());

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("acme");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void defaultStrategy_readsTheJwtClaim_andIgnoresTheHeader() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("tenant_id", "from-jwt").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "from-header");
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> seen.set(TenantContext.get()));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(seen.get()).isEqualTo("from-jwt");
    }

    @Test
    void missingTenant_withEnforcement_isHandledNotThrown_andChainSkipped() throws Exception {
        props.getTenancy().setEnforceIsolation(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit/x");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        verify(resolver).resolveException(any(), any(), isNull(), any(MissingTenantException.class));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void requestsOutsideTheAuditPath_areNotFiltered() throws Exception {
        props.getTenancy().setEnforceIsolation(true);
        FilterChain chain = mock(FilterChain.class);
        for (String uri : new String[]{"/actuator/health", "/api/auditor"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, chain);

            verify(chain).doFilter(req, res);
        }
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }
}
