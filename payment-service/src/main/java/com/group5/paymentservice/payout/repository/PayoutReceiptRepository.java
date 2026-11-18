package com.group5.paymentservice.payout.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface PayoutReceiptRepository {
    void updateReceipt(UUID attemptId, String status, OffsetDateTime confirmedAt);
}
