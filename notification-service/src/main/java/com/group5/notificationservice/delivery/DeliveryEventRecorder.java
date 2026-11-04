package com.group5.notificationservice.delivery;

import com.group5.common_events.EventEnvelope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class DeliveryEventRecorder {

    private final DeliveryEventLogRepository eventLogs;
    private final JsonMapper mapper;
    private final ObjectProvider<Tracer> tracer;

    public DeliveryEventRecorder(DeliveryEventLogRepository eventLogs, JsonMapper mapper,
                                 ObjectProvider<Tracer> tracer) {
        this.eventLogs = eventLogs;
        this.mapper = mapper;
        this.tracer = tracer;
    }

    public void sent(DeliveryEvents.Sent payload) {
        record("NotificationSent", payload.deliveryId(), payload.sentAt(), payload);
    }

    public void failed(DeliveryEvents.Failed payload) {
        record("NotificationFailed", payload.deliveryId(), payload.failedAt(), payload);
    }

    // GHI THẲNG VÀO event_logs TRONG CÙNG TRANSACTION VỚI KẾT QUẢ GỬI, KHÔNG ĐI QUA KAFKA (TOPIC = none)
    // KHI #192 (!121) MERGE THÌ ĐỔI CHỖ NÀY SANG EventLogRecorder.record(envelope)
    private void record(String eventType, UUID deliveryId, Instant occurredAt, Object payload) {
        EventEnvelope<Object> envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, deliveryId.toString(),
                occurredAt, traceId(), payload);
        eventLogs.insert(envelope, mapper.writeValueAsString(envelope.payload()));
    }

    private String traceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
