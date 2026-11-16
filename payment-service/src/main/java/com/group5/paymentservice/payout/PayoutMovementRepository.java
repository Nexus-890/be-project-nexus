package com.group5.paymentservice.payout;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PayoutMovementRepository {
    private final JdbcTemplate jdbc;

    public Optional<UUID> findByIdempotencyKey(String key) {
        return jdbc.queryForList("SELECT id FROM payment_transactions WHERE idempotency_key=?", UUID.class, key)
                .stream().findFirst();
    }

    public UUID recordMovement(Map<String, Object> payout, String type, String key, String reference) {
        return jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,currency,status,type,idempotency_key,received_at)
                VALUES (?,?,?,?,?,'SUCCESS',?::payment_transaction_type,?,now()) RETURNING id
                """, UUID.class, payout.get("session_id"), payout.get("provider"), reference,
                payout.get("net_amount"), payout.get("currency"), type, key);
    }
}
