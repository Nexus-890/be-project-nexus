package com.group5.shipmentservice.shipping.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShipmentStatusReceiptRepository {
    private final JdbcTemplate jdbc;

    public ShipmentStatusReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claimWebhook(String carrier, String deliveryId, UUID shipmentId) {
        return jdbc.update("""
                INSERT INTO carrier_webhook_receipts(carrier, delivery_id, shipment_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, carrier, deliveryId, shipmentId) == 1;
    }

    public void recordCancellation(UUID orderId, UUID eventId, String reason) {
        jdbc.update("""
                INSERT INTO cancelled_shipment_orders(order_id, event_id, reason)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, orderId, eventId, reason);
    }
}
