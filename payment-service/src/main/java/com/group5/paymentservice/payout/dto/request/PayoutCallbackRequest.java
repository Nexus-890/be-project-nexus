package com.group5.paymentservice.payout.dto.request;

import com.group5.paymentservice.payout.provider.PayoutGateway;

import java.util.UUID;

public record PayoutCallbackRequest(UUID payoutId, int attempt, String idempotencyKey,
        PayoutGateway.State state, String reference, String providerStatus) { }
