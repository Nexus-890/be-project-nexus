package com.group5.paymentservice.payment.event.payload;

import java.util.UUID;

public record OrderRefundCompleted(
        UUID refundId,
        UUID orderId,
        UUID orderGroupId,
        UUID sessionId,
        UUID instructionId,
        UUID incomingTransactionId,
        String amount,
        String currency,
        String status,
        String reason,
        UUID actorId,
        long refundVersion,
        long instructionVersion,
        String requestedAt,
        UUID completedTransactionId,
        String completedAt,
        // Bổ sung người nhận email, giữ nguyên dữ liệu hoàn tiền hiện có.
        UUID buyerId) {
}
