package com.group5.commerceservice.order.event;

import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    public void orderCancelled(OrderCancelled payload) {
        outbox.write("Order", Topics.COMMERCE,
                envelopes.create("OrderCancelled", payload.orderId().toString(), payload));
    }

    public void orderGroupCancelled(OrderGroupCancelled payload) {
        outbox.write("OrderGroup", Topics.COMMERCE,
                envelopes.create("OrderGroupCancelled", payload.orderGroupId().toString(), payload));
    }
}
