package io.github.assami.audit.adapter.jpa;

import io.github.assami.audit.exception.AuditNotFoundException;
import io.github.assami.audit.model.AuditLevel;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the JPA adapter against H2 (the schema is generated from the entity). */
@DataJpaTest
class JpaAuditRepositoryTest {

    @SpringBootConfiguration
    @EntityScan(basePackageClasses = AuditLogEntity.class)
    @EnableJpaRepositories(basePackageClasses = AuditLogJpaRepository.class)
    static class Config {}

    @Autowired AuditLogJpaRepository jpa;
    @Autowired EntityManager em;

    JpaAuditRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JpaAuditRepository(jpa);
    }

    private AuditLog.Builder log(String action) {
        return AuditLog.builder().serviceName("svc").action(action);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @Test
    void save_insertsNewRow_withVersionAssignedByHibernate() {
        AuditLog saved = repo.save(log("LOGIN").payload("p").createdBy("jane").build());
        flushAndClear();

        AuditLog found = repo.findById(saved.getId()).orElseThrow();
        assertThat(found.getAction()).isEqualTo("LOGIN");
        assertThat(found.getVersion()).isEqualTo(0L);
        assertThat(found.getCreatedBy()).isEqualTo("jane");
    }

    @Test
    void cursorPagination_sameMillisecond_returnsEveryRowOnce() {
        Instant base = Instant.parse("2026-01-01T00:00:00.001000Z");
        for (int i = 0; i < 7; i++) {
            repo.save(log("A" + i).createdAt(base.plusNanos(i * 1_000L)).build());
        }
        flushAndClear();

        Set<String> seen = new HashSet<>();
        int pages = 0;
        String cursor = null;
        do {
            AuditPage page = repo.findAll(AuditQuery.builder().cursor(cursor).pageSize(3).build());
            page.content().forEach(l -> assertThat(seen.add(l.getAction())).isTrue());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).hasSize(7);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void findAll_filtersByTenant() {
        repo.save(log("A").tenantId("t1").build());
        repo.save(log("B").tenantId("t2").build());
        flushAndClear();

        AuditPage page = repo.findAll(AuditQuery.builder().tenantId("t1").pageSize(10).build());

        assertThat(page.content()).extracting(AuditLog::getAction).containsExactly("A");
    }

    @Test
    void findAll_combinesFilters_andTimeRange() {
        Instant t = Instant.parse("2026-03-01T10:00:00Z");
        repo.save(log("LOGIN").level(AuditLevel.ERROR).createdAt(t).build());
        repo.save(log("LOGIN").level(AuditLevel.INFO).createdAt(t).build());
        repo.save(log("LOGIN").level(AuditLevel.ERROR).createdAt(t.plusSeconds(3600)).build());
        repo.save(log("LOGOUT").level(AuditLevel.ERROR).createdAt(t).build());
        flushAndClear();

        AuditPage page = repo.findAll(AuditQuery.builder()
                .action("LOGIN").level(AuditLevel.ERROR)
                .from(t.minusSeconds(1)).to(t.plusSeconds(60)).pageSize(10).build());

        assertThat(page.content()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void softDelete_hidesRow_andCountIgnoresIt() {
        AuditLog a = repo.save(log("A").build());
        repo.save(log("B").build());
        flushAndClear();

        repo.softDelete(a.getId());
        flushAndClear();

        assertThat(repo.findById(a.getId())).isEmpty();
        assertThat(repo.count()).isEqualTo(1);
        assertThatThrownBy(() -> repo.softDelete("unknown")).isInstanceOf(AuditNotFoundException.class);
    }

    @Test
    void gdprErase_clearsPayloadAndActor() {
        AuditLog a = repo.save(log("A").payload("secret").createdBy("jane@example.com").build());
        flushAndClear();

        repo.gdprErase(a.getId());
        flushAndClear();

        AuditLog found = repo.findById(a.getId()).orElseThrow();
        assertThat(found.getPayload()).isNull();
        assertThat(found.getCreatedBy()).isNull();
        assertThat(found.isGdprErased()).isTrue();
    }
}
