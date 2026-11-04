package com.group5.notificationservice.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerHealthJob {

    public static final String KAFKA = "kafka";

    private final KafkaBrokerProbe probe;
    private final BrokerHealthRepository repository;

    @Scheduled(fixedDelayString = "${nexus.health.broker-interval:PT15S}")
    @SchedulerLock(name = "brokerHealthJob", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    public void checkBrokers() {
        HealthStatus observed = probe.reachable() || probe.reachable() ? HealthStatus.UP : HealthStatus.DOWN;
        BrokerHealth health = repository.findById(KAFKA).orElseGet(() -> new BrokerHealth(KAFKA));
        HealthStatus previous = health.getStatus();
        health.record(observed, OffsetDateTime.now());
        repository.save(health);
        if (previous != observed) {
            log.info("Broker {} changed from {} to {}", KAFKA, previous, observed);
        }
    }
}
