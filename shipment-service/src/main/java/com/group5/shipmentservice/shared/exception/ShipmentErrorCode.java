package com.group5.shipmentservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ShipmentErrorCode implements ErrorCode {
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Invalid webhook signature"),
    INVALID_WEBHOOK(HttpStatus.BAD_REQUEST, "Invalid webhook payload"),
    CARRIER_CANCELLATION_REJECTED(HttpStatus.CONFLICT, "The carrier rejected the shipment cancellation"),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "The shipment cannot move to the requested status"),
    UNSUPPORTED_CARRIER(HttpStatus.BAD_REQUEST, "Unsupported carrier"),
    INVALID_CARRIER_STATUS(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported carrier status"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key is required"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "This key or shipment request was already used with a different body"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found for shipment creation"),
    ORDER_NOT_PAID(HttpStatus.CONFLICT, "The order is not paid or is no longer PAID"),
    INVALID_ORDER_SNAPSHOT(HttpStatus.BAD_GATEWAY, "The order's shipping snapshot is invalid"),
    COMMERCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The order service is unavailable"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid shipping quote request"),
    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "The reporting period needs from before to and can span at most 366 days"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "Invalid request body"),
    INVALID_ADDRESS(HttpStatus.BAD_REQUEST, "The locality code does not exist or does not belong to its parent"),
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "The warehouse has no pickup address configured"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "No SKU on sale was found"),
    NO_SERVICE(HttpStatus.UNPROCESSABLE_ENTITY, "No shipping service is available for this route"),
    CARRIER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The carrier did not respond in time"),
    CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "The carrier connection is paused after repeated failures"),
    CARRIER_CONFIGURATION_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "The carrier configuration is incomplete or invalid"),
    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The product service is unavailable"),
    INVALID_CATALOG_RESPONSE(HttpStatus.BAD_GATEWAY, "Invalid product price data"),
    INVALID_CARRIER_RESPONSE(HttpStatus.BAD_GATEWAY, "Invalid carrier response"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid request parameter"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid access token is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Response format not supported"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request content type not supported"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required service is unavailable"),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The service did not respond in time"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The shipment service failed to process the request");

    private final HttpStatus status;
    private final String message;

    ShipmentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return "SHIPMENT_" + name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }

    public static ShipmentErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_PARAMETER;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> NO_SERVICE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            default -> INTERNAL_ERROR;
        };
    }
}
