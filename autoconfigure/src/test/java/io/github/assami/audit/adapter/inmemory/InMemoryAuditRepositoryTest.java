package io.github.assami.audit.adapter.inmemory;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditLevel;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.support.PaginationAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class InMemoryAuditRepositoryTest {

    private InMemoryAuditRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAuditRepository();
    }

    @Test
    void save_and_findById() {
        AuditLog log = AuditLog.builder()
                .serviceName("svc")
                .action("LOGIN")
                .build();

        repo.save(log);

        Optional<AuditLog> found = repo.findById(log.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getAction()).isEqualTo("LOGIN");
    }

    @Test
    void findById_notFound_returnsEmpty() {
        assertThat(repo.findById("nonexistent")).isEmpty();
    }

    @Test
    void softDelete_hidesEntry() {
        AuditLog log = repo.save(AuditLog.builder().serviceName("svc").action("X").build());
        repo.softDelete(log.getId());

        assertThat(repo.findById(log.getId())).isEmpty();
    }

    @Test
    void softDelete_unknownId_throws() {
        assertThatThrownBy(() -> repo.softDelete("unknown"))
                .isInstanceOf(AuditNotFoundException.class);
    }

    @Test
    void gdprErase_clearsPayload() {
        AuditLog log = repo.save(AuditLog.builder()
                .serviceName("svc").action("A").payload("sensitive").build());

        repo.gdprErase(log.getId());

        // Re-fetch is not possible after erase (payload is null) but entry is not soft-deleted
        assertThat(repo.findById(log.getId())).isPresent();
        assertThat(repo.findById(log.getId()).get().getPayload()).isNull();
        assertThat(repo.findById(log.getId()).get().isGdprErased()).isTrue();
    }

    @Test
    void findAll_filtersByAction() {
        repo.save(AuditLog.builder().serviceName("svc").action("LOGIN").build());
        repo.save(AuditLog.builder().serviceName("svc").action("LOGOUT").build());

        AuditQuery q = AuditQuery.builder().action("LOGIN").pageSize(10).build();
        AuditPage page = repo.findAll(q);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).getAction()).isEqualTo("LOGIN");
    }

    @Test
    void findAll_cursorPagination() {
        for (int i = 0; i < 5; i++) {
            repo.save(AuditLog.builder().serviceName("svc").action("A" + i).build());
        }

        AuditQuery first = AuditQuery.builder().pageSize(2).build();
        AuditPage page1 = repo.findAll(first);
        assertThat(page1.content()).hasSize(2);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();

        AuditQuery second = AuditQuery.builder()
                .cursor(page1.nextCursor()).pageSize(2).build();
        AuditPage page2 = repo.findAll(second);
        assertThat(page2.content()).hasSize(2);
    }

    @Test
    void findAll_cursorPagination_sameMillisecond_losesNothing() {
        Instant base = Instant.parse("2026-01-01T00:00:00.001000Z");
        for (int i = 0; i < 5; i++) {
            repo.save(AuditLog.builder().serviceName("svc").action("A" + i)
                    .createdAt(base.plusNanos(i * 1_000L)).build());
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        do {
            AuditPage page = repo.findAll(AuditQuery.builder().cursor(cursor).pageSize(2).build());
            page.content().forEach(l -> seen.add(l.getAction()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).hasSize(5);
    }

    @Test
    void findAll_malformedCursor_throwsIllegalArgument() {
        String noSeparator = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("abc".getBytes());
        assertThatThrownBy(() -> repo.findAll(AuditQuery.builder().cursor(noSeparator).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gdprErase_clearsActor() {
        AuditLog log = repo.save(AuditLog.builder()
                .serviceName("svc").action("A").createdBy("jane@example.com").build());

        repo.gdprErase(log.getId());

        assertThat(repo.findById(log.getId()).get().getCreatedBy()).isNull();
    }

    @Test
    void saveAll_persistsAll() {
        List<AuditLog> logs = List.of(
                AuditLog.builder().serviceName("s").action("A").build(),
                AuditLog.builder().serviceName("s").action("B").build()
        );
        repo.saveAll(logs);
        assertThat(PaginationAssertions.countAll(repo)).isEqualTo(2);
    }

    @Test
    void count_excludesSoftDeleted() {
        AuditLog log = repo.save(AuditLog.builder().serviceName("s").action("A").build());
        repo.save(AuditLog.builder().serviceName("s").action("B").build());
        repo.softDelete(log.getId());

        assertThat(PaginationAssertions.countAll(repo)).isEqualTo(1);
    }

    @Test
    void findAll_filtersByLevel() {
        repo.save(AuditLog.builder().serviceName("s").action("A").level(AuditLevel.ERROR).build());
        repo.save(AuditLog.builder().serviceName("s").action("B").level(AuditLevel.INFO).build());

        AuditPage page = repo.findAll(AuditQuery.builder().level(AuditLevel.ERROR).pageSize(10).build());
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).getLevel()).isEqualTo(AuditLevel.ERROR);
    }
}
