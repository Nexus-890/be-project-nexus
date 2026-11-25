package com.group5.paymentservice.payout.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ManualPayoutView(
        UUID id,
        UUID orderId,
        UUID sellerId,
        String status,
        String executionMode,
        String confirmationSource,
        String orderAmount,
        String shippingFee,
        String platformFee,
        String netAmount,
        String currency,
        long version,
        String blockedReason,
        List<String> allowedActions,
        OffsetDateTime serverTime,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Attempt currentAttempt,
        List<Evidence> evidence,
        List<ReviewCase> cases,
        List<History> history,
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                Recipient recipient) {
    public ManualPayoutView(
            UUID id,
            UUID orderId,
            UUID sellerId,
            String status,
            String executionMode,
            String confirmationSource,
            String orderAmount,
            String shippingFee,
            String platformFee,
            String netAmount,
            String currency,
            long version,
            String blockedReason,
            List<String> allowedActions,
            OffsetDateTime serverTime,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Attempt currentAttempt,
            List<Evidence> evidence,
            List<ReviewCase> cases,
            List<History> history) {
        this(
                id,
                orderId,
                sellerId,
                status,
                executionMode,
                confirmationSource,
                orderAmount,
                shippingFee,
                platformFee,
                netAmount,
                currency,
                version,
                blockedReason,
                allowedActions,
                serverTime,
                createdAt,
                updatedAt,
                currentAttempt,
                evidence,
                cases,
                history,
                null);
    }

    public record Recipient(String bankCode, String accountLast4, String holderName) {}

    public record Attempt(
            UUID id,
            int attemptNo,
            String state,
            long version,
            UUID claimedBy,
            OffsetDateTime claimExpiresAt,
            OffsetDateTime qrExpiresAt,
            String reference,
            String receiptStatus,
            long receiptVersion,
            OffsetDateTime sellerConfirmedAt,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    BankMatch bankMatch) {}

    public record BankMatch(
            String provider,
            String transactionId,
            String amount,
            OffsetDateTime transferredAt,
            String outcome,
            int transfers) {}

    public record Evidence(UUID id, String contentType, long size, OffsetDateTime createdAt) {}

    public record ReviewCase(
            UUID id,
            String kind,
            String status,
            String reason,
            String explanation,
            long version,
            OffsetDateTime createdAt) {}

    public record History(
            UUID id,
            String event,
            UUID attemptId,
            UUID caseId,
            UUID actorId,
            String actorRole,
            String reason,
            OffsetDateTime occurredAt) {}
}
