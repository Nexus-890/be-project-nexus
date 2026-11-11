package com.group5.commerceservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CommerceErrorCode implements ErrorCode {
    GUEST_CART_DISABLED(HttpStatus.FORBIDDEN, "Guest carts are disabled"),
    CART_MERGE_DISABLED(HttpStatus.FORBIDDEN, "Guest cart merge is disabled"),
    GUEST_CART_NOT_FOUND(HttpStatus.NOT_FOUND, "Guest cart not found or no longer active"),
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart or cart item not found"),
    INVALID_GUEST_CONFIGURATION(HttpStatus.INTERNAL_SERVER_ERROR, "Guest cart token signing is not configured correctly"),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "Quantity is invalid"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "SKU not found"),
    DB_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Database error"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated"),
    INVALID_CATALOG_RESPONSE(HttpStatus.BAD_GATEWAY, "Catalog returned an invalid SKU response"),
    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Catalog is unavailable"),
    INVALID_CONFIGURATION(HttpStatus.INTERNAL_SERVER_ERROR, "Expiration-days must be greater or equal to 1"),
    INVALID_ORDER_REQUEST(HttpStatus.BAD_REQUEST, "The order request is invalid"),
    INVALID_ORDER_FILTER(HttpStatus.BAD_REQUEST, "The order list filters are invalid"),
    CART_EMPTY(HttpStatus.BAD_REQUEST, "Cart is empty"),
    INVALID_PRICING_CONFIGURATION(HttpStatus.INTERNAL_SERVER_ERROR, "Checkout price revalidation must be enabled"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "The order status transition is not allowed"),
    IDEMPOTENCY_KEY_NOT_OWNED(HttpStatus.BAD_REQUEST, "Idempotency-Key cannot be used for this request"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used for another request"),
    PRICE_CHANGED(HttpStatus.CONFLICT, "An item price has changed; review the new price before retrying checkout"),
    PROMOTION_NOT_SUPPORTED(HttpStatus.CONFLICT, "Remove the promotion code before placing this order"),
    ITEM_UNAVAILABLE(HttpStatus.CONFLICT, "An item is unavailable"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "There is insufficient stock for a cart item"),
    INVENTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Inventory is unavailable; retry checkout later"),
    SHIPPING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Shipping quotes are unavailable; retry checkout later"),
    SHIPPING_METADATA_REQUIRED(HttpStatus.CONFLICT, "The seller must supply shipping measurements for this SKU"),
    INVALID_PARCEL(HttpStatus.CONFLICT, "The combined seller parcel exceeds shipping limits"),
    INVALID_SHIPPING_ADDRESS(HttpStatus.BAD_REQUEST, "The shipping address is invalid"),
    SHIPPING_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "No shipping service is available for this order"),
    INVALID_ORDER_AMOUNT(HttpStatus.CONFLICT, "An item price or order total cannot be represented as numeric(19,2)"),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "The order can no longer be cancelled"),
    RESERVED_CANCEL_REASON(HttpStatus.BAD_REQUEST, "This cancellation reason is reserved for payment expiry");

    private final HttpStatus status;
    private final String message;
    CommerceErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    @Override public String code() { return "COMMERCE_" + name(); }
    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
