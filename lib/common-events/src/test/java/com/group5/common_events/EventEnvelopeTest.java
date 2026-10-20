package com.group5.common_events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EventEnvelopeTest {

    private static final UUID EVENT_ID = UUID.fromString("01936b2a-7c4e-7000-8000-000000000001");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-19T08:00:00Z");

    @Test
    void keepsEveryField() {
        EventEnvelope<Map<String, String>> envelope = new EventEnvelope<>(
                EVENT_ID, "ProductCreated", "product-1", OCCURRED_AT, "trace-1", Map.of("name", "iPhone 15"));

        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.eventType()).isEqualTo("ProductCreated");
        assertThat(envelope.aggregateId()).isEqualTo("product-1");
        assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.payload()).containsEntry("name", "iPhone 15");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "aggregateId", "occurredAt", "traceId", "payload"})
    void rejectsAMissingField(String field) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> envelopeWithout(field))
                .withMessageStartingWith(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsABlankTextField(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(EVENT_ID, blank, "product-1", OCCURRED_AT, "trace-1", "x"))
                .withMessage("eventType must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(EVENT_ID, "ProductCreated", blank, OCCURRED_AT, "trace-1", "x"))
                .withMessage("aggregateId must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(EVENT_ID, "ProductCreated", "product-1", OCCURRED_AT, blank, "x"))
                .withMessage("traceId must not be blank");
    }

    private static EventEnvelope<String> envelopeWithout(String field) {
        return new EventEnvelope<>(
                field.equals("eventId") ? null : EVENT_ID,
                field.equals("eventType") ? null : "ProductCreated",
                field.equals("aggregateId") ? null : "product-1",
                field.equals("occurredAt") ? null : OCCURRED_AT,
                field.equals("traceId") ? null : "trace-1",
                field.equals("payload") ? null : "x");
    }
}
