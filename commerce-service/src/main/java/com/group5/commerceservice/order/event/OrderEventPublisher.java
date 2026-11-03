package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    public void orderCreated(OrderCreated payload) {
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

    public void orderGroupCancelled(OrderGroupCancelled payload) {
        outbox.write("OrderGroup", Topics.COMMERCE,
                envelopes.create("OrderGroupCancelled", payload.orderGroupId().toString(), payload));
    }
}
