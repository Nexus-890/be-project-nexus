package com.group5.auctionservice.settlement;

import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.shared.config.AuctionSchedulingConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nexus.auction", name = {"scheduler.enabled", "settlement.enabled"},
        matchIfMissing = true)
public class WinnerDeterminationJob {
    private static final int BATCH_SIZE = 100;

    private final AuctionRepository auctions;
    private final SettlementService settlement;
    private final Tracer tracer;

    @Scheduled(fixedDelay = 10_000, scheduler = AuctionSchedulingConfig.LIFECYCLE_SCHEDULER)
    @SchedulerLock(name = "auctionWinnerDetermination")
    public void tick() {
        UUID afterId = null;
        List<UUID> ids;
        do {
            ids = auctions.findEndedIds(afterId, BATCH_SIZE);
            for (UUID id : ids) {
                try {
                    settleTraced(id);
                } catch (RuntimeException failure) {
                    log.error("Auction settlement failed for {}; will retry on the next run", id, failure);
                }
                afterId = id;
            }
        } while (ids.size() == BATCH_SIZE);
    }

    private void settleTraced(UUID id) {
        Span span = tracer.nextSpan().name("auction.determine-winner").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            settlement.settle(id);
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }
}
