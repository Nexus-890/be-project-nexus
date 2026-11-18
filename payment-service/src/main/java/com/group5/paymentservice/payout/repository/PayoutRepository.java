package com.group5.paymentservice.payout.repository;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PayoutRepository {
    private final JdbcTemplate jdbc;

    public Optional<Map<String, Object>> findEligibleOrder(UUID orderId) {
        return jdbc
                .queryForList(
                        """
SELECT c.*,i.id AS instruction_id,i.session_id,i.amount,i.currency
FROM payment_order_completions c
JOIN payout_eligibility e ON e.order_id=c.order_id
JOIN payment_instructions i ON i.order_id=c.order_id
JOIN payment_sessions s ON s.id=i.session_id
JOIN payment_transactions t ON t.id=i.funded_transaction_id
WHERE c.order_id=? AND c.order_amount IS NOT NULL AND c.shipping_fee IS NOT NULL
  AND i.status='PAID' AND s.status='PAID' AND i.canceled_at IS NULL
  AND i.paid_at IS NOT NULL AND i.paid_at<=c.completed_at AND i.amount>0
  AND i.buyer_id=c.buyer_id AND s.buyer_id=c.buyer_id AND i.seller_id=c.seller_id
  AND s.order_group_id=c.order_group_id AND i.last_order_version<=c.order_version
  AND t.status='SUCCESS' AND t.type='NORMAL' AND t.session_id=s.id
  AND t.currency=i.currency AND s.currency=i.currency
  AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=c.order_id)
  AND NOT EXISTS(SELECT 1 FROM payment_pending_order_cancellations p WHERE p.order_id=c.order_id)
  AND NOT EXISTS(SELECT 1 FROM payment_pending_cancellations p WHERE p.order_group_id=c.order_group_id)
  AND NOT EXISTS(SELECT 1 FROM payouts p WHERE p.order_id=c.order_id)
""",
                        orderId)
                .stream()
                .findFirst();
    }

    public void lockSellerBank(UUID sellerId) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "seller-bank:" + sellerId);
    }

    public Optional<UUID> findVerifiedDefaultBank(UUID sellerId) {
        return jdbc
                .queryForList(
                        """
SELECT id FROM seller_bank
WHERE seller_id=? AND active AND is_default AND verification_status='VERIFIED'
""",
                        UUID.class,
                        sellerId)
                .stream()
                .findFirst();
    }

    public Optional<Map<String, Object>> findVerifiedDefaultBankDetails(UUID sellerId) {
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

    public UUID create(
            UUID orderId,
            Map<String, Object> order,
            UUID bankId,
            String status,
            String provider,
            BigDecimal rate,
            BigDecimal fee,
            BigDecimal netAmount) {
        return create(orderId, order, bankId, status, provider, rate, fee, netAmount, "AUTO", null);
    }

    public UUID create(
            UUID orderId,
            Map<String, Object> order,
            UUID bankId,
            String status,
            String provider,
            BigDecimal rate,
            BigDecimal fee,
            BigDecimal netAmount,
            String mode,
            String blockedReason) {
        return jdbc
                .queryForList(
                        """
INSERT INTO payouts(order_id,session_id,instruction_id,seller_id,seller_bank_id,order_amount,
    shipping_fee,platform_fee_rate,platform_fee,net_amount,currency,status,next_attempt_at,provider,source_event_id,
    execution_mode,blocked_reason)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?::payout_status,CASE WHEN ?='AUTO' THEN now() END,?,?,?,?)
ON CONFLICT(order_id) DO NOTHING RETURNING id
""",
                        UUID.class,
                        orderId,
                        order.get("session_id"),
                        order.get("instruction_id"),
                        order.get("seller_id"),
                        bankId,
                        order.get("amount"),
                        order.get("shipping_fee"),
                        rate,
                        fee,
                        netAmount,
                        order.get("currency"),
                        status,
                        mode,
                        provider,
                        order.get("source_event_id"),
                        mode,
                        blockedReason)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public Optional<Map<String, Object>> findLocked(UUID id) {
        return jdbc.queryForList("SELECT * FROM payouts WHERE id=? FOR UPDATE", id).stream()
                .findFirst();
    }

    public List<Map<String, Object>> findHeldForUpdate(UUID sellerId) {
        return jdbc.queryForList(
                """
SELECT * FROM payouts WHERE seller_id=? AND status='ON_HOLD' AND execution_mode='AUTO' ORDER BY id FOR UPDATE
""",
                sellerId);
    }

    public void release(UUID id, UUID bankId) {
        jdbc.update(
                """
UPDATE payouts SET status='PENDING',seller_bank_id=?,next_attempt_at=now(),
    blocked_reason=NULL,updated_at=now(),version=version+1 WHERE id=? AND execution_mode='AUTO'
""",
                bankId,
                id);
    }

    public void lockRetryRequest(String requestKey) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                "payout-retry:" + requestKey);
    }

    public void scheduleAdminRetry(UUID id, UUID actorId) {
        jdbc.update(
                """
                UPDATE payouts SET status='PENDING',next_attempt_at=now(),updated_at=now(),
                    updated_by=?,version=version+1 WHERE id=? AND execution_mode='AUTO'
                """,
                actorId,
                id);
    }

    public void updateProviderReference(UUID id, String reference) {
        jdbc.update("UPDATE payouts SET provider_reference=? WHERE id=?", reference, id);
    }

    public void recordSuccess(UUID id, UUID transactionId) {
        jdbc.update(
                "UPDATE payouts SET transaction_id=?,succeeded_at=now() WHERE id=?",
                transactionId,
                id);
    }

    public void recordReturn(UUID id, UUID transactionId) {
        jdbc.update(
                "UPDATE payouts SET return_transaction_id=?,returned_at=now() WHERE id=?",
                transactionId,
                id);
    }

    public void updateOutcome(UUID id, String status, UUID actorId, OffsetDateTime nextAttemptAt) {
        jdbc.update(
                """
UPDATE payouts SET status=?::payout_status,updated_at=now(),updated_by=?,version=version+1,
    next_attempt_at=? WHERE id=?
""",
                status,
                actorId,
                nextAttemptAt,
                id);
    }

    public void scheduleAutomaticRetry(UUID id, OffsetDateTime nextAttemptAt) {
        jdbc.update(
                "UPDATE payouts SET status='PENDING',next_attempt_at=?,version=version+1 WHERE id=?"
                    + " AND execution_mode='AUTO'",
                nextAttemptAt,
                id);
    }

    /**
     * Caller holds payment-group before instruction/refund locks, as manual claim/issuance does.
     */
    public boolean hasReservedManualProceeds(UUID orderId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM payouts WHERE order_id=? AND execution_mode='MANUAL'
  AND status IN ('PROCESSING','UNKNOWN','SUCCEEDED'))
""",
                        Boolean.class,
                        orderId));
    }
}
