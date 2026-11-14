package com.group5.paymentservice.payment.dto.response;

import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentView(
        UUID transactionId,
        UUID orderGroupId,
        String provider,
        String providerReference,
        BigDecimal amount,
        String currency,
        PaymentTransactionType type,
        PaymentTransactionStatus status,
        OffsetDateTime receivedAt) {
}
