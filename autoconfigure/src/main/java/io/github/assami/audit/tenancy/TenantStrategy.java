package io.github.assami.audit.tenancy;

/**
 * Strategies for resolving the current tenant identifier.
 */
public enum TenantStrategy {

    /**
     * Read tenant from an HTTP request header (default: {@code X-Tenant-ID}).
     * The caller chooses the value, so use it only behind a gateway that sets the header;
     * otherwise prefer {@link #JWT_CLAIM}.
     */
    HEADER,

    /**
     * Extract tenant from a JWT claim (default claim name: {@code tenant_id}).
     */
    JWT_CLAIM,

    /**
     * Derive tenant from the first segment of the request hostname
     * (e.g. {@code acme.example.com} → {@code acme}).
     */
    SUBDOMAIN
}
