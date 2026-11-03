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
    INVALID_VIETQR_DATA(HttpStatus.CONFLICT, "Stored payment data cannot be represented as VietQR");

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
