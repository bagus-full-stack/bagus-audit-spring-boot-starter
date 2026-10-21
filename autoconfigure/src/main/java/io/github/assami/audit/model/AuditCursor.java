package io.github.assami.audit.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Opaque keyset cursor {@code (created_at, id)}. Keeps the full {@link Instant}
 * precision: truncating to milliseconds skips rows created in the same millisecond.
 */
public record AuditCursor(Instant createdAt, String id) {

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static AuditCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            return new AuditCursor(Instant.parse(raw.substring(0, sep)), raw.substring(sep + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }
}
