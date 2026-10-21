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
 *     enforce-isolation: true
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

    @NestedConfigurationProperty
    private RetentionProperties retention = new RetentionProperties();

    // ── Getters / setters ────────────────────────────────────────────────────

    public RetentionProperties getRetention()                   { return retention; }
    public void setRetention(RetentionProperties retention)     { this.retention = retention; }

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

        /** JWT claim holding the role names, for example {@code ["AUDIT_READ"]}. Each gets the {@code ROLE_} prefix. */
        private String rolesClaim = "roles";

        /** Required {@code iss} claim. Only applies to the decoder built from {@code jwk-set-uri}. */
        private String issuer;

        /** Required value in the {@code aud} claim. Only applies to the decoder built from {@code jwk-set-uri}. */
        private String audience;

        /** Claim used as the caller name in {@code created_by}. Keycloak's {@code sub} is a UUID: use {@code preferred_username}. */
        private String principalClaim = "sub";

        public String getPrincipalClaim()                    { return principalClaim; }
        public void setPrincipalClaim(String principalClaim) { this.principalClaim = principalClaim; }
        public String getIssuer()                    { return issuer; }
        public void setIssuer(String issuer)         { this.issuer = issuer; }
        public String getAudience()                  { return audience; }
        public void setAudience(String audience)     { this.audience = audience; }
        public String getJwkSetUri()                 { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri)   { this.jwkSetUri = jwkSetUri; }
        public String getRolesClaim()                { return rolesClaim; }
        public void setRolesClaim(String rolesClaim) { this.rolesClaim = rolesClaim; }
    }

    public static class TenancyProperties {
        private boolean enabled = false;
        // JWT_CLAIM by default: with HEADER the caller picks its own tenant.
        private TenantStrategy strategy = TenantStrategy.JWT_CLAIM;
        private String headerName = "X-Tenant-ID";
        private String jwtClaim = "tenant_id";
        // true: with tenancy on, a request without a tenant would otherwise see every tenant's entries.
        private boolean enforceIsolation = true;

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

    public static class RetentionProperties {
        /** Days to keep soft-deleted entries before they are removed for good. 0 keeps them forever. */
        private int deletedDays = 0;

        /** Rows removed per statement by the purge job; a large backlog is cleared in several short transactions. */
        private int batchSize = 5000;

        /** Spring cron expression for the purge job. */
        private String cron = "0 30 3 * * *";

        public int getDeletedDays()                  { return deletedDays; }
        public void setDeletedDays(int deletedDays)  { this.deletedDays = deletedDays; }
        public int getBatchSize()                    { return batchSize; }
        public void setBatchSize(int batchSize)      { this.batchSize = batchSize; }
        public String getCron()                      { return cron; }
        public void setCron(String cron)             { this.cron = cron; }
    }

    public static class ApiProperties {
        private String basePath = "/api/audit";

        public String getBasePath()                 { return basePath; }
        public void setBasePath(String basePath)    { this.basePath = basePath; }
    }
}
