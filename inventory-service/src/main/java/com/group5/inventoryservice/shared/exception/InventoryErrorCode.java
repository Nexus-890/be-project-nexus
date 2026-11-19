package com.group5.inventoryservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum InventoryErrorCode implements ErrorCode {
    DUPLICATE_WAREHOUSE_CODE(HttpStatus.CONFLICT, "Warehouse code already exists"),
    WAREHOUSE_UNAVAILABLE(HttpStatus.CONFLICT, "Warehouse is not active for stock intake"),
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Warehouse not found"),
    WAREHOUSE_NOT_SKU_HOME(HttpStatus.CONFLICT, "This SKU is stocked in another warehouse"),
    TOO_MANY_SKUS(HttpStatus.BAD_REQUEST, "At most 100 SKU ids can be read at once"),
    DEFAULT_WAREHOUSE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR,
            "The configured default warehouse does not exist"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Not enough stock; nothing was reserved"),
    DUPLICATE_RESERVATION_ITEM(HttpStatus.BAD_REQUEST, "Each order and SKU pair may appear only once"),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT,
            "These orders already hold reservations that differ from this request"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Reservation not found"),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "The reservation is not HELD"),
    NOT_PICKABLE(HttpStatus.CONFLICT, "Only a paid (CONFIRMED) reservation can be picked"),
    NOT_PICKED(HttpStatus.CONFLICT, "Pick the reservation before packing it for shipment"),
    BALANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "SKU balance not found"),
    NEGATIVE_QUANTITY(HttpStatus.CONFLICT, "The adjustment would make a quantity negative"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "The caller does not own this SKU"),
    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The catalog could not confirm who owns this SKU");

    private final HttpStatus status;
    private final String message;

    InventoryErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }

    @Override public String code() { return "INVENTORY_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
