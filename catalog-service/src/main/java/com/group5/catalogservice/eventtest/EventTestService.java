package com.group5.catalogservice.eventtest;

import com.group5.catalogservice.eventtest.dto.EventTestRequest;
import com.group5.catalogservice.eventtest.dto.EventTestResponse;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Profile(EventTestService.PROFILE)
public class EventTestService {

    public static final String PROFILE = "event-test";
    static final String EVENT_TYPE = "CatalogTestEvent";
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final Logger log = LoggerFactory.getLogger(EventTestService.class);

    private final EventEnvelopeFactory envelopeFactory;
    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate;

    public EventTestService(
            EventEnvelopeFactory envelopeFactory,
            @Qualifier(EventKafka.TEMPLATE) KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate) {
        this.envelopeFactory = envelopeFactory;
        this.kafkaTemplate = kafkaTemplate;
    }

    public EventTestResponse publish(EventTestRequest request) {
        EventEnvelope<EventTestPayload> envelope = envelopeFactory.create(
                EVENT_TYPE, request.aggregateId(), new EventTestPayload(request.message()));

        RecordMetadata metadata = send(envelope).getRecordMetadata();

        log.info("EVENT_PUBLISHED eventId={} eventType={} aggregateId={} traceId={} topic={} partition={} offset={}",
                envelope.eventId(), envelope.eventType(), envelope.aggregateId(), envelope.traceId(),
                metadata.topic(), metadata.partition(), metadata.offset());

        return new EventTestResponse(
                envelope.eventId(),
                envelope.eventType(),
                envelope.traceId(),
                metadata.topic(),
                metadata.partition(),
                metadata.offset());
    }

    private SendResult<String, EventEnvelope<?>> send(EventEnvelope<?> envelope) {
        try {
            return kafkaTemplate.send(Topics.CATALOG, envelope.aggregateId(), envelope)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing event " + envelope.eventId(), ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Could not publish event " + envelope.eventId() + " to " + Topics.CATALOG, ex);
        }
    }
}
