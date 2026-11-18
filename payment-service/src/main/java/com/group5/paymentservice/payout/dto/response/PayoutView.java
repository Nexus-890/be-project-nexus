package com.group5.paymentservice.payout.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Chỉ trả các trường được phép, không đưa số tài khoản đầy đủ hoặc ciphertext vào API. */
public record PayoutView(UUID id, UUID orderId, UUID sellerId,
        BigDecimal orderAmount, BigDecimal shippingFee, BigDecimal platformFeeRate, BigDecimal platformFee,
        BigDecimal netAmount, String currency, String status, int attemptCount, String recipientAccountLast4,
        String providerReference, boolean simulated, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<History> history) {
    public record History(UUID id, String event, long eventSeq, String fromStatus, String toStatus, int attemptNo,
            BigDecimal amount, UUID sellerBankId, String recipientAccountLast4,
            String provider, String providerReference, String providerStatus, String errorCode, String errorMessage,
            boolean simulated, UUID actorId, OffsetDateTime occurredAt, UUID transactionId) { }
}
