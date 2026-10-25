package io.github.assami.audit.ai;

import io.github.assami.audit.event.AuditLogCreatedEvent;
import io.github.assami.audit.event.AuditLogDeletedEvent;
import io.github.assami.audit.event.AuditLogErasedEvent;
import io.github.assami.audit.model.AuditLog;
import io.github.assami.audit.model.AuditPage;
import io.github.assami.audit.model.AuditQuery;
import io.github.assami.audit.port.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the vector index in step with the audit log. Embedding calls are slow, so they run on one background
 * thread and a failure is logged: the audit write has already succeeded and must not depend on the model.
 * <p>
 * shortcut: the queue holds 10 000 pending changes and drops the rest with a warning, and the default index lives
 * in memory (only the latest {@code audit.ai.backfill} entries come back after a restart). Define your own
 * {@link VectorStore} bean (pgvector, ...) to keep the index across restarts.
 */
public class AuditAssistantIndexer {

    private static final Logger log = LoggerFactory.getLogger(AuditAssistantIndexer.class);
    private static final int BATCH = 50;
    static final int EMBED_PAYLOAD_MAX = 2000;
    static final long WARN_EVERY_MS = 60_000;

    private final VectorStore index;
    private final AuditRepository repository;
    private final int backfill;
    // Model server down: every audit write fails here. One line a minute, with the count, keeps the log readable.
    private final AtomicLong lastWarn = new AtomicLong();
    private final AtomicLong hidden = new AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10_000), r -> {
                Thread t = new Thread(r, "audit-ai-indexer");
                t.setDaemon(true);
                return t;
            });

    public AuditAssistantIndexer(VectorStore index, AuditRepository repository, int backfill) {
        this.index = index;
        this.repository = repository;
        this.backfill = backfill;
    }

    // After the commit, so a rolled-back write is never indexed; at once when there is no transaction.
    @TransactionalEventListener(fallbackExecution = true)
    public void onCreated(AuditLogCreatedEvent event) {
        AuditLog entry = event.getAuditLog();
        submit(() -> index.add(List.of(toDocument(entry))));
    }

    /** A deleted or erased entry leaves the index: the index must not keep what GDPR erasure removed. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onDeleted(AuditLogDeletedEvent event) {
        submit(() -> index.delete(List.of(event.getAuditLogId())));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onErased(AuditLogErasedEvent event) {
        submit(() -> index.delete(List.of(event.getAuditLogId())));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        if (backfill > 0) submit(this::indexRecent);
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    /** Waits until every queued change has been applied. For tests. */
    void drain() throws InterruptedException {
        worker.shutdown();
        worker.awaitTermination(10, TimeUnit.SECONDS);
    }

    private void indexRecent() {
        String cursor = null;
        int done = 0;
        while (done < backfill) {
            AuditPage page = repository.findAll(AuditQuery.builder().cursor(cursor).pageSize(BATCH).build());
            List<Document> docs = new ArrayList<>();
            for (AuditLog entry : page.content()) {
                if (done + docs.size() >= backfill) break;
                docs.add(toDocument(entry));
            }
            if (!docs.isEmpty()) index.add(docs);
            done += docs.size();
            cursor = page.nextCursor();
            if (cursor == null) break;
        }
        log.info("Audit assistant: indexed {} existing entries", done);
    }

    static Document toDocument(AuditLog e) {
        Map<String, Object> metadata = new HashMap<>();
        if (e.getTenantId() != null) metadata.put("tenantId", e.getTenantId());
        // an embedding model has a small input limit: a longer text makes the call fail and the entry is never indexed
        String text = e.getAction() + " " + e.getLevel() + " " + e.getCreatedBy()
                + (e.getPayload() != null ? " " + AuditAssistant.cut(e.getPayload(), EMBED_PAYLOAD_MAX) : "");
        return new Document(e.getId(), text, metadata);
    }

    /** Entries that fail here are not indexed: only the latest {@code audit.ai.backfill} come back after a restart. */
    private void warn(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarn.get();
        if (now - last >= WARN_EVERY_MS && lastWarn.compareAndSet(last, now)) {
            log.warn("{} ({} more since the last warning)", message, hidden.getAndSet(0));
        } else {
            hidden.incrementAndGet();
        }
    }

    private void submit(Runnable task) {
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    warn("Audit assistant index update failed: " + ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            warn("Audit assistant index queue is full: changes are not indexed");
        }
    }
}
