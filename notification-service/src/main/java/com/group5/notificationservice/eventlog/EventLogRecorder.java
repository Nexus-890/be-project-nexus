package com.group5.notificationservice.eventlog;

import com.group5.common_events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

/** Shared by Kafka ingestion and trusted in-process notification/health producers. */
@Service
public class EventLogRecorder {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public EventLogRecorder(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public boolean record(EventEnvelope<?> envelope) {
        return recordRaw(mapper.writeValueAsString(envelope), null);
    }

    @Transactional
    public boolean recordRaw(String raw, String topic) {
        Metadata metadata = metadata(raw);
        // raw_envelope preserves original bytes as text, including unknown fields.
        // jsonb is only an additional queryable representation of that envelope.
        return jdbc.update("""
                INSERT INTO event_logs (event_id, event_type, aggregate_type, aggregate_id,
                    operator_id, trace_id, payload, raw_envelope, source_topic, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, metadata.eventId(), metadata.type(), aggregateType(metadata.type()), metadata.aggregateId(),
                metadata.actor(), metadata.traceId(), raw, raw, topic, Timestamp.from(metadata.occurredAt())) == 1;
    }

    /** The error handler must commit this write before treating a record as recovered. */
    @Transactional
    public void reject(String topic, int partition, long offset, String key, String raw, String failureType) {
        jdbc.update("""
                INSERT INTO event_log_rejections
                    (source_topic, source_partition, source_offset, record_key, raw_envelope, failure_type)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_topic, source_partition, source_offset) DO NOTHING
                """, topic, partition, offset, key, raw, failureType);
    }

    private Metadata metadata(String raw) {
        JsonNode envelope = mapper.readTree(raw);
        UUID eventId = UUID.fromString(required(envelope, "eventId"));
        String type = required(envelope, "eventType");
        String aggregateId = required(envelope, "aggregateId");
        String traceId = required(envelope, "traceId");
        Instant occurredAt = Instant.parse(required(envelope, "occurredAt"));
        JsonNode payload = envelope.get("payload");
        if (payload == null || payload.isNull()) throw new IllegalArgumentException("payload is required");
        UUID actor = optionalUuid(payload, "actorId");
        if (actor == null) actor = optionalUuid(payload, "operatorId");
        if (type.length() > 80 || aggregateId.length() > 100 || traceId.length() > 64) {
            throw new IllegalArgumentException("event metadata exceeds storage limits");
        }
        return new Metadata(eventId, type, aggregateId, traceId, occurredAt, actor);
    }

    private record Metadata(UUID eventId, String type, String aggregateId, String traceId, Instant occurredAt, UUID actor) { }

    private static String required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonempty string");
        }
        return value.asText();
    }

    private static UUID optionalUuid(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isString()) return null;
        try { return UUID.fromString(value.asText()); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    // Envelope has no aggregateType. Map catalogue contracts; do not confuse
    // refundId or payment instructionId with the envelope's order aggregate.
    static String aggregateType(String type) {
        if (type.equals("SystemConfigUpdated")) return "SystemConfig";
        if (type.equals("UserRated")) return "Rating";
        if (type.startsWith("CheckoutPayment") || type.equals("OrderGroupCancelled")) return "OrderGroup";
        if (type.startsWith("Reputation") || type.equals("TrustLevelChanged")) return "User";
        if (type.equals("BidPlaced")) return "Auction";
        for (String prefix : new String[]{"Category", "Product", "User", "Order", "Auction", "Inventory", "Shipment", "Cart", "Notification"}) {
            if (type.startsWith(prefix)) return prefix;
        }
        return null; // Unknown events are still retained, without invented metadata.
    }
}
