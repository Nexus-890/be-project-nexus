package com.group5.paymentservice.payment.dto.response;

import com.group5.paymentservice.payment.enums.RefundStatus;

import java.util.UUID;

public record RefundResult(UUID refundId, RefundStatus status) {
}
