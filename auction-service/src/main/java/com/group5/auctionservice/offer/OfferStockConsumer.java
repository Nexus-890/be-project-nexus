package com.group5.auctionservice.offer;

import com.group5.common_events.*;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class OfferStockConsumer {
    private final SecondChanceOfferService offers;
    private final IdempotentConsumer idempotent;
    private final Tracer tracer;
    @KafkaListener(topics=Topics.INVENTORY,groupId="auction-offer-stock",containerFactory=EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(EventEnvelope<JsonNode> event) {
        if (!"SecondChanceStockReady".equals(event.eventType()) && !"SecondChanceStockRejected".equals(event.eventType())) return;
        var id=AuctionPaymentTimeoutHandler.uuid(event.payload(),"auctionId");
        if (!id.toString().equals(event.aggregateId())) throw new IllegalArgumentException("Stock aggregate mismatch");
        var span=tracer.nextSpan().name("auction.offer-stock").start();
        try(var ignored=tracer.withSpan(span)) {
            idempotent.handle("auction-offer-stock",event,message->offers.stock(
                    AuctionPaymentTimeoutHandler.uuid(message.payload(),"offerId"),id,"SecondChanceStockReady".equals(message.eventType())));
        } finally { span.end(); }
    }
}
