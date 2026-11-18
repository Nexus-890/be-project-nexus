package com.group5.paymentservice.payout.model;

import java.util.Map;

/** Internal list projection; encrypted readiness data is never serialized to API responses. */
public record ManualPayoutQueueRow(
        Map<String, Object> payout,
        Map<String, Object> attempt,
        Map<String, Object> reviewedBank,
        boolean funded) {}
