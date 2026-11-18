package com.group5.paymentservice.payout.qr;

import com.group5.common_core.constant.ErrorCode;

import org.springframework.http.HttpStatus;

public enum ManualPayoutQrErrorCode implements ErrorCode {
    INVALID_QR_DATA(
            HttpStatus.CONFLICT,
            "The frozen payout cannot be represented as a supported VietQR transfer"),
    QR_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "The payout QR image is temporarily unavailable");

    private final HttpStatus status;
    private final String message;

    ManualPayoutQrErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return "PAYMENT_MANUAL_PAYOUT_" + name();
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
