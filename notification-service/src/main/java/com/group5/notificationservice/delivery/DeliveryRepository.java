package com.group5.notificationservice.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DeliveryRepository {

    private final JdbcTemplate jdbc;

    public DeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPendingEmail(UUID notificationId, Instant dueAt) {
        jdbc.update("""
                INSERT INTO notification_deliveries (notification_id, channel, status, next_attempt_at)
                VALUES (?, 'EMAIL', 'PENDING', ?)
                ON CONFLICT (notification_id, channel) DO NOTHING
                """, notificationId, Timestamp.from(dueAt));
    }

    // KHÓA DELIVERY ĐẾN HẠN BẰNG FOR UPDATE SKIP LOCKED -> 2 WORKER KHÔNG NHẬN CÙNG 1 DELIVERY CÙNG LÚC
    // BỎ QUA CÁC DELIVERY LƯỢT NÀY ĐÃ NHẬN (excluded) -> 1 LƯỢT KHÔNG GỬI LẠI NGAY DELIVERY VỪA LỖI
    public List<DueDelivery> lockDueEmails(Instant now, int limit, Collection<UUID> excluded) {
        return jdbc.query("""
                SELECT d.id, d.notification_id, d.attempt_count, n.user_id, n.title, n.content
                FROM notification_deliveries d
                JOIN notifications n ON n.id = d.notification_id
                WHERE d.status = 'PENDING' AND d.channel = 'EMAIL' AND d.next_attempt_at <= ?
                  AND NOT (d.id = ANY (CAST(? AS uuid[])))
                ORDER BY d.next_attempt_at
                LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
                """, (rs, i) -> new DueDelivery(rs.getObject("id", UUID.class),
                rs.getObject("notification_id", UUID.class), rs.getInt("attempt_count"),
                rs.getObject("user_id", UUID.class), rs.getString("title"), rs.getString("content")),
                Timestamp.from(now), uuidArray(excluded), limit);
    }

    private static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    public void markExhausted(UUID deliveryId, Instant now) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'FAILED', next_attempt_at = NULL, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), deliveryId);
    }

    // next_attempt_at = HẠN CHỜ KẾT QUẢ: WORKER CHẾT GIỮA CHỪNG THÌ HẾT HẠN SẼ CÓ WORKER KHÁC NHẬN LẠI
    public UUID startAttempt(UUID deliveryId, int attemptNumber, Instant leaseUntil, Instant now) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET attempt_count = ?, next_attempt_at = ?, updated_at = ?
                WHERE id = ?
                """, attemptNumber, Timestamp.from(leaseUntil), Timestamp.from(now), deliveryId);
        return jdbc.queryForObject("""
                INSERT INTO notification_delivery_attempts (delivery_id, attempt_number, status, attempted_at)
                VALUES (?, ?, 'STARTED', ?)
                RETURNING id
                """, UUID.class, deliveryId, attemptNumber, Timestamp.from(now));
    }

    public boolean ownsAttempt(UUID deliveryId, UUID attemptId, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM notification_delivery_attempts a
                    JOIN notification_deliveries d ON d.id = a.delivery_id
                    WHERE a.id = ? AND d.id = ? AND a.status = 'STARTED'
                      AND d.status = 'PENDING' AND d.next_attempt_at > ?
                )
                """, Boolean.class, attemptId, deliveryId, Timestamp.from(now)));
    }

    public List<TimedOutAttempt> closeStartedAttempts(UUID deliveryId, Instant now) {
        return jdbc.query("""
                UPDATE notification_delivery_attempts
                SET status = 'FAILED', completed_at = ?, error_code = 'ATTEMPT_TIMED_OUT', retryable = true
                WHERE delivery_id = ? AND status = 'STARTED'
                RETURNING id, attempt_number
                """, (rs, i) -> new TimedOutAttempt(rs.getObject("id", UUID.class), rs.getInt("attempt_number")),
                Timestamp.from(now), deliveryId);
    }

    public boolean completeAttempt(UUID attemptId, String providerMessageId, Instant now) {
        return jdbc.update("""
                UPDATE notification_delivery_attempts
                SET status = 'SUCCEEDED', completed_at = ?, provider_message_id = ?
                WHERE id = ? AND status = 'STARTED'
                """, Timestamp.from(now), providerMessageId, attemptId) > 0;
    }

    public boolean failAttempt(UUID attemptId, String errorCode, String errorMessage, boolean retryable,
                               Instant now) {
        return jdbc.update("""
                UPDATE notification_delivery_attempts
                SET status = 'FAILED', completed_at = ?, error_code = ?, error_message = ?, retryable = ?
                WHERE id = ? AND status = 'STARTED'
                """, Timestamp.from(now), errorCode, errorMessage, retryable, attemptId) > 0;
    }

    public void markSent(UUID deliveryId, Instant now) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'SENT', next_attempt_at = NULL, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), deliveryId);
    }

    public void reschedule(UUID deliveryId, String status, Instant nextAttemptAt, Instant now) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = CAST(? AS notification_delivery_status), next_attempt_at = ?, updated_at = ?
                WHERE id = ?
                """, status, nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), Timestamp.from(now),
                deliveryId);
    }

    public List<DeliveryRow> findByNotification(UUID notificationId) {
        return jdbc.query("""
                SELECT id, channel::text AS channel, status::text AS status, attempt_count, next_attempt_at,
                       created_at, updated_at
                FROM notification_deliveries
                WHERE notification_id = ?
                ORDER BY created_at, id
                """, (rs, i) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getString("channel"),
                rs.getString("status"), rs.getInt("attempt_count"),
                rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), notificationId);
    }

    public List<AttemptRow> findAttemptsByNotification(UUID notificationId) {
        return jdbc.query("""
                SELECT a.id, a.delivery_id, a.attempt_number, a.status::text AS status, a.attempted_at,
                       a.completed_at, a.error_code, a.retryable
                FROM notification_delivery_attempts a
                JOIN notification_deliveries d ON d.id = a.delivery_id
                WHERE d.notification_id = ?
                ORDER BY a.attempt_number
                """, (rs, i) -> new AttemptRow(rs.getObject("id", UUID.class),
                rs.getObject("delivery_id", UUID.class), rs.getInt("attempt_number"), rs.getString("status"),
                rs.getObject("attempted_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("error_code"), rs.getBoolean("retryable")), notificationId);
    }

    public record DeliveryRow(UUID id, String channel, String status, int attemptCount,
                              OffsetDateTime nextAttemptAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record AttemptRow(UUID id, UUID deliveryId, int attemptNumber, String status, OffsetDateTime attemptedAt,
                             OffsetDateTime completedAt, String errorCode, boolean retryable) {
    }

    public record DueDelivery(UUID id, UUID notificationId, int attemptCount, UUID userId, String title,
                              String content) {
    }

    public record TimedOutAttempt(UUID id, int attemptNumber) {
    }
}
