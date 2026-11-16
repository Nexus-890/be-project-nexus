package com.group5.auctionservice.stream;

import com.group5.common_events.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Consumer chỉ fan-out thay đổi trạng thái, không xử lý Outbid thuộc R2. */
class AuctionStreamConsumerTest {
    private final AuctionStreamRegistry streams = mock(AuctionStreamRegistry.class);
    private final AuctionStreamConsumer consumer = new AuctionStreamConsumer(streams);
    private final UUID auction = UUID.randomUUID();

    /** Mọi event thay đổi giá, deadline hoặc trạng thái đều đánh dấu auction cần snapshot mới. */
    @ParameterizedTest
    @ValueSource(strings = {"AuctionStarted", "BidPlaced", "AuctionEnded", "AuctionWon", "AuctionFailed",
            "AuctionCancelled", "AuctionPaymentTimeout", "AuctionSettled"})
    void relevantEventsNotifyConnectedViewers(String type) {
        consumer.receive(event(type, auction));
        verify(streams).changed(auction);
    }

    /** Không tạo DB query hay broadcast cho event không thuộc luồng SSE. */
    @Test
    void unrelatedEventIsIgnored() {
        consumer.receive(event("Outbid", auction));
        verifyNoInteractions(streams);
    }

    /** Không cho payload một auction đánh thức stream của auction khác. */
    @Test
    void inconsistentAggregateIsRejected() {
        var event = event("BidPlaced", UUID.randomUUID());
        var wrong = new EventEnvelope<>(event.eventId(), event.eventType(), auction.toString(), event.occurredAt(),
                event.traceId(), event.payload());
        assertThatThrownBy(() -> consumer.receive(wrong)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(streams);
    }

    /** Mỗi replica vẫn nhận event replay; registry chịu trách nhiệm bỏ version đã gửi. */
    @Test
    void replayIsAllowedToReachTheLocalRegistry() {
        var event = event("BidPlaced", auction);
        consumer.receive(event);
        consumer.receive(event);
        verify(streams, times(2)).changed(auction);
    }

    /** Dùng envelope và JsonNode đúng serializer của common-events. */
    private EventEnvelope<JsonNode> event(String type, UUID id) {
        return new EventEnvelope<>(UUID.randomUUID(), type, id.toString(), Instant.now(), "stream-unit",
                JsonMapper.shared().valueToTree(Map.of("auctionId", id.toString())));
    }
}