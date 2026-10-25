package io.github.assami.audit.adapter.inmemory;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.support.PaginationAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRobustnessTest {

    private InMemoryAuditRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAuditRepository();
    }

    private AuditLog.Builder log(String action) {
        return AuditLog.builder().serviceName("svc").action(action);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 50, 199, 200, 201})
    void randomisedCollidingTimestamps_everyPageSizeWalksExactlyOnce(int pageSize) {
        Set<String> ids = PaginationAssertions.saveWithCollidingTimestamps(repo, 300, 42L + pageSize);

        PaginationAssertions.assertWalkIsExact(repo, ids, pageSize);
    }

    @Test
    void exactMultipleOfPageSize_endsWithoutAnExtraEmptyPage() {
        for (int i = 0; i < 6; i++) repo.save(log("A" + i).build());

        AuditPage p1 = repo.findAll(AuditQuery.builder().pageSize(3).build());
        AuditPage p2 = repo.findAll(AuditQuery.builder().pageSize(3).cursor(p1.nextCursor()).build());

        assertThat(p1.hasNext()).isTrue();
        assertThat(p2.content()).hasSize(3);
        assertThat(p2.hasNext()).isFalse();
        assertThat(p2.nextCursor()).isNull();
    }

    @Test
    void emptyRepository_returnsAnEmptyLastPage() {
        AuditPage page = repo.findAll(AuditQuery.builder().build());

        assertThat(page.content()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void rowsInsertedDuringTheWalk_doNotBreakIt() {
        for (int i = 0; i < 10; i++) {
            repo.save(log("OLD" + i).createdAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)).build());
        }
        AuditPage first = repo.findAll(AuditQuery.builder().pageSize(4).build());
        repo.save(log("NEW").createdAt(Instant.parse("2027-01-01T00:00:00Z")).build());

        AuditPage second = repo.findAll(AuditQuery.builder().pageSize(4).cursor(first.nextCursor()).build());

        assertThat(second.content()).extracting(AuditLog::getAction).doesNotContain("NEW");
        assertThat(second.content()).hasSize(4);
    }

    @Test
    void deletedRows_neverAppearInListsOrCounts() {
        AuditLog gone = repo.save(log("GONE").build());
        repo.save(log("KEEP").build());
        repo.softDelete(gone.getId());

        assertThat(repo.findAll(AuditQuery.builder().build()).content()).extracting(AuditLog::getAction).containsExactly("KEEP");
        assertThat(PaginationAssertions.countAll(repo)).isEqualTo(1);
        assertThat(repo.findById(gone.getId())).isEmpty();
    }

    @Test
    void timeRange_isInclusiveOnBothEnds() {
        Instant t = Instant.parse("2026-03-01T10:00:00Z");
        repo.save(log("BEFORE").createdAt(t.minusSeconds(1)).build());
        repo.save(log("START").createdAt(t).build());
        repo.save(log("END").createdAt(t.plusSeconds(10)).build());
        repo.save(log("AFTER").createdAt(t.plusSeconds(11)).build());

        AuditPage page = repo.findAll(AuditQuery.builder().from(t).to(t.plusSeconds(10)).pageSize(10).build());

        assertThat(page.content()).extracting(AuditLog::getAction).containsExactlyInAnyOrder("START", "END");
    }

    @Test
    void tenantFilter_isExact_andNeverMatchesNullTenants() {
        repo.save(log("A").tenantId("acme").build());
        repo.save(log("B").tenantId("acme2").build());
        repo.save(log("C").build());

        AuditPage page = repo.findAll(AuditQuery.builder().tenantId("acme").pageSize(10).build());

        assertThat(page.content()).extracting(AuditLog::getAction).containsExactly("A");
    }

    @Test
    void erasingTwice_isIdempotent_andUnknownIdsFail() {
        AuditLog a = repo.save(log("A").payload("p").build());

        repo.gdprErase(a.getId());
        repo.gdprErase(a.getId());

        assertThat(repo.findById(a.getId()).orElseThrow().isGdprErased()).isTrue();
        assertThatThrownBy(() -> repo.gdprErase("nope")).isInstanceOf(AuditNotFoundException.class);
        assertThatThrownBy(() -> repo.softDelete("nope")).isInstanceOf(AuditNotFoundException.class);
    }

    @Test
    void concurrentWriters_loseNothing_andTheWalkStaysExact() throws Exception {
        int threads = 16, perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) ids.add(repo.save(log("C").build()).getId());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(PaginationAssertions.countAll(repo)).isEqualTo((long) threads * perThread);
        PaginationAssertions.assertWalkIsExact(repo, new HashSet<>(ids), 200);
    }

    @Test
    void concurrentReadersAndWriters_neverThrow() throws Exception {
        for (int i = 0; i < 200; i++) repo.save(log("SEED").build());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> { for (int i = 0; i < 300; i++) repo.save(log("W").build()); }));
            futures.add(pool.submit(() -> { for (int i = 0; i < 50; i++) PaginationAssertions.walk(repo, 25); }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(PaginationAssertions.countAll(repo)).isEqualTo(200 + 4 * 300);
    }
}
