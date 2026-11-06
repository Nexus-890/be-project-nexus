package com.group5.notificationservice.health;

import com.group5.common_events.EventEnvelope;
import com.group5.notificationservice.eventlog.EventLogRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerHealthJob {

    public static final String KAFKA = "kafka";
    public static final List<String> BROKERS = List.of(KAFKA);

    private final KafkaBrokerProbe probe;
    private final HealthStateStore store;
    private final HealthTransitionEvents events;
    private final EventLogRecorder recorder;
    private final JsonMapper mapper;

    @Scheduled(fixedDelayString = "${nexus.health.broker-interval:PT15S}")
    @SchedulerLock(name = "brokerHealthJob", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    public void checkBrokers() {
        HealthStatus observed = probe.reachable() || probe.reachable() ? HealthStatus.UP : HealthStatus.DOWN;
        BrokerHealth previous = store.find(KAFKA).orElseGet(() -> BrokerHealth.unknown(KAFKA));
        BrokerHealth current = previous.observe(observed, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        Optional<EventEnvelope<?>> transition = events.between(previous, current);
        if (transition.isPresent()) {
            store.saveTransition(current, transition.get().eventId(), mapper.writeValueAsString(transition.get()));
        } else {
            store.save(current);
        }
        recordPending(KAFKA);
    }

    // GHI QUA RECORDER CỦA #192 (event_logs, ON CONFLICT event_id) -> GHI LẠI NHIỀU LẦN VẪN CHỈ 1 DÒNG
    // POSTGRES LỖI THÌ ENVELOPE CÒN TRONG REDIS, LẦN CHẠY SAU GHI TIẾP. KAFKA CHẾT KHÔNG ẢNH HƯỞNG VIỆC GHI
    private void recordPending(String broker) {
        store.pendingEvents(broker).forEach((eventId, envelope) -> {
            try {
                recorder.recordRaw(envelope, null);
                store.removePending(broker, eventId);
            } catch (RuntimeException error) {
                log.warn("Health transition not recorded yet, retrying next run: broker={} eventId={}",
                        broker, eventId, error);
            }
        });
    }
}
