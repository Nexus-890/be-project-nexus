package com.group5.inventoryservice.reservation;

import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class ReservationExpiryJob {

    private static final UUID SYSTEM_ACTOR = new UUID(0, 0);

    private static final String EXPIRE = """
            WITH expired AS (
                UPDATE inventory_reservations
                SET status = 'EXPIRED', released_at = now(), updated_by = ?
                WHERE id IN (
                    SELECT id FROM inventory_reservations
                    WHERE status = 'HELD' AND auction_id IS NULL AND expires_at < now() - make_interval(secs => ?)
                    ORDER BY expires_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, order_id, sku_id, warehouse_id, quantity, expires_at, released_at
            ), returned AS (
                UPDATE inventory_balances b
                SET reserved_quantity  = b.reserved_quantity - e.quantity,
                    available_quantity = b.available_quantity + e.quantity,
                    updated_by         = ?,
                    updated_at         = now(),
                    version            = b.version + 1
                FROM (SELECT sku_id, warehouse_id, sum(quantity) AS quantity
                      FROM expired GROUP BY sku_id, warehouse_id) e
                WHERE b.sku_id = e.sku_id AND b.warehouse_id = e.warehouse_id
            ), moved AS (
                INSERT INTO inventory_movements
                    (sku_id, warehouse_id, movement_type, quantity, reference_id, created_by, updated_by)
                SELECT sku_id, warehouse_id, CAST('RELEASE' AS inventory_movement_type), quantity, id, ?, ?
                FROM expired
            )
            SELECT id, order_id, sku_id, warehouse_id, quantity, expires_at, released_at FROM expired
            """;

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final TransactionTemplate transaction;
    private final Tracer tracer;

    @Value("${nexus.inventory.reservation-expiry-batch-size:500}")
    private int batchSize = 500;

    @Value("${nexus.inventory.reservation-expiry-grace:10m}")
    private Duration grace = Duration.ofMinutes(10);

    public ReservationExpiryJob(JdbcTemplate jdbc, OutboxWriter outbox, EventEnvelopeFactory envelopes,
                                PlatformTransactionManager transactions, Tracer tracer) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.envelopes = envelopes;
        this.transaction = new TransactionTemplate(transactions);
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${nexus.inventory.reservation-expiry-interval:30s}",
            initialDelayString = "${nexus.inventory.reservation-expiry-interval:30s}")
    @SchedulerLock(name = "reservationExpiryJob", lockAtMostFor = "PT5M")
    public void run() {
        runOnce();
    }

    public int runOnce() {
        Span span = tracer.nextSpan().name("inventory-reservation-expiry").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Integer expired = transaction.execute(status -> {
                List<InventoryReservationExpired> rows = jdbc.query(EXPIRE, ReservationExpiryJob::expired,
                        SYSTEM_ACTOR, (double) grace.toSeconds(), batchSize, SYSTEM_ACTOR, SYSTEM_ACTOR,
                        SYSTEM_ACTOR);
                rows.forEach(row -> outbox.write("InventoryReservation", Topics.INVENTORY,
                        envelopes.create("InventoryReservationExpired", row.reservationId().toString(), row)));
                return rows.size();
            });
            if (expired != null && expired > 0) {
                log.info("Reservation expiry job completed: expired={}", expired);
            }
            return expired == null ? 0 : expired;
        } finally {
            span.end();
        }
    }

    private static InventoryReservationExpired expired(ResultSet row, int index) throws SQLException {
        return new InventoryReservationExpired(row.getObject("id", UUID.class), row.getObject("order_id", UUID.class),
                row.getObject("sku_id", UUID.class), row.getObject("warehouse_id", UUID.class), row.getInt("quantity"),
                instant(row, "expires_at"), instant(row, "released_at"));
    }

    private static String instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant().toString();
    }

    public record InventoryReservationExpired(UUID reservationId, UUID orderId, UUID skuId, UUID warehouseId,
                                              int quantity, String expiresAt, String releasedAt) {
    }
}
