package com.group5.notificationservice.stream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class NotificationInboxQuery {
    private final JdbcTemplate jdbc;
    public NotificationInboxQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Snapshot snapshot(UUID userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT revision FROM notification_inbox_versions WHERE user_id = ?), 0)::text AS revision,
                    (SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL AND in_app_enabled) AS unread
                """, (rs, row) -> new Snapshot(rs.getLong("unread"), rs.getString("revision")), userId, userId);
    }
    public record Snapshot(long unreadCount, String revision) {}
}
