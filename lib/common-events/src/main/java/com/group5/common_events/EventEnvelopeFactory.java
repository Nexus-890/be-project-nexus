package com.group5.common_events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public class EventEnvelopeFactory {

    private final Tracer tracer;
    private final Clock clock;

    public EventEnvelopeFactory(Tracer tracer, Clock clock) {
        this.tracer = tracer;
        this.clock = clock;
    }
    //sinh eventId, occurredAt và lấy traceId của request hiện tại.
    public <T> EventEnvelope<T> create(String eventType, String aggregateId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                aggregateId,
                Instant.now(clock),
                currentTraceId(),
                payload);
    }

    public <T> EventEnvelope<T> createWithTimestamp(String eventType, String aggregateId,
                                                   Function<Instant, T> payloadFactory) {
        Objects.requireNonNull(payloadFactory, "payloadFactory must not be null");
        Instant occurredAt = Instant.now(clock);
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                aggregateId,
                occurredAt,
                currentTraceId(),
                payloadFactory.apply(occurredAt));
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalStateException(
                    "No active trace: create events inside a traced request, listener or job");
        }
        return traceId;
    }
}
