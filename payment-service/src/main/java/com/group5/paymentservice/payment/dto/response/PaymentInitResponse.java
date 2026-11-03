package com.group5.paymentservice.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentInitResponse(
        UUID sessionId,
        String qrImageUrl,
        String bankAccount,
        BigDecimal amount,
        String memoCode,
        Instant expiresAt) {
}
