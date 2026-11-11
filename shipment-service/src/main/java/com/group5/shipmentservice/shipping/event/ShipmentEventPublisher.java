package com.group5.shipmentservice.shipping.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.event.payload.ShipmentCreated;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class ShipmentEventPublisher {
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    public ShipmentEventPublisher(OutboxWriter outbox, EventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    public void created(Shipment shipment, String traceId) {
        ShipmentCreated payload = new ShipmentCreated(shipment.getId(), shipment.getOrderId(),
                shipment.getBuyerId(), shipment.getWarehouseId(), shipment.getCarrier(), shipment.getTrackingCode(),
                shipment.getShipmentStatus(), shipment.getVersion(), shipment.getCreatedAt().toInstant());
        EventEnvelope<ShipmentCreated> event = traceId == null
                ? envelopes.create("ShipmentCreated", shipment.getId().toString(), payload)
                : new EventEnvelope<>(UUID.randomUUID(), "ShipmentCreated", shipment.getId().toString(),
                        Instant.now(), traceId, payload);
        outbox.write("Shipment", Topics.SHIPMENT, event);
    }
}
