package com.group5.notificationservice.health;

import com.group5.notificationservice.eventlog.EventLogRecorder;
import com.group5.notificationservice.shared.config.HealthProperties;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthEventWriterTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final LockingTaskExecutor ALWAYS_LOCKED =
            new DefaultLockingTaskExecutor(configuration -> Optional.of(mock(SimpleLock.class)));

    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private final AtomicReference<BrokerHealth> stored = new AtomicReference<>();
    private final List<HealthEventWriter> writers = new ArrayList<>();
    private HealthStateStore store;
    private EventLogRecorder recorder;

    @BeforeEach
    void setup() {
        store = mock(HealthStateStore.class);
        recorder = mock(EventLogRecorder.class);
        when(store.find(BrokerHealthJob.KAFKA)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(store).save(any());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            pending.put(invocation.getArgument(1, UUID.class).toString(), invocation.getArgument(2));
            return null;
        }).when(store).saveTransition(any(), any(), anyString());
        when(store.pendingEvents(BrokerHealthJob.KAFKA)).thenAnswer(invocation -> Map.copyOf(pending));
        doAnswer(invocation -> pending.remove(invocation.<String>getArgument(1)))
                .when(store).removePending(eq(BrokerHealthJob.KAFKA), anyString());
        when(recorder.recordRaw(anyString(), isNull())).thenReturn(true);
    }

    @AfterEach
    void stopWriters() {
        writers.forEach(HealthEventWriter::destroy);
    }

    @Test
    void recordsEveryPendingEnvelopeAndRemovesItOnlyAfterTheWrite() {
        queue(3);

        Duration next = writer(20, Duration.ofSeconds(10)).drain();

        verify(recorder, times(3)).recordRaw(anyString(), isNull());
        verify(store, times(3)).removePending(eq(BrokerHealthJob.KAFKA), anyString());
        assertThat(pending).isEmpty();
        assertThat(next).isEqualTo(INTERVAL);
    }

    @Test
    void theFirstFailureStopsTheRoundKeepsTheEnvelopesAndBacksOff() {
        queue(3);
        when(recorder.recordRaw(anyString(), isNull()))
                .thenThrow(new DataAccessResourceFailureException("event_logs unreachable"));
        HealthEventWriter writer = writer(20, Duration.ofSeconds(10));

        List<Duration> delays = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            delays.add(writer.drain());
        }

        verify(recorder, times(5)).recordRaw(anyString(), isNull());
        verify(store, never()).removePending(anyString(), anyString());
        assertThat(pending).hasSize(3);
        assertThat(delays).containsExactly(INTERVAL, Duration.ofSeconds(10), Duration.ofSeconds(20),
                MAX_BACKOFF, MAX_BACKOFF);
    }

    @Test
    void aSuccessfulRoundResetsTheBackoff() {
        queue(1);
        when(recorder.recordRaw(anyString(), isNull()))
                .thenThrow(new DataAccessResourceFailureException("event_logs unreachable"))
                .thenThrow(new DataAccessResourceFailureException("event_logs unreachable"))
                .thenReturn(true);
        HealthEventWriter writer = writer(20, Duration.ofSeconds(10));

        assertThat(writer.drain()).isEqualTo(INTERVAL);
        assertThat(writer.drain()).isEqualTo(Duration.ofSeconds(10));
        assertThat(writer.drain()).isEqualTo(INTERVAL);
        assertThat(pending).isEmpty();

        queue(1);
        when(recorder.recordRaw(anyString(), isNull()))
                .thenThrow(new DataAccessResourceFailureException("event_logs unreachable"));
        assertThat(writer.drain()).isEqualTo(INTERVAL);
    }

    @Test
    void oneRoundRecordsAtMostOneBatchAndAsksForAnImmediateNextRound() {
        queue(25);
        HealthEventWriter writer = writer(20, Duration.ofSeconds(10));

        assertThat(writer.drain()).isEqualTo(Duration.ZERO);
        assertThat(pending).hasSize(5);
        assertThat(writer.drain()).isEqualTo(INTERVAL);
        assertThat(pending).isEmpty();
    }

    @Test
    void oneRoundStopsWhenItsTimeBudgetIsUsed() {
        queue(5);
        when(recorder.recordRaw(anyString(), isNull())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return true;
        });

        Duration next = writer(20, Duration.ofMillis(300)).drain();

        assertThat(next).isEqualTo(Duration.ZERO);
        assertThat(pending).hasSize(3);
    }

    @Test
    void aRoundWhoseLockIsHeldByAnotherReplicaRecordsNothing() {
        queue(1);
        HealthEventWriter writer = new HealthEventWriter(store, recorder,
                new DefaultLockingTaskExecutor(configuration -> Optional.empty()), 20, Duration.ofSeconds(10),
                INTERVAL, MAX_BACKOFF);
        writers.add(writer);

        writer.round();

        verify(recorder, never()).recordRaw(anyString(), any());
        assertThat(pending).hasSize(1);
    }

    @Test
    void aHangingRecorderNeverDelaysTheBrokerCheck() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(recorder.recordRaw(anyString(), isNull())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return true;
        });
        HealthEventWriter writer = new HealthEventWriter(store, recorder, ALWAYS_LOCKED, 20,
                Duration.ofSeconds(10), Duration.ofMillis(50), MAX_BACKOFF);
        writers.add(writer);
        writer.afterPropertiesSet();
        KafkaBrokerProbe probe = mock(KafkaBrokerProbe.class);
        HealthProperties properties = new HealthProperties(List.of("user-service"),
                URI.create("http://127.0.0.1:1/realms/nexus"), Duration.ofSeconds(1), "test");
        @SuppressWarnings("unchecked")
        HealthTransitionEvents events = new HealthTransitionEvents(mock(ObjectProvider.class), properties);
        BrokerHealthJob job = new BrokerHealthJob(probe, store, events, JsonMapper.builder().build());

        when(probe.reachable()).thenReturn(false);
        job.checkBrokers();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        when(probe.reachable()).thenReturn(true);
        long started = System.nanoTime();
        job.checkBrokers();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        assertThat(stored.get().status()).isEqualTo(HealthStatus.UP);
        assertThat(stored.get().version()).isEqualTo(2);
        assertThat(pending).hasSize(2);

        release.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(pending).isEmpty();
        verify(recorder, times(2)).recordRaw(anyString(), isNull());
    }

    private HealthEventWriter writer(int batch, Duration budget) {
        HealthEventWriter writer = new HealthEventWriter(store, recorder, ALWAYS_LOCKED, batch, budget, INTERVAL,
                MAX_BACKOFF);
        writers.add(writer);
        return writer;
    }

    private void queue(int count) {
        for (int i = 0; i < count; i++) {
            pending.put(UUID.randomUUID().toString(), "{\"n\":" + i + "}");
        }
    }
}
