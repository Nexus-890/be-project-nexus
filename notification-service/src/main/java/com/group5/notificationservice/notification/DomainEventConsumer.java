package com.group5.notificationservice.notification;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

@Component
public class DomainEventConsumer {

    static final String CONSUMER = "notification-domain-events";

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final IdempotentConsumer idempotentConsumer;
    private final NotificationRepository notificationRepository;
    private final List<NotificationChannel> channels;

    public DomainEventConsumer(IdempotentConsumer idempotentConsumer,
                               NotificationRepository notificationRepository,
                               List<NotificationChannel> channels) {
        this.idempotentConsumer = idempotentConsumer;
        this.notificationRepository = notificationRepository;
        this.channels = channels;
    }

    @KafkaListener(
            topics = {Topics.COMMERCE, Topics.CATALOG},
            groupId = "${nexus.notification.group-id}",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY
    )
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> envelope = record.value();
        Optional<NotificationTemplate> template = NotificationTemplate.forEventType(envelope.eventType());
        if (template.isEmpty()) {
            log.debug("NOTIFICATION_SKIPPED eventId={} eventType={} topic={} reason=no-template",
                    envelope.eventId(), envelope.eventType(), record.topic());
            return;
        }

        boolean handled = idempotentConsumer.handle(CONSUMER, envelope, event -> {
            Notification notification = notificationRepository.save(template.get().render(event.payload()));
            channels.forEach(channel -> channel.send(notification));
        });
        if (handled) {
            log.info("NOTIFICATION_CREATED eventId={} eventType={} aggregateId={}",
                    envelope.eventId(), envelope.eventType(), envelope.aggregateId());
        }
    }
}
