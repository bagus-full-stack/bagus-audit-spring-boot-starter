package io.github.assami.audit.retention;

import io.github.assami.audit.service.AuditService;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * Permanently removes entries that were soft-deleted more than {@code audit.retention.deleted-days} ago.
 * Only registered when that property is above zero; see {@code AuditAutoConfiguration}.
 */
public class AuditPurgeJob {

    private final AuditService service;
    private final Duration keepDeleted;

    public AuditPurgeJob(AuditService service, int deletedDays) {
        this.service = service;
        this.keepDeleted = Duration.ofDays(deletedDays);
    }

    @Scheduled(cron = "${audit.retention.cron:0 30 3 * * *}")
    public void run() {
        service.purgeDeleted(keepDeleted);
    }
}
