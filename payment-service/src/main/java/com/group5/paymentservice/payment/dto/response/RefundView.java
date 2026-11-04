package com.group5.paymentservice.payment.dto.response;

import com.group5.paymentservice.payment.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundView(
        UUID refundId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String reason,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt) {
}
