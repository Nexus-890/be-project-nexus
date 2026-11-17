package com.group5.notificationservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable error identifiers owned by the notification service. */
public enum NotificationErrorCode implements ErrorCode {
    DATA_INVALID(HttpStatus.BAD_REQUEST, "Invalid data"),
    AUDIT_SIGNATURE_INVALID(HttpStatus.FORBIDDEN, "Invalid internal audit signature"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Notification not found"),
    EVENT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "Event log not found"),
    NOT_OWNER(HttpStatus.FORBIDDEN, "Notification belongs to another user"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated!");

    private final HttpStatus status;
    private final String message;

    NotificationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public String code() { return "NOTIFICATION_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
