package com.group5.notificationservice.eventlog.kafka.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;


import tools.jackson.databind.json.JsonMapper;

class KafkaSmokeListenerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-03T10:15:30Z");

    @Test
    void receiveStoresEventIdAndKafkaMetadataWithoutDelayByDefault() {
        KafkaSmokeMessageStore store = new KafkaSmokeMessageStore();
        KafkaSmokeListener listener = new KafkaSmokeListener(
                store,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                JsonMapper.shared(),
                Duration.ZERO,
                "notification-smoke"
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "nexus.training.integration.v1",
                2,
                42L,
                "training-1",
                "{\"eventId\":\"evt-123\",\"payload\":{\"message\":\"hello\"}}"
        );

        listener.receive(record);

        assertThat(store.snapshot())
                .containsExactly(new KafkaSmokeMessage(
                        "evt-123",
                        "training-1",
                        "nexus.training.integration.v1",
                        2,
                        42L,
                        RECEIVED_AT,
                        "{\"eventId\":\"evt-123\",\"payload\":{\"message\":\"hello\"}}"
                ));
    }

    @Test
    void receiveStoresInvalidJsonWithNullEventIdSoRawPayloadCanStillBeInspected() {
        KafkaSmokeMessageStore store = new KafkaSmokeMessageStore();
        KafkaSmokeListener listener = new KafkaSmokeListener(
                store,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                JsonMapper.shared(),
                Duration.ZERO,
                "notification-smoke"
        );

        listener.receive(new ConsumerRecord<>("topic", 0, 7L, "key", "not-json"));

        KafkaSmokeMessage message = store.snapshot().getFirst();
        assertThat(message.eventId()).isNull();
        assertThat(message.value()).isEqualTo("not-json");
        assertThat(message.offset()).isEqualTo(7L);
    }

    @Test
    void interruptedDelayStoresMessageThenThrowsAndKeepsInterruptFlagSet() throws InterruptedException {
        KafkaSmokeMessageStore store = new KafkaSmokeMessageStore();
        KafkaSmokeListener listener = new KafkaSmokeListener(
                store,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                JsonMapper.shared(),
                Duration.ofSeconds(30),
                "notification-smoke"
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "nexus.training.integration.v1",
                1,
                8L,
                "training-2",
                "{\"eventId\":\"evt-interrupt\"}"
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

        while (store.snapshot().isEmpty()) {
            Thread.sleep(10);
        }
        listenerThread.interrupt();
        listenerThread.join(5_000);

        assertThat(listenerThread.isAlive()).isFalse();
        assertThat(thrown[0]).isInstanceOf(IllegalStateException.class);
        assertThat(interrupted[0]).isTrue();
        assertThat(store.snapshot())
                .singleElement()
                .extracting(KafkaSmokeMessage::eventId)
                .isEqualTo("evt-interrupt");
    }

    @Test
    void validatesProcessingDelayBounds() {
        assertThat(KafkaSmokeListener.validateProcessingDelay(0)).isEqualTo(Duration.ZERO);
        assertThat(KafkaSmokeListener.validateProcessingDelay(120_000)).isEqualTo(Duration.ofMillis(120_000));
        assertThatThrownBy(() -> KafkaSmokeListener.validateProcessingDelay(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaSmokeListener.validateProcessingDelay(120_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
