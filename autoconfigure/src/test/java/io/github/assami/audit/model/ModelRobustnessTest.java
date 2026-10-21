package io.github.assami.audit.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRobustnessTest {

    // ── AuditCursor ──────────────────────────────────────────────────────────

    @Test
    void cursor_roundTrips_withNanosecondPrecision() {
        Instant at = Instant.parse("2026-05-01T10:15:30.123456789Z");
        AuditCursor decoded = AuditCursor.decode(new AuditCursor(at, "abc").encode());

        assertThat(decoded.createdAt()).isEqualTo(at);
        assertThat(decoded.id()).isEqualTo("abc");
    }

    @Test
    void cursor_idMayContainTheSeparator() {
        AuditCursor decoded = AuditCursor.decode(new AuditCursor(Instant.EPOCH, "a|b|c").encode());

        assertThat(decoded.id()).isEqualTo("a|b|c");
    }

    @Test
    void cursor_isUrlSafe() {
        String encoded = new AuditCursor(Instant.now(), UUID.randomUUID().toString()).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "!!!!", "bm90LWEtY3Vyc29y", "MjAyNi0wMS0wMXxpZA", "fHx8"})
    void cursor_garbageIsRejectedWithIllegalArgument(String garbage) {
        assertThatThrownBy(() -> AuditCursor.decode(garbage)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── AuditLog ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void log_blankActionOrServiceNameIsRejected(String blank) {
        assertThatThrownBy(() -> AuditLog.builder().serviceName("svc").action(blank).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuditLog.builder().serviceName(blank).action("A").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void log_defaults() {
        AuditLog a = AuditLog.builder().serviceName("s").action("A").build();
        AuditLog b = AuditLog.builder().serviceName("s").action("A").build();

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getLevel()).isEqualTo(AuditLevel.INFO);
        assertThat(a.getVersion()).isZero();
        assertThat(a.isDeleted()).isFalse();
        assertThat(a.isGdprErased()).isFalse();
        assertThat(a.getCreatedAt()).isNotNull();
    }

    @Test
    void log_isImmutable_softDeleteAndEraseReturnCopies() {
        AuditLog original = AuditLog.builder().serviceName("s").action("A")
                .payload("secret").createdBy("jane").lastModifiedBy("jane").build();

        AuditLog deleted = original.softDelete(Instant.now());
        AuditLog erased = original.markGdprErased(Instant.now());

        assertThat(original.isDeleted()).isFalse();
        assertThat(original.getPayload()).isEqualTo("secret");
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getPayload()).isNull();
        assertThat(deleted.getCreatedBy()).isNull();
        assertThat(deleted.getLastModifiedBy()).isNull();
        assertThat(erased.getPayload()).isNull();
        assertThat(erased.getCreatedBy()).isNull();
        assertThat(erased.getLastModifiedBy()).isNull();
        assertThat(erased.isGdprErased()).isTrue();
        assertThat(erased.getId()).isEqualTo(original.getId());
        assertThat(erased.getAction()).isEqualTo("A");
    }

    @Test
    void log_toStringDoesNotLeakThePayload() {
        AuditLog log = AuditLog.builder().serviceName("s").action("A").payload("TOP-SECRET").createdBy("jane").build();

        assertThat(log.toString()).doesNotContain("TOP-SECRET").doesNotContain("jane");
    }

    // ── AuditQuery ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -5, 0})
    void query_pageSizeBelowOneIsRaisedToOne(int size) {
        assertThat(AuditQuery.builder().pageSize(size).build().getPageSize()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 1_000, Integer.MAX_VALUE})
    void query_pageSizeAbove200IsCapped(int size) {
        assertThat(AuditQuery.builder().pageSize(size).build().getPageSize()).isEqualTo(200);
    }

    @Test
    void query_defaultPageSizeIs20() {
        assertThat(AuditQuery.builder().build().getPageSize()).isEqualTo(20);
    }

    @Test
    void query_forTenantOverridesOnlyTheTenant() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        AuditQuery original = AuditQuery.builder().tenantId("evil").serviceName("s").action("a")
                .level(AuditLevel.ERROR).from(from).to(from.plusSeconds(1)).cursor("c").pageSize(7).build();

        AuditQuery forced = original.forTenant("acme");

        assertThat(forced.getTenantId()).isEqualTo("acme");
        assertThat(original.getTenantId()).isEqualTo("evil");
        assertThat(forced.getServiceName()).isEqualTo("s");
        assertThat(forced.getAction()).isEqualTo("a");
        assertThat(forced.getLevel()).isEqualTo(AuditLevel.ERROR);
        assertThat(forced.getFrom()).isEqualTo(from);
        assertThat(forced.getCursor()).isEqualTo("c");
        assertThat(forced.getPageSize()).isEqualTo(7);
    }
}
