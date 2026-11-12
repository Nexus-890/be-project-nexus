package com.group5.notificationservice.notification;

import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public enum NotificationTemplate {

    ORDER_CREATED("OrderCreated", "buyerId", "Order placed", "Your order %s was placed", "orderNumber"),
    ORDER_COMPLETED("OrderCompleted", "buyerId", "Order receipt", "Your order %s is complete. Your receipt is ready.", "orderNumber");

    private final String eventType;
    private final String recipientField;
    private final String title;
    private final String contentFormat;
    private final String contentField;

    NotificationTemplate(String eventType, String recipientField, String title,
                         String contentFormat, String contentField) {
        this.eventType = eventType;
        this.recipientField = recipientField;
        this.title = title;
        this.contentFormat = contentFormat;
        this.contentField = contentField;
    }

    public static Optional<NotificationTemplate> forEventType(String eventType) {
        return Arrays.stream(values())
                .filter(template -> template.eventType.equals(eventType))
                .findFirst();
    }

    public Notification render(JsonNode payload) {
        Notification notification = new Notification();
        notification.setUserId(UUID.fromString(requiredText(payload, recipientField)));
        notification.setType(name());
        notification.setTitle(title);
        notification.setContent(contentFormat.formatted(requiredText(payload, contentField)));
        if (this == ORDER_COMPLETED) {
            UUID.fromString(requiredText(payload, "orderId"));
            // Commerce renders and escapes the immutable receipt snapshot before publishing it.
            notification.setEmailHtml(requiredText(payload, "receiptHtml"));
        }
        return notification;
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(eventType + " payload has no " + field);
        }
        return value.asString();
    }
}
