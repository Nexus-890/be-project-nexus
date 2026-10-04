package com.group5.notificationservice.eventlog.kafka.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

class KafkaDemoListenerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-03T10:15:30Z");
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void receivePersistsPayloadEventIdAndKafkaMetadataWithoutProfile() {
        KafkaDemoMessageRepository repository = mock(KafkaDemoMessageRepository.class);
        when(repository.saveIfNotProcessed(any())).thenReturn(true);
        KafkaDemoListener listener = new KafkaDemoListener(
                repository,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                JsonMapper.shared(),
                Duration.ZERO,
                "notification-demo"
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "nexus.training.integration.v1",
                2,
                42L,
                "training-1",
                "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{\"message\":\"hello\"}}"
        );

        listener.receive(record);

        ArgumentCaptor<KafkaDemoEvent> eventCaptor = ArgumentCaptor.forClass(KafkaDemoEvent.class);
        verify(repository).saveIfNotProcessed(eventCaptor.capture());
        KafkaDemoEvent event = eventCaptor.getValue();
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventIdSource()).isEqualTo("payload.eventId");
        assertThat(event.key()).isEqualTo("training-1");
        assertThat(event.topic()).isEqualTo("nexus.training.integration.v1");
        assertThat(event.partition()).isEqualTo(2);
        assertThat(event.offset()).isEqualTo(42L);
        assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.value()).contains("hello");
    }

    @Test
    void receiveUsesStableMetadataEventIdWhenPayloadEventIdIsMissingOrInvalid() {
        KafkaDemoMessageRepository repository = mock(KafkaDemoMessageRepository.class);
        KafkaDemoListener listener = new KafkaDemoListener(
                repository,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                JsonMapper.shared(),
                Duration.ZERO,
                "notification-demo"
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 7L, "key", "not-json");

        listener.receive(record);
        listener.receive(record);

        ArgumentCaptor<KafkaDemoEvent> eventCaptor = ArgumentCaptor.forClass(KafkaDemoEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).saveIfNotProcessed(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(KafkaDemoEvent::eventId)
                .containsOnly(eventCaptor.getAllValues().getFirst().eventId());
        assertThat(eventCaptor.getAllValues().getFirst().eventIdSource()).isEqualTo("kafka.metadata");
    }

    @Test
    void interruptedDelayPersistsMessageThenThrowsAndKeepsInterruptFlagSet() throws InterruptedException {
        KafkaDemoMessageRepository repository = mock(KafkaDemoMessageRepository.class);
        KafkaDemoListener listener = new KafkaDemoListener(
                repository,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                JsonMapper.shared(),
                Duration.ofSeconds(30),
                "notification-demo"
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "nexus.training.integration.v1",
                1,
                8L,
                "training-2",
                "{\"eventId\":\"11111111-1111-1111-1111-111111111111\"}"
        );
        RuntimeException[] thrown = new RuntimeException[1];
        boolean[] interrupted = new boolean[1];

        Thread listenerThread = new Thread(() -> {
            try {
                listener.receive(record);
            } catch (RuntimeException ex) {
                thrown[0] = ex;
                interrupted[0] = Thread.currentThread().isInterrupted();
            }
        });
        listenerThread.start();

        while (listenerThread.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(10);
        }
        listenerThread.interrupt();
        listenerThread.join(5_000);

        assertThat(listenerThread.isAlive()).isFalse();
        assertThat(thrown[0]).isInstanceOf(IllegalStateException.class);
        assertThat(interrupted[0]).isTrue();
        verify(repository).saveIfNotProcessed(any(KafkaDemoEvent.class));
    }

    @Test
    void validatesProcessingDelayBounds() {
        assertThat(KafkaDemoListener.validateProcessingDelay(0)).isEqualTo(Duration.ZERO);
        assertThat(KafkaDemoListener.validateProcessingDelay(120_000)).isEqualTo(Duration.ofMillis(120_000));
        assertThatThrownBy(() -> KafkaDemoListener.validateProcessingDelay(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaDemoListener.validateProcessingDelay(120_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
