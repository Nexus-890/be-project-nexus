package com.group5.paymentservice.payout.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BankTransfer(
        String provider,
        String transactionId,
        String accountNumber,
        BigDecimal amount,
        String content,
        String bankReference,
        OffsetDateTime transactionAt) {}
