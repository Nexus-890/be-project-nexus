package com.group5.common_events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
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

    private void givenCurrentTraceId(String traceId) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
    }
}
