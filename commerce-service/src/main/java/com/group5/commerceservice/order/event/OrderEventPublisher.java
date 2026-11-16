package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.entity.OrderItem;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.commerceservice.promotion.VoucherLifecycleService.VoucherEvent;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    public void orderCreated(Order order) {
        OrderCreated payload = new OrderCreated(order.getId(), order.getUserId(), order.getOrderNumber(),
                order.getOrderType(), order.getReferenceId(), order.getOrderGroup().getId(), order.getSellerId(),
                order.getVersion(), order.getItems().stream().map(OrderItem::getProductId)
                        .distinct().sorted().toList());
        outbox.write("Order", Topics.COMMERCE,
                envelopes.create("OrderCreated", payload.orderId().toString(), payload));
    }

    public void checkoutPaymentRequested(OrderGroup group) {
        List<CheckoutPaymentRequested.OrderShare> orders = group.getOrders().stream()
                .sorted(Comparator.comparing(Order::getId))
                .map(order -> new CheckoutPaymentRequested.OrderShare(order.getId(), order.getSellerId(),
                        order.getTotalAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        order.getPaymentDueAt().toInstant().toString(), order.getVersion()))
                .toList();
        outbox.write("OrderGroup", Topics.COMMERCE,
                envelopes.createWithTimestamp("CheckoutPaymentRequested", group.getId().toString(),
                        occurredAt -> new CheckoutPaymentRequested(group.getId(), group.getBuyerId(),
                                group.getCurrency(), group.getTotalAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                group.getDueAt().toInstant().toString(), occurredAt.toString(), orders.size(), orders)));
    }

    public void orderCancelled(OrderCancelled payload) {
        outbox.write("Order", Topics.COMMERCE,
                envelopes.create("OrderCancelled", payload.orderId().toString(), payload));
    }

    public void checkoutStockReleaseRequested(UUID orderId) {
        outbox.write("Order", Topics.COMMERCE, envelopes.create(
                "CheckoutStockReleaseRequested", orderId.toString(), Map.of("orderId", orderId)));
    }

    /** Ghi một OrderPaid cho từng đơn vào outbox trong transaction đang chuyển trạng thái PAID. */
    public void orderPaid(OrderPaid payload) {
        outbox.write("Order", Topics.COMMERCE,
                envelopes.create("OrderPaid", payload.orderId().toString(), payload));
    }

    public void voucherChanged(String eventType, VoucherEvent payload) {
        outbox.write("VoucherUsage", Topics.COMMERCE,
                envelopes.create(eventType, payload.usageId().toString(), payload));
    }

    public void orderCompleted(OrderCompleted payload) {
        outbox.write("Order", Topics.COMMERCE,
                envelopes.create("OrderCompleted", payload.orderId().toString(), payload));
    }

    public void orderGroupCancelled(OrderGroupCancelled payload) {
        outbox.write("OrderGroup", Topics.COMMERCE,
                envelopes.create("OrderGroupCancelled", payload.orderGroupId().toString(), payload));
    }
}
