package com.group5.paymentservice.payout.dto.response;

import java.util.List;
import java.util.UUID;

public record ManualReceiptView(
        UUID attemptId,
        String attemptState,
        String receiptStatus,
        long receiptVersion,
        boolean hasOpenDispute,
        List<String> allowedActions,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                DisputeSummary dispute) {
    public ManualReceiptView(
            UUID attemptId,
            String attemptState,
            String receiptStatus,
            long receiptVersion,
            boolean hasOpenDispute,
            List<String> allowedActions) {
        this(
                attemptId,
                attemptState,
                receiptStatus,
                receiptVersion,
                hasOpenDispute,
                allowedActions,
                null);
    }

    public record DisputeSummary(UUID id, String status, String reason) {}
}
