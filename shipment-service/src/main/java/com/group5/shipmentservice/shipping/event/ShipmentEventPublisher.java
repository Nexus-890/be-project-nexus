package com.group5.shipmentservice.shipping.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.event.payload.ShipmentStatusChanged;
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

    public void changed(Shipment shipment, ShipmentHistory history, String reason, String traceId) {
        String type = switch (shipment.getShipmentStatus()) {
            case SHIPPED -> "ShipmentPickedUp";
            case IN_TRANSIT -> "ShipmentInTransit";
            case DELIVERED -> "ShipmentDelivered";
            case DELIVERY_FAILED -> "ShipmentFailed";
            case RETURNED -> "ShipmentReturned";
            case CANCELLED -> "ShipmentCancelled";
            default -> "ShipmentStatusUpdated";
        };
        ShipmentStatusChanged payload = new ShipmentStatusChanged(shipment.getId(), shipment.getOrderId(),
                shipment.getBuyerId(), shipment.getWarehouseId(), shipment.getCarrier(), shipment.getTrackingCode(),
                shipment.getShipmentStatus(), history.getId(), shipment.getVersion(), history.getSource(),
                history.getCarrierEventId(), history.getChangedBy(), history.getCreatedAt().toInstant(), reason);
        EventEnvelope<ShipmentStatusChanged> event = traceId == null
                ? envelopes.create(type, shipment.getId().toString(), payload)
                : new EventEnvelope<>(UUID.randomUUID(), type, shipment.getId().toString(),
                        Instant.now(), traceId, payload);
        outbox.write("Shipment", Topics.SHIPMENT, event);
    }
}
