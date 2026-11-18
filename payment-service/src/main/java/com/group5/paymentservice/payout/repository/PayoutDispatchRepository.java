package com.group5.paymentservice.payout.repository;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PayoutDispatchRepository {
    private final JdbcTemplate jdbc;

    // Chỉ khóa dòng được chọn để các worker khác tiếp tục lấy payout còn lại.
    public Optional<Map<String, Object>> claimNextDue() {
        return jdbc
                .queryForList(
                        """
SELECT * FROM payouts
WHERE execution_mode='AUTO' AND status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=now())
ORDER BY next_attempt_at NULLS FIRST,id LIMIT 1 FOR UPDATE SKIP LOCKED
""")
                .stream()
                .findFirst();
    }

    public Optional<Map<String, Object>> findVerifiedDefault(UUID sellerId) {
        return jdbc
                .queryForList(
                        """
SELECT * FROM seller_bank
WHERE seller_id=? AND active AND is_default AND verification_status='VERIFIED'
""",
                        sellerId)
                .stream()
                .findFirst();
    }

    public void holdWithoutRecipient(UUID payoutId) {
        jdbc.update(
                """
UPDATE payouts SET status='ON_HOLD',seller_bank_id=NULL,recipient_bank_code=NULL,
    recipient_account_encrypted=NULL,recipient_account_last4=NULL,recipient_holder_name=NULL,
    next_attempt_at=NULL,version=version+1,updated_at=now() WHERE id=? AND execution_mode='AUTO'
""",
                payoutId);
    }

    public void startAttempt(
            UUID payoutId, int attempt, Map<String, Object> bank, String provider) {
        jdbc.update(
                """
UPDATE payouts SET status='PROCESSING',attempt_count=?,attempt_started_at=now(),next_attempt_at=NULL,
    next_reconcile_at=NULL,
    seller_bank_id=?,recipient_bank_code=?,recipient_account_encrypted=?,recipient_account_last4=?,
    recipient_holder_name=?,provider=?,provider_reference=NULL,transaction_id=NULL,return_transaction_id=NULL,
    succeeded_at=NULL,returned_at=NULL,updated_at=now(),version=version+1 WHERE id=? AND execution_mode='AUTO'
""",
                attempt,
                bank.get("id"),
                bank.get("bank_code"),
                bank.get("account_number_encrypted"),
                bank.get("account_last4"),
                bank.get("account_holder_name"),
                provider,
                payoutId);
    }

    public List<UUID> findHeldSellersWithVerifiedDefault() {
        return jdbc.queryForList(
                """
SELECT DISTINCT p.seller_id FROM payouts p JOIN seller_bank b ON b.seller_id=p.seller_id
WHERE p.execution_mode='AUTO' AND p.status='ON_HOLD' AND b.active AND b.is_default AND b.verification_status='VERIFIED'
LIMIT 100
""",
                UUID.class);
    }

    // Đặt lịch tiếp theo ngay khi nhận việc để lookup lỗi cũng không chiếm mãi đầu hàng đợi.
    public Optional<Map<String, Object>> claimNextReconciliation(
            String provider, OffsetDateTime cutoff, OffsetDateTime nextReconcileAt) {
        return jdbc
                .queryForList(
                        """
WITH due AS (
    SELECT id FROM payouts
    WHERE execution_mode='AUTO' AND provider=? AND (next_reconcile_at IS NULL OR next_reconcile_at<=now())
      AND ((status IN ('UNKNOWN','PROCESSING') AND attempt_started_at<=?)
        OR (status='SUCCEEDED' AND next_attempt_at<=now()))
    ORDER BY coalesce(next_reconcile_at,attempt_started_at),id
    LIMIT 1 FOR UPDATE SKIP LOCKED
)
UPDATE payouts p SET next_reconcile_at=? FROM due
WHERE p.id=due.id AND p.execution_mode='AUTO' RETURNING p.id,p.attempt_count,p.provider,p.execution_mode
""",
                        provider,
                        cutoff,
                        nextReconcileAt)
                .stream()
                .findFirst();
    }

    public Optional<Map<String, Object>> findUnresolvedAttempt(UUID payoutId, int attempt) {
        return jdbc
                .queryForList(
                        """
SELECT * FROM payouts WHERE id=? AND attempt_count=? AND execution_mode='AUTO' AND status IN ('UNKNOWN','PROCESSING')
""",
                        payoutId,
                        attempt)
                .stream()
                .findFirst();
    }
}
