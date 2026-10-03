package com.group5.notificationservice.eventlog.kafka.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class KafkaDemoMessageRepositoryTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-03T10:15:30Z");

    @Test
    void saveIfNotProcessedWritesDedupMarkerAndEventLogInOneServiceCall() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(contains("INSERT INTO processed_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO event_logs"), any(Object[].class))).thenReturn(1);
        KafkaDemoMessageRepository repository = new KafkaDemoMessageRepository(jdbcTemplate);

        boolean stored = repository.saveIfNotProcessed(event());

        assertThat(stored).isTrue();
        verify(jdbcTemplate).update(contains("ON CONFLICT (event_id, consumer) DO NOTHING"), any(Object[].class));
        verify(jdbcTemplate).update(contains("INSERT INTO event_logs"), any(Object[].class));
    }

    @Test
    void saveIfNotProcessedSkipsEventLogWhenDedupMarkerAlreadyExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(contains("INSERT INTO processed_events"), any(Object[].class))).thenReturn(0);
        KafkaDemoMessageRepository repository = new KafkaDemoMessageRepository(jdbcTemplate);

        boolean stored = repository.saveIfNotProcessed(event());

        assertThat(stored).isFalse();
        verify(jdbcTemplate, never()).update(contains("INSERT INTO event_logs"), any(Object[].class));
    }

    @Test
    void payloadEscapesRawMessageSoJsonbInsertCanStoreOriginalKafkaValue() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        KafkaDemoMessageRepository repository = new KafkaDemoMessageRepository(jdbcTemplate);

        boolean stored = repository.saveIfNotProcessed(new KafkaDemoEvent(
                EVENT_ID,
                "payload.eventId",
                "key-with-\"quote\"",
                "nexus.training.integration.v1",
                2,
                42L,
                RECEIVED_AT,
                "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"message\":\"line\\nquote\"}\u0001"
        ));

        assertThat(stored).isTrue();
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("?::jsonb"), args.capture());
        var json = JsonMapper.shared().readTree((String) args.getValue()[7]);
        assertThat(json.path("rawValue").asString()).endsWith("\u0001");
        assertThat(json.path("kafka").path("key").asString()).isEqualTo("key-with-\"quote\"");
    }

    private KafkaDemoEvent event() {
        return new KafkaDemoEvent(
                EVENT_ID,
                "payload.eventId",
                "training-1",
                "nexus.training.integration.v1",
                2,
                42L,
                RECEIVED_AT,
                "{\"eventId\":\"11111111-1111-1111-1111-111111111111\"}"
        );
    }
}
