package com.group5.notificationservice.health;

import com.group5.common_events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BrokerHealthJob {

    public static final String KAFKA = "kafka";
    public static final List<String> BROKERS = List.of(KAFKA);

    private final KafkaBrokerProbe probe;
    private final HealthStateStore store;
    private final HealthTransitionEvents events;
    private final JsonMapper mapper;

    // CHỈ PROBE VÀ GHI REDIS (TIMEOUT 300ms). VIỆC GHI event_logs DO HealthEventWriter LÀM TRÊN THREAD RIÊNG
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
    }
}
