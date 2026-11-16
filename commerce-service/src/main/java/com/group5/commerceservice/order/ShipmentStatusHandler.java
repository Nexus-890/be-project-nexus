package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShipmentStatusHandler {
    public static final String CONSUMER = "commerce-order-shipment";
    private static final Set<String> PROGRESS_EVENTS = Set.of("ShipmentPickedUp", "ShipmentInTransit",
            "ShipmentDelivered", "ShipmentFailed", "ShipmentReturned", "ShipmentStatusUpdated");

    static boolean supports(String eventType) { return PROGRESS_EVENTS.contains(eventType); }

    private final IdempotentConsumer consumer;
    private final OrderRepository orders;
    private final OrderStateMachine stateMachine;
    private final EntityManager entityManager;
    private final Validator validator;
    private final PendingShipmentEvents pending;

    public void handle(EventEnvelope<ShipmentStatusChanged> event) {
        validate(event);
        consumer.handle(CONSUMER, event, ignored -> {
            if (!apply(event.payload(), "ShipmentDelivered".equals(event.eventType()))) pending.park(event);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retry(EventEnvelope<ShipmentStatusChanged> event) {
        validate(event);
        return apply(event.payload(), "ShipmentDelivered".equals(event.eventType()));
    }

    private void validate(EventEnvelope<ShipmentStatusChanged> event) {
        ShipmentStatusChanged shipment = event.payload();
        require(validator.validate(shipment).isEmpty(), "Invalid shipment transition payload");
        require(event.aggregateId().equals(shipment.shipmentId().toString()), "Shipment and aggregate must match");
        String expected = switch (event.eventType()) {
            case "ShipmentPickedUp" -> "SHIPPED";
            case "ShipmentInTransit" -> "IN_TRANSIT";
            case "ShipmentDelivered" -> "DELIVERED";
            case "ShipmentFailed" -> "DELIVERY_FAILED";
            case "ShipmentReturned" -> "RETURNED";
            case "ShipmentStatusUpdated" -> Set.of("OUT_FOR_DELIVERY", "RETURNING").contains(shipment.status())
                    ? shipment.status() : "";
            default -> "";
        };
        require(expected.equals(shipment.status()), "Shipment status and event type must match");
        Instant.parse(shipment.changedAt());
        require(!"MANUAL".equals(shipment.source()) || shipment.reason() != null,
                "A manual shipment transition must have a reason");
    }

    private boolean apply(ShipmentStatusChanged shipment, boolean delivered) {
        // Serialize different envelope IDs for the same order, including duplicate delivery facts.
        if (orders.lockOrder(shipment.orderId()).isEmpty()) return false;
        Order order = orders.findById(shipment.orderId()).orElseThrow();
        entityManager.refresh(order);
        require(order.getUserId().equals(shipment.buyerId()), "Shipment buyer does not own the order");
        OrderStatus current = order.getStatus();
        if (current == OrderStatus.COMPLETED || current == OrderStatus.CANCELED
                || current == OrderStatus.REFUND_REQUESTED || current == OrderStatus.REFUNDED) {
            return true;
        }
        String reason = delivered ? "Shipment delivered" : "Shipment pickup confirmed by carrier progress";
        if (!delivered) {
            if (current == OrderStatus.PAID) {
                stateMachine.move(order.getId(), OrderStatus.SHIPPED, shipment.actorId(), reason);
            } else if (current != OrderStatus.SHIPPED && current != OrderStatus.DELIVERED) {
                return false;
            }
            return true;
        }
        if (current == OrderStatus.PAID) {
            stateMachine.move(order.getId(), OrderStatus.SHIPPED, shipment.actorId(),
                    "Shipment pickup confirmed by delivery");
            current = OrderStatus.SHIPPED;
        }
        if (current == OrderStatus.SHIPPED) {
            stateMachine.move(order.getId(), OrderStatus.DELIVERED, shipment.actorId(), reason);
        } else if (current != OrderStatus.DELIVERED) {
            // Park this fact instead of blocking the partition that may carry the missing pickup.
            return false;
        }
        // Delivery opens the receipt confirmation window; completion has its own buyer/scheduler transaction.
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
