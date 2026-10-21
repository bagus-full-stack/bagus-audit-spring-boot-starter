package io.github.assami.audit.tenancy;

/**
 * ThreadLocal holder for the current tenant identifier.
 * <p>
 * The {@link AuditTenantFilter} sets the value on inbound requests;
 * the service layer reads it automatically when building log entries.
 * Always call {@link #clear()} in a {@code finally} block when setting
 * the value manually.
 * </p>
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new InheritableThreadLocal<>();

    private TenantContext() {}

    public static void set(String tenantId) {
        TENANT.set(tenantId);
    }

    public static String get() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }

    public static boolean isEmpty() {
        String v = TENANT.get();
        return v == null || v.isBlank();
    }
}
