package com.group5.paymentservice.payout.dto.request;

import com.group5.paymentservice.payout.enums.PayoutReceiptStatus;
import com.group5.paymentservice.payout.enums.PayoutStatus;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ManualPayoutFilter(
        UUID sellerId,
        List<PayoutStatus> status,
        String claimedBy,
        PayoutReceiptStatus receiptStatus,
        Boolean hasOpenDispute,
        Boolean needsReview,
        OffsetDateTime from,
        OffsetDateTime to,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {
    public ManualPayoutFilter {
        status = status == null ? List.of() : List.copyOf(status);
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
