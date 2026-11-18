package com.group5.paymentservice.payout.evidence;

import com.group5.common_core.constant.ErrorCode;

import org.springframework.http.HttpStatus;

public enum PayoutEvidenceErrorCode implements ErrorCode {
    EVIDENCE_INVALID(HttpStatus.BAD_REQUEST, "A non-empty payout evidence file is required"),
    EVIDENCE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payout evidence must not exceed 10 MiB"),
    EVIDENCE_UNSUPPORTED(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG and PDF evidence is supported"),
    EVIDENCE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Private payout evidence storage is unavailable");

    private final HttpStatus status;
    private final String message;

    PayoutEvidenceErrorCode(HttpStatus status, String message) {
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
