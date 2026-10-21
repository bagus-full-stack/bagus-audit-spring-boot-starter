package io.github.assami.audit.tenancy;

import io.github.assami.audit.exception.MissingTenantException;
import io.github.assami.audit.exception.TenantMismatchException;
import io.github.assami.audit.properties.AuditProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TenantFilterRobustnessTest {

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

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private String tenantSeenFor(MockHttpServletRequest req) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> seen.set(TenantContext.get()));
        return seen.get();
    }

    @Test
    void subdomainStrategy_usesTheFirstHostLabel() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.SUBDOMAIN);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.setServerName("acme.example.com");

        assertThat(tenantSeenFor(req)).isEqualTo("acme");
    }

    @Test
    void subdomainStrategy_hostWithoutDotHasNoTenant() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.SUBDOMAIN);
        props.getTenancy().setEnforceIsolation(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.setServerName("localhost");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(resolver).resolveException(any(), any(), any(), any());
    }

    @Test
    void blankHeader_countsAsMissing() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        props.getTenancy().setEnforceIsolation(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "   ");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(resolver).resolveException(any(), any(), any(), any());
    }

    @Test
    void jwtWithoutTheClaim_hasNoTenant_whenNotEnforced() throws Exception {
        props.getTenancy().setEnforceIsolation(false);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("sub", "u").build()));

        assertThat(tenantSeenFor(new MockHttpServletRequest("GET", "/api/audit"))).isNull();
    }

    @Test
    void unauthenticatedRequest_hasNoJwtTenant() throws Exception {
        props.getTenancy().setEnforceIsolation(false);
        assertThat(tenantSeenFor(new MockHttpServletRequest("GET", "/api/audit"))).isNull();
    }

    @Test
    void customJwtClaimName_isHonoured() throws Exception {
        props.getTenancy().setJwtClaim("org");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("org", "globex").claim("tenant_id", "wrong").build()));

        assertThat(tenantSeenFor(new MockHttpServletRequest("GET", "/api/audit"))).isEqualTo("globex");
    }

    @Test
    void isolationIsEnforcedByDefault_aTokenWithoutTenantIsRejected() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("sub", "u").build()));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/audit"), new MockHttpServletResponse(), chain);

        verify(resolver).resolveException(any(), any(), any(), any(MissingTenantException.class));
        verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
    }

    @Test
    void headerNamingAnotherTenantThanTheToken_isRejected() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("tenant_id", "acme").build()));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "globex");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        verify(resolver).resolveException(any(), any(), any(), any(TenantMismatchException.class));
        verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
    }

    @Test
    void subdomainNamingAnotherTenantThanTheToken_isRejected() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.SUBDOMAIN);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("tenant_id", "acme").build()));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.setServerName("globex.example.com");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(resolver).resolveException(any(), any(), any(), any(TenantMismatchException.class));
    }

    @Test
    void headerMatchingTheToken_orATokenWithoutClaim_isAccepted() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "acme");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("tenant_id", "acme").build()));
        assertThat(tenantSeenFor(req)).isEqualTo("acme");

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("sub", "gateway").build()));
        assertThat(tenantSeenFor(req)).isEqualTo("acme");
    }

    @Test
    void tenantIsCleared_evenWhenTheChainThrows() {
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "acme");
        FilterChain boom = (r, s) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), boom))
                .isInstanceOf(ServletException.class);

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void disabledTenancy_neverTouchesTheContext() throws Exception {
        props.getTenancy().setEnabled(false);
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/audit");
        req.addHeader("X-Tenant-ID", "acme");

        assertThat(tenantSeenFor(req)).isNull();
    }

    @Test
    void contextPathIsTakenIntoAccount() throws Exception {
        props.getTenancy().setStrategy(TenantStrategy.HEADER);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/api/audit");
        req.setContextPath("/app");
        req.setRequestURI("/app/api/audit");
        req.addHeader("X-Tenant-ID", "acme");

        assertThat(tenantSeenFor(req)).isEqualTo("acme");
    }

    @Test
    void tenantContext_basics() {
        assertThat(TenantContext.isEmpty()).isTrue();
        TenantContext.set(" ");
        assertThat(TenantContext.isEmpty()).isTrue();
        TenantContext.set("acme");
        assertThat(TenantContext.isEmpty()).isFalse();
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void tenantContext_doesNotLeakToAnotherThreadAfterClear() throws Exception {
        TenantContext.set("acme");
        TenantContext.clear();
        AtomicReference<String> seen = new AtomicReference<>("unset");
        Thread t = new Thread(() -> seen.set(TenantContext.get()));
        t.start();
        t.join();

        assertThat(seen.get()).isNull();
    }
}
