package com.group5.notificationservice.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    // KHÓA CÁC DELIVERY ĐẾN HẠN BẰNG FOR UPDATE SKIP LOCKED
    // -> CHẠY NHIỀU INSTANCE CŨNG KHÔNG CÓ 2 WORKER GỬI CÙNG 1 EMAIL, KHÔNG CẦN SHEDLOCK
    public List<DueDelivery> lockDueEmails(Instant now, int limit) {
        return jdbc.query("""
                SELECT d.id, d.notification_id, d.attempt_count, n.user_id, n.title, n.content
                FROM notification_deliveries d
                JOIN notifications n ON n.id = d.notification_id
                WHERE d.status = 'PENDING' AND d.channel = 'EMAIL' AND d.next_attempt_at <= ?
                ORDER BY d.next_attempt_at
                LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
                """, (rs, i) -> new DueDelivery(rs.getObject("id", UUID.class),
                rs.getObject("notification_id", UUID.class), rs.getInt("attempt_count"),
                rs.getObject("user_id", UUID.class), rs.getString("title"), rs.getString("content")),
                Timestamp.from(now), limit);
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

    public record DueDelivery(UUID id, UUID notificationId, int attemptCount, UUID userId, String title,
                              String content) {
    }

    public record TimedOutAttempt(UUID id, int attemptNumber) {
    }
}
