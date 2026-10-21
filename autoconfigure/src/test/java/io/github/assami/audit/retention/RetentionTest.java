package io.github.assami.audit.retention;

import io.github.assami.audit.adapter.inmemory.InMemoryAuditRepository;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.properties.AuditProperties;
import io.github.assami.audit.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionTest {

    private InMemoryAuditRepository repo;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAuditRepository();
        AuditProperties props = new AuditProperties();
        props.setName("svc");
        service = new AuditService(repo, e -> {}, props, Optional.empty());
    }

    private AuditLog deleted(String action) {
        AuditLog log = service.create(AuditLog.builder().action(action).payload("p"));
        service.delete(log.getId());
        return log;
    }

    @Test
    void repository_purgesOnlyRowsDeletedBeforeTheCutoff() {
        AuditLog old = repo.save(AuditLog.builder().serviceName("s").action("OLD").build());
        repo.save(old.softDelete(Instant.now().minus(Duration.ofDays(40))));
        AuditLog recent = repo.save(AuditLog.builder().serviceName("s").action("RECENT").build());
        repo.save(recent.softDelete(Instant.now().minus(Duration.ofDays(1))));
        repo.save(AuditLog.builder().serviceName("s").action("LIVE").build());

        long removed = repo.purgeDeleted(Instant.now().minus(Duration.ofDays(30)));

        assertThat(removed).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.purgeDeleted(Instant.now().plus(Duration.ofDays(1)))).as("the recent one goes later").isEqualTo(1);
        assertThat(repo.findAll(AuditQuery.builder().build()).content()).extracting(AuditLog::getAction).containsExactly("LIVE");
    }

    @Test
    void service_keepsRowsDeletedWithinTheWindow_andRemovesTheOlderOnes() {
        deleted("A");

        assertThat(service.purgeDeleted(Duration.ofDays(1))).isZero();
        assertThat(service.purgeDeleted(Duration.ofSeconds(-1))).isEqualTo(1);
        assertThat(service.purgeDeleted(Duration.ofSeconds(-1))).as("nothing left").isZero();
    }

    @Test
    void job_runsThePurgeWithItsConfiguredWindow() {
        deleted("A");

        new AuditPurgeJob(service, 30).run();
        assertThat(repo.purgeDeleted(Instant.now().plusSeconds(60))).as("still inside the 30 day window").isEqualTo(1);
    }

    @Test
    void liveRowsAreNeverPurged() {
        service.create(AuditLog.builder().action("LIVE"));

        assertThat(service.purgeDeleted(Duration.ofSeconds(-1))).isZero();
        assertThat(repo.count()).isEqualTo(1);
    }
}
