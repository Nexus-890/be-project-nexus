package com.group5.notificationservice.notification;

/**
 * Sends a stored in-app notification through one more channel. R1 has none. A channel such as email,
 * enabled by nexus.notification.email-enabled, is added as a new bean of this type; DomainEventConsumer
 * and NotificationTemplate do not change.
 */
public interface NotificationChannel {

    void send(Notification notification);
}
