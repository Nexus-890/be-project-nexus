package com.group5.catalogservice.eventtest;

import com.group5.catalogservice.eventtest.dto.EventTestRequest;
import com.group5.catalogservice.eventtest.dto.EventTestResponse;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventTestServiceTest {

    private static final String AGGREGATE_ID = "69000000-0000-4000-8000-000000000201";

    private final EventEnvelopeFactory envelopeFactory = mock(EventEnvelopeFactory.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate = mock(KafkaTemplate.class);
    private final EventTestService service = new EventTestService(envelopeFactory, kafkaTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void publishesTheEnvelopeToTheCatalogTopicKeyedByAggregateId() {
        EventEnvelope<EventTestPayload> envelope = new EventEnvelope<>(
                UUID.fromString("01936b2a-7c4e-7000-8000-000000000001"),
                EventTestService.EVENT_TYPE,
                AGGREGATE_ID,
                Instant.parse("2026-10-19T08:00:00Z"),
                "4bf92f3577b34da6a3ce9 29d0e0e4736",
                new EventTestPayload("hello"));
        when(envelopeFactory.create(eq(EventTestService.EVENT_TYPE), eq(AGGREGATE_ID), any(EventTestPayload.class)))
                .thenReturn(envelope);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(Topics.CATALOG, 0), 7, 0, 0, 0, 0);
        when(kafkaTemplate.send(eq(Topics.CATALOG), eq(AGGREGATE_ID), any(EventEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new SendResult<>(new ProducerRecord<>(Topics.CATALOG, AGGREGATE_ID, envelope), metadata)));

        EventTestResponse response = service.publish(new EventTestRequest(AGGREGATE_ID, "hello"));

        ArgumentCaptor<EventTestPayload> payload = ArgumentCaptor.forClass(EventTestPayload.class);
        verify(envelopeFactory).create(eq(EventTestService.EVENT_TYPE), eq(AGGREGATE_ID), payload.capture());
        assertThat(payload.getValue().message()).isEqualTo("hello");
        verify(kafkaTemplate).send(Topics.CATALOG, AGGREGATE_ID, envelope);
        assertThat(response).isEqualTo(new EventTestResponse(
                envelope.eventId(), EventTestService.EVENT_TYPE, envelope.traceId(), Topics.CATALOG, 0, 7));
    }
}
