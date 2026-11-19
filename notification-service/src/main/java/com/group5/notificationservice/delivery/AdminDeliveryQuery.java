package com.group5.notificationservice.delivery;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.notificationservice.delivery.dto.*;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class AdminDeliveryQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public AdminDeliveryQuery(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String FROM = """
            FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
            LEFT JOIN LATERAL (SELECT error_code FROM notification_delivery_attempts a
                WHERE a.delivery_id = d.id ORDER BY attempt_number DESC LIMIT 1) last_attempt ON true
            """;
    private static final String SELECT = """
            SELECT d.id, d.notification_id, n.user_id, n.type, n.title, d.channel::text AS channel,
                d.status::text AS status, d.attempt_count, d.next_attempt_at, d.created_at, d.updated_at,
                last_attempt.error_code AS last_error_code
            """;
    private static final RowMapper<NotificationDeliverySummary> ROW = (rs, row) -> new NotificationDeliverySummary(
            rs.getObject("id", UUID.class), rs.getObject("notification_id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getString("type"), rs.getString("title"), rs.getString("channel"), rs.getString("status"),
            rs.getInt("attempt_count"), rs.getObject("next_attempt_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getString("last_error_code"));

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ApiResponse<List<NotificationDeliverySummary>> list(int page, int size, String status, String channel,
            OffsetDateTime from, OffsetDateTime to, String errorCode) {
        if (page < 0 || size < 1 || size > 100
                || status != null && !Set.of("PENDING", "SENT", "FAILED", "SKIPPED").contains(status)
                || channel != null && !Set.of("EMAIL", "PUSH", "SMS").contains(channel)
                || from != null && to != null && !from.isBefore(to)
                || errorCode != null && !errorCode.matches("[A-Z0-9_]{1,60}"))
            throw new DomainException(NotificationErrorCode.DATA_INVALID);
        var args = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE true");
        if (status != null) { where.append(" AND d.status::text = :status"); args.addValue("status", status); }
        if (channel != null) { where.append(" AND d.channel::text = :channel"); args.addValue("channel", channel); }
        if (from != null) { where.append(" AND d.created_at >= :from"); args.addValue("from", from); }
        if (to != null) { where.append(" AND d.created_at < :to"); args.addValue("to", to); }
        if (errorCode != null) { where.append(" AND EXISTS (SELECT 1 FROM notification_delivery_attempts e WHERE e.delivery_id = d.id AND e.error_code = :errorCode)"); args.addValue("errorCode", errorCode); }
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) " + FROM + where, args, Long.class));
        args.addValue("limit", size).addValue("offset", (long) page * size);
        var rows = jdbc.query(SELECT + FROM + where + " ORDER BY d.created_at DESC, d.id DESC LIMIT :limit OFFSET :offset", args, ROW);
        return ApiResponse.page(rows, new PageInfo(page, size, total, (int) Math.ceil((double) total / size)));
    }

    @Transactional(readOnly = true)
    public NotificationDeliveryDetail detail(UUID id) {
        var args = new MapSqlParameterSource("id", id);
        var rows = jdbc.query(SELECT + FROM + " WHERE d.id = :id", args, ROW);
        if (rows.isEmpty()) throw new DomainException(NotificationErrorCode.NOT_FOUND);
        var attempts = jdbc.query("""
                SELECT id, attempt_number, status::text AS status, attempted_at, completed_at, error_code, retryable
                FROM notification_delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number
                """, args, (rs, row) -> new DeliveryResponse.Attempt(rs.getObject("id", UUID.class),
                rs.getInt("attempt_number"), rs.getString("status"), rs.getObject("attempted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getString("error_code"), rs.getBoolean("retryable")));
        return new NotificationDeliveryDetail(rows.getFirst(), attempts);
    }
}
