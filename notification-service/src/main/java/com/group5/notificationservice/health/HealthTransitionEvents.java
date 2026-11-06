package com.group5.notificationservice.health;

import com.group5.common_events.EventEnvelope;
import com.group5.notificationservice.health.event.SystemHealthCheckFailed;
import com.group5.notificationservice.health.event.SystemHealthCheckRecovered;
import com.group5.notificationservice.shared.config.HealthProperties;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class HealthTransitionEvents {

    public static final String FAILED = "SystemHealthCheckFailed";
    public static final String RECOVERED = "SystemHealthCheckRecovered";
    public static final String BROKER_UNREACHABLE = "BROKER_UNREACHABLE";

    private final ObjectProvider<Tracer> tracer;
    private final String environment;

    public HealthTransitionEvents(ObjectProvider<Tracer> tracer, HealthProperties properties) {
        this.tracer = tracer;
        this.environment = properties.environment();
    }

    // CHỈ 2 LẦN CHUYỂN CÓ EVENT: ->DOWN VÀ DOWN->UP. UNKNOWN->UP (LẦN ĐẦU THẤY UP) KHÔNG PHẢI SỰ CỐ
    // POLL KHÔNG ĐỔI TRẠNG THÁI THÌ KHÔNG CÓ EVENT
    public Optional<EventEnvelope<?>> between(BrokerHealth previous, BrokerHealth current) {
        if (current.version() == previous.version()) {
            return Optional.empty();
        }
        if (current.status() == HealthStatus.DOWN) {
            return Optional.of(envelope(FAILED, current, new SystemHealthCheckFailed(current.name(), environment,
                    previous.status(), current.status(), current.version(), current.lastCheckedAt(),
                    current.lastFailureAt(), current.lastRecoveredAt(), BROKER_UNREACHABLE)));
        }
        if (current.status() == HealthStatus.UP && previous.status() == HealthStatus.DOWN) {
            return Optional.of(envelope(RECOVERED, current, new SystemHealthCheckRecovered(current.name(), environment,
                    previous.status(), current.status(), current.version(), current.lastCheckedAt(),
                    current.lastFailureAt(), current.lastRecoveredAt())));
        }
        return Optional.empty();
    }

    private <T> EventEnvelope<T> envelope(String type, BrokerHealth current, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, environment + ":" + current.name(),
                current.lastCheckedAt(), traceId(), payload);
    }

    // JOB CHẠY NỀN CÓ THỂ KHÔNG CÓ SPAN (TRACING TẮT) -> TỰ SINH traceId ĐỂ RECORDER KHÔNG TỪ CHỐI
    private String traceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
