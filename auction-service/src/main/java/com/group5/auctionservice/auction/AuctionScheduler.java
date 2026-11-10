package com.group5.auctionservice.auction;

import com.group5.auctionservice.shared.config.AuctionSchedulingConfig;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "nexus.auction.scheduler", name = "enabled", matchIfMissing = true)
public class AuctionScheduler {
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_PHASE = 10;

    private final AuctionRepository auctions;
    private final AuctionStateMachine stateMachine;
    private final EventEnvelopeFactory envelopes;
    private final OutboxWriter outbox;
    private final Tracer tracer;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AuctionScheduler(AuctionRepository auctions, AuctionStateMachine stateMachine,
                            EventEnvelopeFactory envelopes, OutboxWriter outbox, Tracer tracer,
                            Clock clock, PlatformTransactionManager transactionManager) {
        this.auctions = auctions;
        this.stateMachine = stateMachine;
        this.envelopes = envelopes;
        this.outbox = outbox;
        this.tracer = tracer;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(4);
    }

    @Scheduled(fixedRate = 10_000, scheduler = AuctionSchedulingConfig.LIFECYCLE_SCHEDULER)
    @SchedulerLock(name = "auctionLifecycle")
    public void tick() {
        LockAssert.assertLocked();
        Span span = tracer.nextSpan().name("auction.lifecycle.tick").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Instant now = clock.instant();
            transitionDue(() -> auctions.lockAuctionsDueToStart(now, BATCH_SIZE), AuctionStatus.ACTIVE);
            transitionDue(() -> auctions.lockAuctionsDueToEnd(now, BATCH_SIZE), AuctionStatus.ENDED);
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private void transitionDue(Supplier<List<UUID>> lockDue, AuctionStatus target) {
        for (int batch = 0; batch < MAX_BATCHES_PER_PHASE; batch++) {
            Integer selected = transactions.execute(status -> {
                List<UUID> ids = lockDue.get();
                for (Auction auction : auctions.findAllById(ids)) {
                    stateMachine.transition(auction, target);
                    writeEvent(auction, target);
                }
                return ids.size();
            });
            if (selected < BATCH_SIZE) {
                return;
            }
        }
    }

    private void writeEvent(Auction auction, AuctionStatus target) {
        boolean started = target == AuctionStatus.ACTIVE;
        String eventType = started ? "AuctionStarted" : "AuctionEnded";
        String transitionTime = started ? "startedAt" : "endedAt";
        outbox.write("Auction", Topics.AUCTION, envelopes.createWithTimestamp(eventType, auction.getId().toString(),
                occurredAt -> Map.of(
                        "auctionId", auction.getId(),
                        "sellerId", auction.getSellerId(),
                        "productId", auction.getProductId(),
                        "skuId", auction.getSkuId(),
                        "startAt", auction.getStartAt(),
                        "endAt", auction.getEndAt(),
                        transitionTime, occurredAt)));
    }
}
