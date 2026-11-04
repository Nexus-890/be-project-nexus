package com.group5.paymentservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PaymentErrorCode implements ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid buyer identity is required"),
    CHECKOUT_PRIVILEGE_REQUIRED(HttpStatus.FORBIDDEN, "CHECKOUT.START is required"),
    ORDER_GROUP_ID_REQUIRED(HttpStatus.BAD_REQUEST, "orderGroupId is required"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment session is not available"),
    SESSION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "This payment session belongs to another buyer"),
    SESSION_NOT_PAYABLE(HttpStatus.CONFLICT, "Payment session is no longer payable"),
    INVALID_INSTRUCTIONS(HttpStatus.CONFLICT, "Payment instructions are incomplete or inconsistent"),
    INVALID_VIETQR_DATA(HttpStatus.CONFLICT, "Stored payment data cannot be represented as VietQR"),
    INVALID_REFUND_REQUEST(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key header is required"),
    REFUND_AMOUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "Refund amount exceeds the amount paid for this order"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "No payment exists for this order"),
    ORDER_NOT_REFUNDABLE(HttpStatus.CONFLICT, "Only a cancelled, paid order can be refunded"),
    REFUND_EXISTS(HttpStatus.CONFLICT, "This order already has a refund"),
    REFUND_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used for another refund request"),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "Refund is not available"),
    REFUND_CONFLICT(HttpStatus.CONFLICT, "Refund was changed by another request, try again");

    private final HttpStatus status;
    private final String message;

    PaymentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return "PAYMENT_" + name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
