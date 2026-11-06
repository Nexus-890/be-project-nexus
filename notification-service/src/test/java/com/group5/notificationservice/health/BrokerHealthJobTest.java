package com.group5.notificationservice.health;

import com.group5.notificationservice.eventlog.EventLogRecorder;
import com.group5.notificationservice.shared.config.HealthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerHealthJobTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private KafkaBrokerProbe probe;
    private HealthStateStore store;
    private EventLogRecorder recorder;
    private BrokerHealthJob job;
    private BrokerHealth stored;
    private final Map<String, String> pending = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        probe = mock(KafkaBrokerProbe.class);
        store = mock(HealthStateStore.class);
        recorder = mock(EventLogRecorder.class);
        HealthProperties properties = new HealthProperties(List.of("user-service"),
                URI.create("http://127.0.0.1:1/realms/nexus"), Duration.ofSeconds(1), "test");
        HealthTransitionEvents events = new HealthTransitionEvents(mock(ObjectProvider.class), properties);
        job = new BrokerHealthJob(probe, store, events, recorder, mapper);

        when(store.find(BrokerHealthJob.KAFKA)).thenAnswer(invocation -> Optional.ofNullable(stored));
        doAnswer(invocation -> stored = invocation.getArgument(0)).when(store).save(any());
        doAnswer(invocation -> {
            stored = invocation.getArgument(0);
            pending.put(invocation.getArgument(1, UUID.class).toString(), invocation.getArgument(2));
            return null;
        }).when(store).saveTransition(any(), any(), anyString());
        when(store.pendingEvents(BrokerHealthJob.KAFKA)).thenAnswer(invocation -> Map.copyOf(pending));
        doAnswer(invocation -> pending.remove(invocation.<String>getArgument(1)))
                .when(store).removePending(eq(BrokerHealthJob.KAFKA), anyString());
        when(recorder.recordRaw(anyString(), isNull())).thenReturn(true);
    }

    @Test
    void oneFailedAttemptIsRetriedBeforeMarkingDown() {
        when(probe.reachable()).thenReturn(false, true);

        job.checkBrokers();

        verify(probe, times(2)).reachable();
        assertThat(stored.status()).isEqualTo(HealthStatus.UP);
        assertThat(stored.lastFailureAt()).isNull();
        verify(recorder, never()).recordRaw(anyString(), any());
    }

    @Test
    void aFailedRetryMarksTheBrokerDownAndRecordsOneFailedEvent() {
        when(probe.reachable()).thenReturn(false, false);

        job.checkBrokers();

        assertThat(stored.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(stored.lastFailureAt()).isNotNull();
        JsonNode event = recorded().getFirst();
        assertThat(event.get("eventType").asText()).isEqualTo(HealthTransitionEvents.FAILED);
        assertThat(event.get("aggregateId").asText()).isEqualTo("test:kafka");
        JsonNode payload = event.get("payload");
        assertThat(payload.get("serviceName").asText()).isEqualTo("kafka");
        assertThat(payload.get("environment").asText()).isEqualTo("test");
        assertThat(payload.get("previousStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(payload.get("status").asText()).isEqualTo("DOWN");
        assertThat(payload.get("healthVersion").asLong()).isEqualTo(1);
        assertThat(payload.get("checkedAt").asText()).endsWith("Z");
        assertThat(payload.get("lastFailureAt").asText()).isEqualTo(payload.get("checkedAt").asText());
        assertThat(payload.has("lastRecoveredAt")).isTrue();
        assertThat(payload.get("lastRecoveredAt").isNull()).isTrue();
        assertThat(payload.get("reasonCode").asText()).isEqualTo(HealthTransitionEvents.BROKER_UNREACHABLE);
        assertThat(pending).isEmpty();
    }

    @Test
    void stayingDownDoesNotRecordAnotherEvent() {
        when(probe.reachable()).thenReturn(false);

        job.checkBrokers();
        job.checkBrokers();
        job.checkBrokers();

        verify(recorder, times(1)).recordRaw(anyString(), isNull());
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void theRecoveryIsRecordedOnceAndKeepsTheFailureTime() {
        when(probe.reachable()).thenReturn(false, false, true, true);

        job.checkBrokers();
        job.checkBrokers();
        job.checkBrokers();

        List<JsonNode> events = recorded();
        assertThat(events).extracting(event -> event.get("eventType").asText())
                .containsExactly(HealthTransitionEvents.FAILED, HealthTransitionEvents.RECOVERED);
        JsonNode recovered = events.get(1).get("payload");
        assertThat(recovered.get("previousStatus").asText()).isEqualTo("DOWN");
        assertThat(recovered.get("status").asText()).isEqualTo("UP");
        assertThat(recovered.get("healthVersion").asLong()).isEqualTo(2);
        assertThat(recovered.get("lastFailureAt").asText()).isEqualTo(stored.lastFailureAt().toString());
        assertThat(recovered.get("lastRecoveredAt").asText()).isEqualTo(stored.lastRecoveredAt().toString());
        assertThat(recovered.has("reasonCode")).isFalse();
    }

    @Test
    void anEventThatCannotBeRecordedStaysPendingAndIsRecordedOnTheNextRun() {
        when(probe.reachable()).thenReturn(false);
        when(recorder.recordRaw(anyString(), isNull()))
                .thenThrow(new DataAccessResourceFailureException("event_logs down"))
                .thenReturn(true);

        job.checkBrokers();
        assertThat(pending).hasSize(1);
        String envelope = pending.values().iterator().next();

        job.checkBrokers();

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(recorder, times(2)).recordRaw(raw.capture(), isNull());
        assertThat(raw.getAllValues()).containsOnly(envelope);
        assertThat(pending).isEmpty();
    }

    @Test
    void aReachableBrokerIsCheckedOnce() {
        when(probe.reachable()).thenReturn(true);

        job.checkBrokers();

        verify(probe, times(1)).reachable();
        assertThat(stored.status()).isEqualTo(HealthStatus.UP);
    }

    private List<JsonNode> recorded() {
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(recorder, atLeastOnce()).recordRaw(raw.capture(), isNull());
        return raw.getAllValues().stream().map(mapper::readTree).toList();
    }
}
