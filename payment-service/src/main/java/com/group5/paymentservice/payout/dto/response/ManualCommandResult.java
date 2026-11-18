package com.group5.paymentservice.payout.dto.response;

/** A recorded conflict is returned out of the transaction before the controller emits HTTP 409. */
public record ManualCommandResult(
        ManualPayoutView result, boolean replay, boolean created, boolean conflict) {}
