package com.group5.paymentservice.payment.dto.response;

import com.group5.paymentservice.payment.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RefundView(
        UUID refundId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String reason,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        List<RefundEvidenceView> evidence) {
    public RefundView withEvidence(List<RefundEvidenceView> files) {
        return new RefundView(refundId, orderId, amount, currency, status, reason, requestedAt, completedAt,
                List.copyOf(files));
    }
}
