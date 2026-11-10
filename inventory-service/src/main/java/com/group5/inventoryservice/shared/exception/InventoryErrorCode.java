package com.group5.inventoryservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum InventoryErrorCode implements ErrorCode {
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Warehouse not found"),
    TOO_MANY_SKUS(HttpStatus.BAD_REQUEST, "At most 100 SKU ids can be read at once"),
    DEFAULT_WAREHOUSE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR,
            "The configured default warehouse does not exist");

    private final HttpStatus status;
    private final String message;

    InventoryErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }

    @Override public String code() { return "INVENTORY_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
