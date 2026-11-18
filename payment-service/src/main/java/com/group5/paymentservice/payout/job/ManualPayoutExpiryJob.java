package com.group5.paymentservice.payout.job;

import com.group5.paymentservice.payout.repository.ManualPayoutRepository;
import com.group5.paymentservice.payout.service.ManualPayoutService;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Safe lease transitions only: this job never sends money or queries a transfer provider. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManualPayoutExpiryJob {
    private final ManualPayoutRepository repository;
    private final ManualPayoutService payouts;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("manual-payout-expiry").factory());
    private final AtomicBoolean active = new AtomicBoolean();
    private java.util.UUID cursor;

    @Scheduled(cron = "${nexus.payout.manual.expiry-cron:*/30 * * * * *}")
    public void expire() {
        if (!active.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            var batch = repository.expiring(cursor, 100);
                            if (batch.isEmpty()) {
                                cursor = null;
                                batch = repository.expiring(null, 100);
                            }
                            for (var id : batch) {
                                cursor = id;
                                try {
                                    payouts.expire(id);
                                } catch (RuntimeException failure) {
                                    log.warn("Manual payout expiry deferred: payoutId={}", id);
                                }
                            }
                        } finally {
                            active.set(false);
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            active.set(false);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
