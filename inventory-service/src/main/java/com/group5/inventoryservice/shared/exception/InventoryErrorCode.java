package com.group5.inventoryservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum InventoryErrorCode implements ErrorCode {
    DUPLICATE_WAREHOUSE_CODE(HttpStatus.CONFLICT, "Warehouse code already exists"),
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Warehouse not found"),
    WAREHOUSE_UNAVAILABLE(HttpStatus.CONFLICT, "Warehouse is not active for stock intake"),
    DEFAULT_WAREHOUSE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR,
            "The configured default warehouse does not exist");

    private final HttpStatus status;
    private final String message;

    InventoryErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }

    @Override public String code() { return "INVENTORY_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
