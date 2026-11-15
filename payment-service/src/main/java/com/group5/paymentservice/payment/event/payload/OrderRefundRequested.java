package com.group5.paymentservice.payment.event.payload;

import java.util.UUID;

public record OrderRefundRequested(
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
        // Người nhận lấy từ instruction đã lưu, không phải actor thao tác hoàn tiền.
        UUID buyerId) {
}
