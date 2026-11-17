package com.group5.notificationservice.notification;

import com.group5.common_events.*;
import com.group5.common_outbox.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Deduplicate the business outcome as well as the Kafka envelope. */
@Component
@RequiredArgsConstructor
public class SecondChanceNotificationConsumer {
    private final IdempotentConsumer idempotent;
    private final JdbcTemplate jdbc;
    private final NotificationRepository notifications;
    private final List<NotificationChannel> channels;
    @Value("${nexus.notification.frontend-base-url:http://localhost:3000}") private String frontendBaseUrl;
    @KafkaListener(topics=Topics.AUCTION,groupId="notification-second-chance",containerFactory=EventKafka.LISTENER_CONTAINER_FACTORY)
    public void receive(ConsumerRecord<String,EventEnvelope<JsonNode>> record) {
        var event=record.value();
        if(!isOffer(event.eventType())) return;
        var payload=event.payload();
        UUID offer=UUID.fromString(payload.path("offerId").asString());
        if(!event.aggregateId().equals(payload.path("auctionId").asString()) || !event.aggregateId().equals(record.key()))
            throw new IllegalArgumentException("Offer notification identity mismatch");
        var template=NotificationTemplate.forEventType(event.eventType()).orElseThrow();
        idempotent.handle("notification-second-chance",event,message->{
            var rendered=template.render(payload,frontendBaseUrl);
            if(jdbc.update("INSERT INTO notification_offer_outcomes(offer_id,event_type) VALUES (?,?) ON CONFLICT DO NOTHING",offer,event.eventType())==0) return;
            for(var item:rendered) { var saved=notifications.save(item);channels.forEach(channel->channel.send(saved)); }
        });
    }
    static boolean isOffer(String type) { return Set.of("SecondChanceOfferReady","SecondChanceAccepted","SecondChanceDeclined","SecondChanceExpired").contains(type); }
}
