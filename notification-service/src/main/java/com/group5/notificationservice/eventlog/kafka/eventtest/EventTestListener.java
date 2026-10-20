package com.group5.notificationservice.eventlog.kafka.eventtest;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
@Profile("event-test")
public class EventTestListener {

    private static final Logger log = LoggerFactory.getLogger(EventTestListener.class);

    @KafkaListener(
            topics = Topics.CATALOG,
            groupId = "notification-event-test",
            containerFactory = EventKafka.LISTENER_CONTAINER_FACTORY
    )
    public void receive(ConsumerRecord<String, EventEnvelope<JsonNode>> record) {
        EventEnvelope<JsonNode> envelope = record.value();
        log.info(
                "EVENT_RECEIVED eventId={} eventType={} aggregateId={} occurredAt={} traceId={} payload={} topic={} partition={} offset={}",
                envelope.eventId(),
                envelope.eventType(),
                envelope.aggregateId(),
                envelope.occurredAt(),
                envelope.traceId(),
                envelope.payload(),
                record.topic(),
                record.partition(),
                record.offset()
        );
    }
}
