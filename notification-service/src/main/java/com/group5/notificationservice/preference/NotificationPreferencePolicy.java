package com.group5.notificationservice.preference;

import com.group5.notificationservice.notification.NotificationTemplate;
import java.util.Set;

/** Business messages are mandatory. Only outbid alerts can currently be disabled. */
public final class NotificationPreferencePolicy {
    public static final Set<String> CHANNELS = Set.of("EMAIL", "IN_APP");
    private NotificationPreferencePolicy() {}

    public static boolean mandatory(String type) {
        // Unknown historical transactional types remain deliverable as before.
        return !"BID_OUTBID".equals(type);
    }

    public static boolean supported(String type) {
        if (type == null) return false;
        try { NotificationTemplate.valueOf(type); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
