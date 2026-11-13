package com.group5.commerceservice.order;

import com.group5.common_events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PendingShipmentEvents {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final JavaType envelopeType;

    public PendingShipmentEvents(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.envelopeType = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, ShipmentStatusChanged.class);
    }

    public void park(EventEnvelope<ShipmentStatusChanged> event) {
        jdbc.update("""
                INSERT INTO pending_order_shipment_events (event_id, envelope) VALUES (?, ?::jsonb)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), mapper.writeValueAsString(event));
    }

    public List<UUID> pendingIds() {
        return jdbc.queryForList("""
                SELECT p.event_id FROM pending_order_shipment_events p
                JOIN orders o ON o.id = CAST(p.envelope->'payload'->>'orderId' AS uuid)
                WHERE o.status <> 'PENDING'
                ORDER BY p.created_at, p.event_id LIMIT 100
                """, UUID.class);
    }

    public Optional<EventEnvelope<ShipmentStatusChanged>> lock(UUID eventId) {
        return jdbc.query("""
                SELECT envelope::text FROM pending_order_shipment_events WHERE event_id = ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> mapper.<EventEnvelope<ShipmentStatusChanged>>readValue(rs.getString(1), envelopeType),
                eventId).stream().findFirst();
    }

    public void remove(UUID eventId) {
        jdbc.update("DELETE FROM pending_order_shipment_events WHERE event_id = ?", eventId);
    }
}
