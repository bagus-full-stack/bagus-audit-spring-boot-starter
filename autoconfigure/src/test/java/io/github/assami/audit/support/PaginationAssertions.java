package io.github.assami.audit.support;

import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.port.AuditRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared invariant checks: a cursor walk returns every row exactly once, newest first. */
public final class PaginationAssertions {

    private PaginationAssertions() {}

    public static List<AuditLog> walk(AuditRepository repo, int pageSize) {
        List<AuditLog> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 100_000; guard++) {
            AuditPage page = repo.findAll(AuditQuery.builder().cursor(cursor).pageSize(pageSize).build());
            assertThat(page.content().size()).isLessThanOrEqualTo(Math.max(1, Math.min(pageSize, 200)));
            all.addAll(page.content());
            cursor = page.nextCursor();
            assertThat(page.hasNext()).as("hasNext matches nextCursor").isEqualTo(cursor != null);
            if (cursor == null) return all;
        }
        throw new AssertionError("cursor walk did not terminate");
    }

    public static void assertWalkIsExact(AuditRepository repo, Set<String> expectedIds, int pageSize) {
        List<AuditLog> walked = walk(repo, pageSize);
        assertThat(walked).as("page size %d: no row lost or repeated", pageSize).hasSize(expectedIds.size());
        assertThat(new HashSet<>(walked.stream().map(AuditLog::getId).toList())).isEqualTo(expectedIds);
        for (int i = 1; i < walked.size(); i++) {
            AuditLog a = walked.get(i - 1), b = walked.get(i);
            int byTime = a.getCreatedAt().compareTo(b.getCreatedAt());
            assertThat(byTime > 0 || (byTime == 0 && a.getId().compareTo(b.getId()) > 0))
                    .as("strictly newest first at index %d", i).isTrue();
        }
    }

    /** Saves {@code count} logs whose timestamps collide on purpose (few distinct instants, tiny offsets). */
    public static Set<String> saveWithCollidingTimestamps(AuditRepository repo, int count, long seed) {
        Random random = new Random(seed);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Instant at = base.plusSeconds(random.nextInt(8)).plusNanos(random.nextInt(3) * 1_000L);
            AuditLog saved = repo.save(AuditLog.builder().id(UUID.nameUUIDFromBytes(("id" + seed + i).getBytes()).toString())
                    .serviceName("svc").action("A" + i).createdAt(at).build());
            ids.add(saved.getId());
        }
        return ids;
    }
}
