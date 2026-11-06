package com.group5.notificationservice.delivery;

import com.group5.common_events.EventEnvelope;
import com.group5.notificationservice.eventlog.EventLogRecorder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class DeliveryEventRecorder {

    private final EventLogRecorder eventLogs;
    private final ObjectProvider<Tracer> tracer;

    public DeliveryEventRecorder(EventLogRecorder eventLogs, ObjectProvider<Tracer> tracer) {
        this.eventLogs = eventLogs;
        this.tracer = tracer;
    }

    public void sent(DeliveryEvents.Sent payload) {
        record("NotificationSent", payload.deliveryId(), payload.sentAt(), payload);
    }

    public void failed(DeliveryEvents.Failed payload) {
        record("NotificationFailed", payload.deliveryId(), payload.failedAt(), payload);
    }

    // GHI CẢ ENVELOPE QUA RECORDER CỦA #192, CÙNG TRANSACTION VỚI KẾT QUẢ GỬI, KHÔNG ĐI QUA KAFKA (TOPIC = none)
    // -> API DETAIL ĐỌC ĐƯỢC payload, METADATA VÀ rawEnvelope NHƯ EVENT TỪ KAFKA
    private void record(String eventType, UUID deliveryId, Instant occurredAt, Object payload) {
        EventEnvelope<Object> envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, deliveryId.toString(),
                occurredAt, traceId(), payload);
        eventLogs.record(envelope);
    }

    private String traceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
