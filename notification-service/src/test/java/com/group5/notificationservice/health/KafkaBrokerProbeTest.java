package com.group5.notificationservice.health;

import com.group5.notificationservice.shared.config.HealthProperties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KafkaBrokerProbeTest {

    @Test
    void anUnreachableBrokerIsReportedWithinTheTimeout() {
        KafkaAdmin admin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1"));
        HealthProperties properties = new HealthProperties(List.of("user-service"),
                URI.create("http://127.0.0.1:1/realms/nexus"), Duration.ofSeconds(1));
        KafkaBrokerProbe probe = new KafkaBrokerProbe(admin, properties);

        long started = System.nanoTime();
        assertThatCode(() -> assertThat(probe.reachable()).isFalse()).doesNotThrowAnyException();
        probe.destroy();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
