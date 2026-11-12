package com.group5.commerceservice.order;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import brave.Tracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShipmentEventConsumerTest {
    private final ShipmentStatusHandler handler = mock(ShipmentStatusHandler.class);
    private final Tracing tracing = Tracing.newBuilder().build();
    private final Tracer tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));
    private final ShipmentEventConsumer consumer = new ShipmentEventConsumer(handler, JsonMapper.shared(), tracer);

    @AfterEach
    void closeTracing() { tracing.close(); }

    @Test
    @SuppressWarnings("unchecked")
    void routesTheTypedPayloadWithTheOriginalEnvelopeIdentity() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        JsonNode payload = JsonMapper.shared().valueToTree(Map.of("orderId", orderId, "shipmentId", shipmentId,
                "shipmentVersion", 4, "status", "DELIVERED"));
        EventEnvelope<JsonNode> event = envelope("ShipmentDelivered", shipmentId, payload);

        doAnswer(invocation -> {
            assertThat(tracer.currentSpan()).isNotNull();
            return null;
        }).when(handler).handle(org.mockito.ArgumentMatchers.any());
        assertThat(tracer.currentSpan()).isNull();
        consumer.receive(new ConsumerRecord<>(Topics.SHIPMENT, 0, 0, orderId.toString(), event));
        assertThat(tracer.currentSpan()).isNull();

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
    void handlerFailureEscapesForKafkaRetryAndDoesNotLeakTheListenerSpan() {
        UUID order = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        var payload = JsonMapper.shared().valueToTree(Map.of("orderId", order, "shipmentId", shipment));
        var failure = new IllegalStateException("outbox unavailable");
        doThrow(failure).when(handler).handle(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> consumer.receive(new ConsumerRecord<>(Topics.SHIPMENT, 0, 0, order.toString(),
                envelope("ShipmentDelivered", shipment, payload)))).isSameAs(failure);
        assertThat(tracer.currentSpan()).isNull();
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
