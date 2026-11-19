package com.group5.notificationservice.preference.dto;

import java.util.List;

public record NotificationPreferences(List<Preference> preferences) {
    public record Preference(String notificationType, String channel, boolean enabled, boolean mandatory) {}
}
