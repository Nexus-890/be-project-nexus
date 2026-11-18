package com.group5.paymentservice.payout.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Internal encrypted transfer instruction. Never serialize this model in a controller response. */
public record ManualTransferSnapshot(
        UUID bankId,
        long bankVersion,
        String bankCode,
        String encryptedAccount,
        String holderName,
        BigDecimal netAmount,
        String currency,
        String reference) {
    public static ManualTransferSnapshot fromAttempt(Map<String, Object> row) {
        return new ManualTransferSnapshot(
                (UUID) row.get("seller_bank_id"),
                ((Number) row.get("bank_version")).longValue(),
                (String) row.get("bank_code"),
                (String) row.get("account_encrypted"),
                (String) row.get("holder_name"),
                (BigDecimal) row.get("net_amount"),
                (String) row.get("currency"),
                (String) row.get("reference"));
    }
}
