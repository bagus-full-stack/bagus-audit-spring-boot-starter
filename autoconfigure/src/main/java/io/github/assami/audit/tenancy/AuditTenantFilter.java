package io.github.assami.audit.tenancy;

import io.github.assami.audit.exception.MissingTenantException;
import io.github.assami.audit.properties.AuditProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Servlet filter that resolves the current tenant and stores it in {@link TenantContext}.
 * Only audit API requests are filtered, so other routes (actuator, host endpoints) are unaffected.
 * Runs once per request; clears the context in a {@code finally} block.
 */
public class AuditTenantFilter extends OncePerRequestFilter {

    private final AuditProperties properties;
    private final HandlerExceptionResolver exceptionResolver;

    /**
     * @param exceptionResolver turns {@link MissingTenantException} into the same 400 problem
     *                          response as the controller advice; a filter's exceptions never reach it
     */
    public AuditTenantFilter(AuditProperties properties, HandlerExceptionResolver exceptionResolver) {
        this.properties = properties;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String tenantId = resolveTenant(request);
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.set(tenantId);
            } else if (properties.getTenancy().isEnforceIsolation()) {
                exceptionResolver.resolveException(request, response, null, new MissingTenantException());
                return;
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String resolveTenant(HttpServletRequest request) {
        AuditProperties.TenancyProperties tenancy = properties.getTenancy();
        return switch (tenancy.getStrategy()) {
            case HEADER    -> request.getHeader(tenancy.getHeaderName());
            case JWT_CLAIM -> resolveFromJwt(tenancy.getJwtClaim());
            case SUBDOMAIN -> resolveFromSubdomain(request.getServerName());
        };
    }

    private String resolveFromJwt(String claimName) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof Jwt jwt) {
            return jwt.getClaimAsString(claimName);
        }
        return null;
    }

    private String resolveFromSubdomain(String serverName) {
        if (serverName == null) return null;
        int dot = serverName.indexOf('.');
        return dot > 0 ? serverName.substring(0, dot) : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.getTenancy().isEnabled()) return true;
        String base = request.getContextPath() + properties.getApi().getBasePath();
        String uri  = request.getRequestURI();
        return !(uri.equals(base) || uri.startsWith(base + "/"));
    }
}
