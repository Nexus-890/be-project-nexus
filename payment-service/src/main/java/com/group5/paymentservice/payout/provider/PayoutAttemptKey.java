package com.group5.paymentservice.payout.provider;

import java.util.UUID;

/** Shared attempt identity for dispatch, reconciliation and provider callbacks. */
public final class PayoutAttemptKey {
    private PayoutAttemptKey() { }

    public static String of(UUID payoutId, int attempt) {
        return "payout:" + payoutId + ":" + attempt;
    }
}
