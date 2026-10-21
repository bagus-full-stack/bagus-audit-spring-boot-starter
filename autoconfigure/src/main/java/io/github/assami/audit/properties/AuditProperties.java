package io.github.assami.audit.properties;

import io.github.assami.audit.tenancy.TenantStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for the audit starter.
 *
 * <pre>{@code
 * audit:
 *   enabled: true
 *   name: order-service
 *   security:
 *     jwk-set-uri: https://issuer.example.com/.well-known/jwks.json  # only if the host defines no JwtDecoder
 *   tenancy:
 *     enabled: false
 *     strategy: JWT_CLAIM
 *     header-name: X-Tenant-ID
 *     jwt-claim: tenant_id
 *     enforce-isolation: false
 *   api:
 *     base-path: /api/audit
 * }</pre>
 */
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    /** Master switch to disable the entire starter. */
    private boolean enabled = true;

    /** Logical name of the service using this starter (used as {@code service_name}). */
    private String name = "unknown-service";

    @NestedConfigurationProperty
    private SecurityProperties security = new SecurityProperties();

    @NestedConfigurationProperty
    private TenancyProperties tenancy = new TenancyProperties();

    @NestedConfigurationProperty
    private ApiProperties api = new ApiProperties();

    // ── Getters / setters ────────────────────────────────────────────────────

    public boolean isEnabled()                  { return enabled; }
    public void setEnabled(boolean enabled)     { this.enabled = enabled; }

    public String getName()                     { return name; }
    public void setName(String name)            { this.name = name; }

    public SecurityProperties getSecurity()              { return security; }
    public void setSecurity(SecurityProperties security) { this.security = security; }

    public TenancyProperties getTenancy()                { return tenancy; }
    public void setTenancy(TenancyProperties tenancy)    { this.tenancy = tenancy; }

    public ApiProperties getApi()              { return api; }
    public void setApi(ApiProperties api)      { this.api = api; }

    // ── Nested config classes ────────────────────────────────────────────────

    public static class SecurityProperties {
        /** JWKS endpoint used to build a JwtDecoder when the host application defines none. */
        private String jwkSetUri;

        public String getJwkSetUri()                 { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri)   { this.jwkSetUri = jwkSetUri; }
    }

    public static class TenancyProperties {
        private boolean enabled = false;
        // JWT_CLAIM by default: with HEADER the caller picks its own tenant.
        private TenantStrategy strategy = TenantStrategy.JWT_CLAIM;
        private String headerName = "X-Tenant-ID";
        private String jwtClaim = "tenant_id";
        private boolean enforceIsolation = false;

        public boolean isEnabled()                       { return enabled; }
        public void setEnabled(boolean enabled)          { this.enabled = enabled; }
        public TenantStrategy getStrategy()              { return strategy; }
        public void setStrategy(TenantStrategy strategy) { this.strategy = strategy; }
        public String getHeaderName()                    { return headerName; }
        public void setHeaderName(String headerName)     { this.headerName = headerName; }
        public String getJwtClaim()                      { return jwtClaim; }
        public void setJwtClaim(String jwtClaim)         { this.jwtClaim = jwtClaim; }
        public boolean isEnforceIsolation()              { return enforceIsolation; }
        public void setEnforceIsolation(boolean v)       { this.enforceIsolation = v; }
    }

    public static class ApiProperties {
        private String basePath = "/api/audit";

        public String getBasePath()                 { return basePath; }
        public void setBasePath(String basePath)    { this.basePath = basePath; }
    }
}
