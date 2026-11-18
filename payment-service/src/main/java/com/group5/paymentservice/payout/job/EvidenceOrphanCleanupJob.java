package com.group5.paymentservice.payout.job;

import com.group5.paymentservice.payout.evidence.EvidenceOrphanCleanupService;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Storage housekeeping only: never dispatches payouts or performs provider reconciliation. */
@Component
@ConditionalOnProperty(
        name = "nexus.payout.manual.evidence.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EvidenceOrphanCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(EvidenceOrphanCleanupJob.class);
    private final EvidenceOrphanCleanupService service;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("payout-evidence-cleanup").factory());
    private final AtomicBoolean running = new AtomicBoolean();

    public EvidenceOrphanCleanupJob(EvidenceOrphanCleanupService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${nexus.payout.manual.evidence.cleanup-interval:1h}",
            initialDelayString = "${nexus.payout.manual.evidence.cleanup-interval:1h}")
    public void clean() {
        if (executor.isShutdown() || !running.compareAndSet(false, true)) return;
        executor.execute(
                () -> {
                    try {
                        service.cleanup();
                    } catch (RuntimeException ignored) {
                        // Retry on the next tick; log no object paths, response bodies or bank
                        // information.
                        log.warn(
                                "Private payout evidence cleanup is unavailable; it will retry on"
                                        + " the next interval");
                    } finally {
                        running.set(false);
                    }
                });
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
