package com.group5.inventoryservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum InventoryErrorCode implements ErrorCode {
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Warehouse not found"),
    DEFAULT_WAREHOUSE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR,
            "The configured default warehouse does not exist"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Not enough stock; nothing was reserved"),
    DUPLICATE_RESERVATION_ITEM(HttpStatus.BAD_REQUEST, "Each order and SKU pair may appear only once"),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT,
            "These orders already hold reservations that differ from this request"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Reservation not found"),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "The reservation is not HELD");

    private final HttpStatus status;
    private final String message;

    InventoryErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }

    @Override public String code() { return "INVENTORY_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
