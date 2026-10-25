package io.github.assami.audit.model;

import java.util.List;

/**
 * Cursor-based page of audit logs.
 *
 * @param content    entries in this page
 * @param nextCursor Base64-encoded opaque cursor, null if last page
 * @param hasNext    true when there is a subsequent page
 */
public record AuditPage(
        List<AuditLog> content,
        String nextCursor,
        boolean hasNext
) {
    public static AuditPage of(List<AuditLog> content, String nextCursor) {
        return new AuditPage(content, nextCursor, nextCursor != null);
    }
}
