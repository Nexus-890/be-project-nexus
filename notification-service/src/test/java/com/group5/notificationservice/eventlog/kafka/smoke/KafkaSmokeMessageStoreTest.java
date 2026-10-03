package com.group5.notificationservice.eventlog.kafka.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class KafkaSmokeMessageStoreTest {

    @Test
    void snapshotKeepsOnlyLatestOneHundredMessages() {
        KafkaSmokeMessageStore store = new KafkaSmokeMessageStore();

        for (int i = 0; i < 105; i++) {
            store.add(new KafkaSmokeMessage(
                    "event-" + i,
                    "key-" + i,
                    "nexus.training.integration.v1",
                    i % 4,
                    i,
                    Instant.EPOCH.plusSeconds(i),
                    "{}"
            ));
        }

        assertThat(store.snapshot())
                .hasSize(100)
                .extracting(KafkaSmokeMessage::eventId)
                .first()
                .isEqualTo("event-5");
        assertThat(store.snapshot().get(99).eventId()).isEqualTo("event-104");
    }
}
