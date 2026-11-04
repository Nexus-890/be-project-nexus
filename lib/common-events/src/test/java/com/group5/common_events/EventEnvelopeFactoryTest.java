package com.group5.common_events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventEnvelopeFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Tracer tracer = mock(Tracer.class);
    private final EventEnvelopeFactory factory = new EventEnvelopeFactory(tracer, CLOCK);

    @Test
    void fillsTheIdTheTimeAndTheCurrentTraceId() {
        givenCurrentTraceId("4bf92f3577b34da6a3ce929d0e0e4736");

        EventEnvelope<Map<String, String>> envelope =
                factory.create("ProductCreated", "product-1", Map.of("name", "iPhone 15"));

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventType()).isEqualTo("ProductCreated");
        assertThat(envelope.aggregateId()).isEqualTo("product-1");
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        assertThat(envelope.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(envelope.payload()).containsEntry("name", "iPhone 15");
    }

    @Test
    void givesEveryEventItsOwnId() {
        givenCurrentTraceId("4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(factory.create("ProductCreated", "product-1", "x").eventId())
                .isNotEqualTo(factory.create("ProductCreated", "product-1", "x").eventId());
    }

    @Test
    void refusesToCreateAnEventOutsideATrace() {
        when(tracer.currentSpan()).thenReturn(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.create("ProductCreated", "product-1", "x"))
                .withMessageStartingWith("No active trace");
    }

    @Test
    void buildsThePayloadWithTheSameSingleClockReadingAsTheEnvelope() {
        givenCurrentTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(NOW, NOW.plusNanos(1));
        EventEnvelopeFactory timedFactory = new EventEnvelopeFactory(tracer, advancingClock);
        AtomicInteger payloadCalls = new AtomicInteger();

        EventEnvelope<Map<String, Instant>> envelope = timedFactory.createWithTimestamp(
                "CheckoutPaymentRequested", "group-1", occurredAt -> {
                    payloadCalls.incrementAndGet();
                    return Map.of("occurredAt", occurredAt);
                });

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventType()).isEqualTo("CheckoutPaymentRequested");
        assertThat(envelope.aggregateId()).isEqualTo("group-1");
        assertThat(envelope.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        assertThat(envelope.payload().get("occurredAt")).isEqualTo(envelope.occurredAt());
        assertThat(payloadCalls).hasValue(1);
        verify(advancingClock).instant();
    }

    @Test
    void refusesToBuildATimestampedPayloadOutsideATrace() {
        when(tracer.currentSpan()).thenReturn(null);
        AtomicInteger payloadCalls = new AtomicInteger();

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.createWithTimestamp("CheckoutPaymentRequested", "group-1", occurredAt -> {
                    payloadCalls.incrementAndGet();
                    return Map.of("occurredAt", occurredAt);
                }))
                .withMessageStartingWith("No active trace");
        assertThat(payloadCalls).hasValue(0);
    }

    private void givenCurrentTraceId(String traceId) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
    }
}
