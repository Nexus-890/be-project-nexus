package com.group5.paymentservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;

import org.springframework.http.HttpStatus;

public enum PaymentErrorCode implements ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid buyer identity is required"),
    INVALID_WEBHOOK_REQUEST(
            HttpStatus.BAD_REQUEST, "The webhook body must be a valid SePay JSON object"),
    CHECKOUT_PRIVILEGE_REQUIRED(HttpStatus.FORBIDDEN, "CHECKOUT.START is required"),
    REFUND_PRIVILEGE_REQUIRED(HttpStatus.FORBIDDEN, "ORDER.REFUND is required to list payments"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "These payments belong to another buyer"),
    ORDER_GROUP_ID_REQUIRED(HttpStatus.BAD_REQUEST, "orderGroupId is required"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment session is not available"),
    SESSION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "This payment session belongs to another buyer"),
    SESSION_NOT_PAYABLE(HttpStatus.CONFLICT, "Payment session is no longer payable"),
    INVALID_INSTRUCTIONS(
            HttpStatus.CONFLICT, "Payment instructions are incomplete or inconsistent"),
    INVALID_VIETQR_DATA(HttpStatus.CONFLICT, "Stored payment data cannot be represented as VietQR"),
    INVALID_REFUND_REQUEST(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key header is required"),
    REFUND_AMOUNT_EXCEEDED(
            HttpStatus.BAD_REQUEST, "Refund amount exceeds the amount paid for this order"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "No payment exists for this order"),
    ORDER_NOT_REFUNDABLE(HttpStatus.CONFLICT, "Only a cancelled, paid order can be refunded"),
    REFUND_EXISTS(HttpStatus.CONFLICT, "This order already has a refund"),
    REFUND_IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT, "Idempotency-Key was already used for another refund request"),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "Refund is not available"),
    REFUND_CONFLICT(HttpStatus.CONFLICT, "Refund was changed by another request, try again"),
    SELLER_BANK_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Seller bank account is not available"),
    SELLER_BANK_ACCOUNT_INVALID(HttpStatus.BAD_REQUEST, "Seller bank account details are invalid"),
    SELLER_BANK_ACCOUNT_CONFLICT(
            HttpStatus.CONFLICT,
            "Bank account changed since review; reload it before submitting a decision"),
    PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payout is not available"),
    PAYOUT_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Payout request is invalid"),
    PAYOUT_NOT_RETRYABLE(HttpStatus.CONFLICT, "Only failed or returned payouts can be retried"),
    PAYOUT_IDEMPOTENCY_INVALID(
            HttpStatus.BAD_REQUEST, "A valid unused Idempotency-Key is required"),
    PAYOUT_CALLBACK_SIGNATURE_INVALID(
            HttpStatus.UNAUTHORIZED, "Payout callback signature is invalid"),
    PAYOUT_CALLBACK_INVALID(HttpStatus.BAD_REQUEST, "Payout callback is invalid"),
    PAYOUT_PROVIDER_CONFLICT(
            HttpStatus.CONFLICT, "Provider reference conflicts with the stored transfer"),
    MANUAL_PAYOUT_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Manual payout request is invalid"),
    MANUAL_PAYOUT_FORBIDDEN(HttpStatus.FORBIDDEN, "This manual payout action is not allowed"),
    MANUAL_PAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "Manual payout resource is not available"),
    MANUAL_PAYOUT_CONFLICT(
            HttpStatus.CONFLICT,
            "Manual payout changed or requires reconciliation; reload the current state"),
    MANUAL_PAYOUT_IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT, "Idempotency-Key belongs to a different request"),
    MANUAL_PAYOUT_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Manual payout dependency is unavailable");

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
