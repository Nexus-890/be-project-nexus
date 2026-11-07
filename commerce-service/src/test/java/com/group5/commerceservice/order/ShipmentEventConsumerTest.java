package com.group5.commerceservice.order;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShipmentEventConsumerTest {
    private final ShipmentStatusHandler handler = mock(ShipmentStatusHandler.class);
    private final ShipmentEventConsumer consumer = new ShipmentEventConsumer(handler, JsonMapper.shared());

    @Test
    @SuppressWarnings("unchecked")
    void routesTheTypedPayloadWithTheOriginalEnvelopeIdentity() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of("orderId", orderId, "shipmentId", shipmentId,
                "shipmentVersion", 4, "status", "DELIVERED"));
        EventEnvelope<JsonNode> event = envelope("ShipmentDelivered", shipmentId, payload);

        consumer.receive(new ConsumerRecord<>(Topics.SHIPMENT, 0, 0, orderId.toString(), event));

        ArgumentCaptor<EventEnvelope<ShipmentStatusChanged>> forwarded = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(handler).handle(forwarded.capture());
        assertThat(forwarded.getValue().eventId()).isEqualTo(event.eventId());
        assertThat(forwarded.getValue().aggregateId()).isEqualTo(shipmentId.toString());
        assertThat(forwarded.getValue().traceId()).isEqualTo(event.traceId());
        assertThat(forwarded.getValue().occurredAt()).isEqualTo(event.occurredAt());
        assertThat(forwarded.getValue().payload().orderId()).isEqualTo(orderId);
        assertThat(forwarded.getValue().payload().shipmentVersion()).isEqualTo(4);
    }

    @Test
    void shipmentEventsWithoutAnOrderTransitionAreIgnoredWithoutParsingThePayload() {
        for (String type : new String[]{"ShipmentCreated", "ShipmentInTransit", "ShipmentFailed", "ShipmentReturned"}) {
            consumer.receive(new ConsumerRecord<>(Topics.SHIPMENT, 0, 0, "unused",
                    envelope(type, UUID.randomUUID(), JsonMapper.shared().valueToTree(Map.of("unknown", true)))));
        }
        verifyNoInteractions(handler);
    }

    @Test
    void rejectsAnEventPartitionedByAnotherOrderBeforeApplyingAnyTransition() {
        UUID shipment = UUID.randomUUID();
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of("orderId", UUID.randomUUID(), "shipmentId", shipment));

        assertThatThrownBy(() -> consumer.receive(new ConsumerRecord<>(Topics.SHIPMENT, 0, 0, UUID.randomUUID().toString(),
                envelope("ShipmentPickedUp", shipment, payload))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Shipment event key must be its orderId");
        verifyNoInteractions(handler);
    }

    private static EventEnvelope<JsonNode> envelope(String type, UUID shipment, JsonNode payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, shipment.toString(), Instant.now(), "204shipment", payload);
    }
}
