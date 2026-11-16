package com.group5.auctionservice.bidding;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import lombok.RequiredArgsConstructor;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Một group dùng chung các instance; Kafka key auctionId giữ các command trong một partition. */
@Component
@RequiredArgsConstructor
public class BidCommandConsumer {
    public static final String CONSUMER = "auction-bid-command";
    private final IdempotentConsumer idempotent;
    private final BidService bids;
    private final JsonMapper json;
    private final Tracer tracer;

    /** Marker, kết quả bid, giá auction và BidPlaced cùng commit hoặc cùng rollback. */
    @KafkaListener(
            topics = Topics.AUCTION_BIDS,
            groupId = "auction-bid-commands",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY)
    public void onEvent(EventEnvelope<JsonNode> event) {
        if (!"BidRequested".equals(event.eventType())) return;
        Span span = tracer.nextSpan().name("auction.bid-command").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            BidCommand command = json.treeToValue(event.payload(), BidCommand.class);
            if (command.auctionId() == null
                    || command.bidId() == null
                    || !command.auctionId().toString().equals(event.aggregateId())) {
                throw new IllegalArgumentException("Bid command does not match its auction key");
            }
            // Barrier riêng đã commit/nhả khóa trước transaction marker, không giữ hai connection.
            bids.awaitAdmissions(command.auctionId());
            idempotent.handle(
                    CONSUMER, event, envelope -> bids.apply(command.auctionId(), command.bidId()));
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }
}
