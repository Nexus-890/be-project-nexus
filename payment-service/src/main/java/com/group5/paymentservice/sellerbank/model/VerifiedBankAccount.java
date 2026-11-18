package com.group5.paymentservice.sellerbank.model;

import java.util.UUID;

/** Snapshot nội bộ cho lệnh chuyển tiền, không trả trực tiếp qua API. */
public record VerifiedBankAccount(UUID id, UUID sellerId, String bankCode, String accountNumberEncrypted,
        String accountLast4, String accountHolderName) {
    @Override public String toString() { return "VerifiedBankAccount[id=" + id + ", accountLast4=" + accountLast4 + "]"; }
}
