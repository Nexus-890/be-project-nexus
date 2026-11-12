package com.group5.notificationservice.health;

import com.group5.notificationservice.shared.config.HealthProperties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaBrokerProbe implements DisposableBean {

    private final Admin admin;
    private final Duration timeout;

    public KafkaBrokerProbe(KafkaAdmin kafkaAdmin, HealthProperties properties) {
        int timeoutMs = Math.toIntExact(properties.timeout().toMillis());
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        this.admin = Admin.create(config);
        this.timeout = properties.timeout();
    }

    public boolean reachable() {
        try {
            admin.describeCluster().nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        admin.close(timeout);
    }
}
