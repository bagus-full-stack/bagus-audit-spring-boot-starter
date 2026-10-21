package io.github.assami.audit.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Graceful shutdown for the audit async thread pool.
 * Runs at phase {@code Integer.MAX_VALUE - 10} so it stops after most beans.
 */
public class AuditGracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AuditGracefulShutdown.class);
    private static final int DRAIN_SECONDS = 30;

    private final Executor executor;
    private volatile boolean running = false;

    public AuditGracefulShutdown(Executor executor) {
        this.executor = executor;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (executor instanceof ExecutorService es) {
            log.info("Audit async executor shutting down — draining for up to {}s", DRAIN_SECONDS);
            es.shutdown();
            try {
                if (!es.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Audit async executor did not drain in time; forcing shutdown");
                    es.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                es.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 10;
    }
}
