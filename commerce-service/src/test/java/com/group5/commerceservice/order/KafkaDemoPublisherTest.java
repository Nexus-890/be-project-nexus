package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaDemoRequest;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaDemoPublisherTest {
    private static final String TOPIC = "nexus.training.integration.v1";
    private static final Instant NOW = Instant.parse("2026-10-03T10:15:30Z");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final KafkaDemoPublisher publisher = new KafkaDemoPublisher(
            template, jdbcTemplate, mapper, Clock.fixed(NOW, ZoneOffset.UTC), TOPIC);

    @Test
    void persistsOutboxPublishesEnvelopeAndMarksItPublished() {
        var metadata = new RecordMetadata(new TopicPartition(TOPIC, 2), 7, 0, 0, 0, 0);
        var sendResult = new SendResult<>(new ProducerRecord<>(TOPIC, "order-1", "ignored"), metadata);
        when(template.send(eq(TOPIC), eq("order-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        var result = publisher.publish(new KafkaDemoRequest("order-1", "hello"));

        var insertSql = ArgumentCaptor.forClass(String.class);
        var outboxId = ArgumentCaptor.forClass(UUID.class);
        var aggregateId = ArgumentCaptor.forClass(String.class);
        var topic = ArgumentCaptor.forClass(String.class);
        var payload = ArgumentCaptor.forClass(String.class);
        var traceId = ArgumentCaptor.forClass(String.class);
        var occurredAt = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbcTemplate).update(insertSql.capture(), outboxId.capture(), aggregateId.capture(), topic.capture(),
                payload.capture(), traceId.capture(), occurredAt.capture());

        assertThat(insertSql.getValue()).contains("INSERT INTO outbox");
        assertThat(outboxId.getValue()).isEqualTo(result.outboxId());
        assertThat(aggregateId.getValue()).isEqualTo("order-1");
        assertThat(topic.getValue()).isEqualTo(TOPIC);
        assertThat(occurredAt.getValue().toInstant()).isEqualTo(NOW);

        var kafkaValue = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq(TOPIC), eq("order-1"), kafkaValue.capture());
        assertThat(kafkaValue.getValue()).isEqualTo(payload.getValue());

        var event = mapper.readTree(payload.getValue());
        assertThat(event.path("eventId").asString()).isEqualTo(result.eventId());
        assertThat(event.path("aggregateId").asString()).isEqualTo("order-1");
        assertThat(event.path("eventType").asString()).isEqualTo("TrainingEvent");
        assertThat(event.path("producer").asString()).isEqualTo("commerce-service");
        assertThat(event.path("payload").path("message").asString()).isEqualTo("hello");
        assertThat(result.partition()).isEqualTo(2);
        assertThat(result.offset()).isEqualTo(7);
        assertThat(result.topic()).isEqualTo(TOPIC);

        verify(jdbcTemplate).update(eq("UPDATE outbox SET published_at = ? WHERE id = ?"), any(Timestamp.class), eq(result.outboxId()));
    }

    @Test
    void failedAcknowledgementIsNotReportedAsSuccessfulPublishAndLeavesPublishedAtUnset() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThatThrownBy(() -> publisher.publish(new KafkaDemoRequest("order-1", "hello")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(jdbcTemplate, never()).update(eq("UPDATE outbox SET published_at = ? WHERE id = ?"), any(), any());
    }
    @Test
    void demoComponentsAreAbsentInSmokeProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("kafka-smoke");
            context.register(KafkaDemoPublisher.class, KafkaDemoController.class);
            context.refresh();

            assertThat(context.getBeansOfType(KafkaDemoPublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaDemoController.class)).isEmpty();
        }
    }

}
