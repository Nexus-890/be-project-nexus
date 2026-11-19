package com.group5.notificationservice.preference;

import com.group5.common_core.exception.DomainException;
import com.group5.notificationservice.notification.NotificationTemplate;
import com.group5.notificationservice.preference.dto.NotificationPreferences;
import com.group5.notificationservice.preference.dto.UpdateNotificationPreferences;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class NotificationPreferenceService {
    private final JdbcTemplate jdbc;
    public NotificationPreferenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void initialize(UUID userId) {
        for (NotificationTemplate type : NotificationTemplate.values()) {
            for (String channel : List.of("IN_APP", "EMAIL")) {
                jdbc.update("""
                        INSERT INTO notification_preferences (user_id, notification_type, channel, enabled)
                        VALUES (?, ?, CAST(? AS notification_channel), true)
                        ON CONFLICT (user_id, notification_type, channel) DO NOTHING
                        """, userId, type.name(), channel);
            }
        }
    }

    @Transactional
    public NotificationPreferences get(UUID userId) {
        initialize(userId);
        return new NotificationPreferences(jdbc.query("""
                SELECT notification_type, channel::text AS channel, enabled
                FROM notification_preferences WHERE user_id = ? AND channel::text IN ('EMAIL', 'IN_APP')
                ORDER BY notification_type, channel
                """, (rs, row) -> {
                    String type = rs.getString("notification_type");
                    boolean mandatory = NotificationPreferencePolicy.mandatory(type);
                    return new NotificationPreferences.Preference(type, rs.getString("channel"),
                            mandatory || rs.getBoolean("enabled"), mandatory);
                }, userId).stream().filter(p -> NotificationPreferencePolicy.supported(p.notificationType())).toList());
    }

    @Transactional
    public NotificationPreferences update(UUID userId, UpdateNotificationPreferences request) {
        Set<String> seen = new HashSet<>();
        for (var change : request.preferences()) {
            if (!NotificationPreferencePolicy.supported(change.notificationType())
                    || !NotificationPreferencePolicy.CHANNELS.contains(change.channel())
                    || !seen.add(change.notificationType() + ":" + change.channel())
                    || NotificationPreferencePolicy.mandatory(change.notificationType()) && !change.enabled()) {
                throw new DomainException(NotificationErrorCode.DATA_INVALID);
            }
        }
        initialize(userId);
        for (var change : request.preferences()) {
            jdbc.update("""
                    UPDATE notification_preferences SET enabled = ?, updated_at = now(), updated_by = ?
                    WHERE user_id = ? AND notification_type = ? AND channel = CAST(? AS notification_channel)
                    """, change.enabled(), userId, userId, change.notificationType(), change.channel());
        }
        return get(userId);
    }

    @Transactional(readOnly = true)
    public boolean enabled(UUID userId, String type, String channel) {
        if (NotificationPreferencePolicy.mandatory(type)) return true;
        List<Boolean> results = jdbc.query("""
                SELECT enabled FROM notification_preferences
                WHERE user_id = ? AND notification_type = ? AND channel = CAST(? AS notification_channel)
                """, (rs, row) -> rs.getBoolean(1), userId, type, channel);
        return results.isEmpty() || results.getFirst();
    }
}
