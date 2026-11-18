package com.group5.paymentservice.payout.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PayoutHistoryRepository {
    private final JdbcTemplate jdbc;

    public record Change(String event, String fromStatus, String toStatus, UUID actorId,
            String providerKey, String providerStatus, String errorCode, String errorMessage,
            UUID transactionId, String requestKey) { }

    public Optional<Map<String, Object>> findRetryRequest(String requestKey) {
        return jdbc.queryForList("""
                SELECT payout_id,actor_id FROM payout_history WHERE request_idempotency_key=?
                """, requestKey).stream().findFirst();
    }

    public void append(Map<String, Object> payout, Change change) {
        jdbc.update("""
                INSERT INTO payout_history(payout_id,event_seq,event,from_status,to_status,attempt_no,amount,
                    seller_bank_id,recipient_bank_code,recipient_account_encrypted,recipient_account_last4,recipient_holder_name,
                    provider,provider_reference,provider_idempotency_key,provider_status,error_code,error_message,
                    transaction_id,request_idempotency_key,simulated,actor_id)
                VALUES (?,(SELECT coalesce(max(event_seq),0)+1 FROM payout_history WHERE payout_id=?),?::payout_history_event,
                    ?::payout_status,?::payout_status,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, payout.get("id"), payout.get("id"), change.event(), change.fromStatus(), change.toStatus(),
                payout.get("attempt_count"), payout.get("net_amount"), payout.get("seller_bank_id"),
                payout.get("recipient_bank_code"), payout.get("recipient_account_encrypted"),
                payout.get("recipient_account_last4"), payout.get("recipient_holder_name"),
                payout.get("provider"), payout.get("provider_reference"), change.providerKey(), change.providerStatus(),
                change.errorCode(), change.errorMessage(), change.transactionId(), change.requestKey(),
                "sandbox".equals(payout.get("provider")), change.actorId());
    }
}
