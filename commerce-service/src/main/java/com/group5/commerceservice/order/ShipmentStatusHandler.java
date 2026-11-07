package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderStatusHistory;
import com.group5.commerceservice.order.event.OrderCompleted;
import com.group5.commerceservice.order.event.OrderEventPublisher;
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

@Service
@RequiredArgsConstructor
public class ShipmentStatusHandler {
    public static final String CONSUMER = "commerce-order-shipment";

    private final IdempotentConsumer consumer;
    private final OrderRepository orders;
    private final OrderStateMachine stateMachine;
    private final OrderEventPublisher events;
    private final OrderReceiptRenderer receipts;
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
        boolean delivered = "ShipmentDelivered".equals(event.eventType());
        require(delivered || "ShipmentPickedUp".equals(event.eventType()), "Unsupported shipment transition");
        require((delivered ? "DELIVERED" : "SHIPPED").equals(shipment.status()), "Shipment status and event type must match");
        Instant.parse(shipment.changedAt());
        require(!"MANUAL".equals(shipment.source()) || shipment.reason() != null,
                "A manual shipment transition must have a reason");
    }

    private boolean apply(ShipmentStatusChanged shipment, boolean delivered) {
        // Serialize different envelope IDs for the same order, including duplicate delivery facts.
        if (orders.lockOrder(shipment.orderId()).isEmpty()) return false;
        Order order = orders.findById(shipment.orderId()).orElseThrow();
        require(order.getUserId().equals(shipment.buyerId()), "Shipment buyer does not own the order");
        OrderStatus current = order.getStatus();
        if (current == OrderStatus.COMPLETED || current == OrderStatus.CANCELED
                || current == OrderStatus.REFUND_REQUESTED || current == OrderStatus.REFUNDED) {
            return true;
        }
        String reason = delivered ? "Shipment delivered" : "Shipment picked up";
        if (!delivered) {
            if (current == OrderStatus.PAID) {
                stateMachine.move(order.getId(), OrderStatus.SHIPPED, shipment.actorId(), reason);
            } else if (current != OrderStatus.SHIPPED && current != OrderStatus.DELIVERED) {
                return false;
            }
            return true;
        }
        if (current == OrderStatus.SHIPPED) {
            stateMachine.move(order.getId(), OrderStatus.DELIVERED, shipment.actorId(), reason);
        } else if (current != OrderStatus.DELIVERED) {
            // Park this fact instead of blocking the partition that may carry the missing pickup.
            return false;
        }
        OrderStatusHistory completion = stateMachine.move(order.getId(), OrderStatus.COMPLETED,
                shipment.actorId(), "Order completed after delivery");
        entityManager.flush();
        entityManager.refresh(completion);
        // The state machine uses native updates. Publish the committed version, not the cached entity.
        entityManager.refresh(order);
        require(!order.getUserId().equals(order.getSellerId()), "Buyer and seller must differ");
        require(order.getOrderType() == OrderType.AUCTION ? order.getReferenceId() != null : order.getReferenceId() == null,
                "Order type and reference must match");
        events.orderCompleted(new OrderCompleted(order.getId(), order.getOrderGroup().getId(), order.getUserId(),
                order.getSellerId(), order.getOrderType(), order.getReferenceId(), order.getVersion(),
                completion.getCreatedAt().toInstant().toString(), order.getOrderNumber(),
                receipts.render(order, completion.getCreatedAt())));
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
