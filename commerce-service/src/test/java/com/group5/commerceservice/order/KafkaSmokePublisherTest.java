package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaSmokeRequest;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaSmokePublisherTest {
    private static final String TOPIC = "nexus.training.integration.v1";
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final KafkaSmokePublisher publisher = new KafkaSmokePublisher(template, mapper, TOPIC);

    @Test
    void publishesEnvelopeWithKeyAndReturnsAcknowledgedMetadata() {
        var metadata = new RecordMetadata(new TopicPartition(TOPIC, 2), 7, 0, 0, 0, 0);
        var sendResult = new SendResult<>(new ProducerRecord<>(TOPIC, "order-1", "ignored"), metadata);
        when(template.send(eq(TOPIC), eq("order-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        var result = publisher.publish(new KafkaSmokeRequest("order-1", "hello"));

        var value = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq(TOPIC), eq("order-1"), value.capture());
        var event = mapper.readTree(value.getValue());
        assertThat(event.path("eventId").asString()).isEqualTo(result.eventId());
        assertThat(event.path("aggregateId").asString()).isEqualTo("order-1");
        assertThat(event.path("eventType").asString()).isEqualTo("TrainingEvent");
        assertThat(event.path("payload").path("message").asString()).isEqualTo("hello");
        assertThat(result.partition()).isEqualTo(2);
        assertThat(result.offset()).isEqualTo(7);
        assertThat(result.topic()).isEqualTo(TOPIC);
    }

    @Test
    void failedAcknowledgementIsNotReportedAsSuccessfulPublish() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> publisher.publish(new KafkaSmokeRequest("order-1", "hello")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void demoComponentsAreAbsentWithoutSmokeProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(KafkaSmokePublisher.class, KafkaSmokeController.class);
            context.refresh();
            assertThat(context.getBeansOfType(KafkaSmokePublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(KafkaSmokeController.class)).isEmpty();
        }
    }
}
