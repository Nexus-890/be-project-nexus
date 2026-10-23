package com.group5.commerceservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CommerceErrorCode implements ErrorCode {
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "Quantity is invalid"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "SKU not found"),
    DB_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Database error"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated"),
    INVALID_CATALOG_RESPONSE(HttpStatus.BAD_GATEWAY, "Catalog returned an invalid SKU response"),
    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Catalog is unavailable");

    private final HttpStatus status;
    private final String message;
    CommerceErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    @Override public String code() { return "COMMERCE_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
