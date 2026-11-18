package com.group5.paymentservice.payout.repository.jdbc;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.ManualPayoutFilter;
import com.group5.paymentservice.payout.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class JdbcManualPayoutRepository
        implements ManualPayoutRepository, PayoutReceiptRepository, PayoutDisputeRepository {
    private final JdbcTemplate jdbc;
    private static final Set<String> ATTEMPT_FIELDS =
            Set.of(
                    "state",
                    "qr_issued_at",
                    "qr_expires_at",
                    "source_account_id",
                    "bank_transfer_date",
                    "bank_transfer_reference",
                    "transferred_at",
                    "confirmed_by",
                    "confirmed_at",
                    "transaction_id",
                    "correction_transaction_id",
                    "return_transaction_id");
    private static final Set<String> PAYOUT_FIELDS =
            Set.of(
                    "status",
                    "blocked_reason",
                    "current_attempt_id",
                    "attempt_count",
                    "confirmation_source",
                    "transaction_id",
                    "return_transaction_id",
                    "succeeded_at",
                    "returned_at",
                    "seller_bank_id",
                    "recipient_bank_code",
                    "recipient_account_encrypted",
                    "recipient_account_last4",
                    "recipient_holder_name",
                    "updated_by");
    private static final Set<String> CASE_FIELDS =
            Set.of("status", "resolution", "reviewed_by", "resolved_at");

    public void lockKey(String key) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", key);
    }

    public Optional<Map<String, Object>> payout(UUID id, boolean locked) {
        return first(
                jdbc.queryForList(
                        "SELECT * FROM payouts WHERE id=?" + (locked ? " FOR UPDATE" : ""), id));
    }

    public void lockPayoutContext(Map<String, Object> p) {
        lockKey("payout-order:" + p.get("order_id"));
        var group =
                jdbc.queryForObject(
                        "SELECT order_group_id FROM payment_sessions WHERE id=?",
                        UUID.class,
                        p.get("session_id"));
        lockKey("payment-group:" + group);
        lockKey("seller-bank:" + p.get("seller_id"));
    }

    public Optional<Map<String, Object>> attempt(UUID payoutId, UUID attemptId) {
        return first(
                jdbc.queryForList(
                        "SELECT * FROM manual_payout_attempts WHERE payout_id=? AND id=?",
                        payoutId,
                        attemptId));
    }

    public Optional<Map<String, Object>> currentAttempt(UUID payoutId) {
        return first(
                jdbc.queryForList(
                        "SELECT a.* FROM payouts p JOIN manual_payout_attempts a ON"
                                + " a.id=p.current_attempt_id WHERE p.id=?",
                        payoutId));
    }

    public Optional<Map<String, Object>> bank(UUID sellerId) {
        return first(
                jdbc.queryForList(
                        "SELECT * FROM seller_bank WHERE seller_id=? AND active AND is_default AND"
                                + " verification_status='VERIFIED'",
                        sellerId));
    }

    public boolean funded(UUID payoutId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM payouts p JOIN payment_order_completions c ON c.order_id=p.order_id
JOIN payout_eligibility e ON e.order_id=p.order_id JOIN payment_instructions i ON i.id=p.instruction_id
JOIN payment_sessions s ON s.id=i.session_id JOIN payment_transactions t ON t.id=i.funded_transaction_id
WHERE p.id=? AND i.status='PAID' AND s.status='PAID' AND i.canceled_at IS NULL
AND i.paid_at IS NOT NULL AND i.paid_at<=c.completed_at AND i.amount=c.order_amount AND i.amount=p.order_amount
AND i.buyer_id=c.buyer_id AND s.buyer_id=c.buyer_id AND i.seller_id=c.seller_id AND p.seller_id=c.seller_id
AND s.order_group_id=c.order_group_id AND i.last_order_version<=c.order_version
AND t.status='SUCCESS' AND t.type='NORMAL' AND t.session_id=s.id AND t.currency=i.currency AND s.currency=i.currency
AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=p.order_id)
AND NOT EXISTS(SELECT 1 FROM payment_pending_order_cancellations x WHERE x.order_id=p.order_id)
AND NOT EXISTS(SELECT 1 FROM payment_pending_cancellations x WHERE x.order_group_id=s.order_group_id))
""",
                        Boolean.class,
                        payoutId));
    }

    public void insertAttempt(Map<String, Object> row) {
        insert("manual_payout_attempts", row);
    }

    public void updateAttempt(UUID id, Map<String, Object> fields) {
        update("manual_payout_attempts", id, fields, ATTEMPT_FIELDS);
    }

    public void updatePayout(UUID id, Map<String, Object> fields) {
        update("payouts", id, fields, PAYOUT_FIELDS);
    }

    public Optional<Map<String, Object>> command(UUID key) {
        return first(
                jdbc.queryForList(
                        "SELECT * FROM manual_payout_commands WHERE idempotency_key=?", key));
    }

    public void recordCommand(
            UUID key,
            UUID actor,
            String operation,
            UUID payoutId,
            UUID attemptId,
            UUID caseId,
            String hash,
            String json,
            String actorRole) {
        jdbc.update(
                "INSERT INTO"
                    + " manual_payout_commands(idempotency_key,actor_id,operation,payout_id,attempt_id,case_id,payload_hash,result,actor_role)"
                    + " VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                key,
                actor,
                operation,
                payoutId,
                attemptId,
                caseId,
                hash,
                json,
                actorRole);
    }

    public List<UUID> queue(ManualPayoutFilter f, CurrentUser actor) {
        var parameters = new ArrayList<Object>();
        String filter = filter(f, actor, parameters);
        parameters.add(f.size());
        parameters.add((long) f.page() * f.size());
        return jdbc.queryForList(
                "SELECT p.id FROM payouts p LEFT JOIN manual_payout_attempts a ON"
                        + " a.id=p.current_attempt_id"
                        + filter
                        + " ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",
                UUID.class,
                parameters.toArray());
    }

    public long count(ManualPayoutFilter f, CurrentUser actor) {
        var parameters = new ArrayList<Object>();
        String filter = filter(f, actor, parameters);
        return jdbc.queryForObject(
                "SELECT count(*) FROM payouts p LEFT JOIN manual_payout_attempts a ON"
                        + " a.id=p.current_attempt_id"
                        + filter,
                Long.class,
                parameters.toArray());
    }

    private String filter(ManualPayoutFilter f, CurrentUser actor, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE p.execution_mode='MANUAL'");
        if (f.sellerId() != null) {
            sql.append(" AND p.seller_id=?");
            args.add(f.sellerId());
        }
        if (!f.status().isEmpty()) {
            sql.append(" AND p.status IN (")
                    .append(
                            String.join(
                                    ",",
                                    Collections.nCopies(f.status().size(), "?::payout_status")))
                    .append(")");
            f.status().forEach(s -> args.add(s.name()));
        }
        if (f.claimedBy() != null) {
            sql.append(" AND a.claimed_by=?");
            args.add("me".equals(f.claimedBy()) ? actor.id() : UUID.fromString(f.claimedBy()));
        }
        if (f.receiptStatus() != null) {
            sql.append(" AND a.receipt_status=?");
            args.add(f.receiptStatus().name());
        }
        String open =
                "EXISTS(SELECT 1 FROM payout_review_cases c WHERE c.payout_id=p.id AND"
                        + " c.status<>'RESOLVED'";
        if (f.hasOpenDispute() != null)
            sql.append(" AND ")
                    .append(f.hasOpenDispute() ? "" : "NOT ")
                    .append(open)
                    .append(" AND c.kind='SELLER_DISPUTE')");
        if (f.needsReview() != null)
            sql.append(" AND ")
                    .append(f.needsReview() ? "" : "NOT ")
                    .append("(p.status='UNKNOWN' OR ")
                    .append(open)
                    .append("))");
        if (f.from() != null) {
            sql.append(" AND p.created_at>=?");
            args.add(f.from());
        }
        if (f.to() != null) {
            sql.append(" AND p.created_at<=?");
            args.add(f.to());
        }
        return sql.toString();
    }

    public List<Map<String, Object>> history(UUID payoutId) {
        return jdbc.queryForList(
                "SELECT * FROM payout_history WHERE payout_id=? ORDER BY event_seq DESC", payoutId);
    }

    public void audit(
            UUID payoutId,
            UUID attemptId,
            UUID caseId,
            String event,
            String from,
            UUID actor,
            String role,
            String reason) {
        jdbc.update(
                """
INSERT INTO payout_history(payout_id,event_seq,event,from_status,to_status,attempt_no,amount,
seller_bank_id,recipient_account_last4,provider,simulated,actor_id,manual_attempt_id,case_id,actor_role,reason,evidence_ids,transaction_id)
SELECT p.id,(SELECT coalesce(max(h.event_seq),0)+1 FROM payout_history h WHERE h.payout_id=p.id),?::payout_history_event,
?::payout_status,p.status,p.attempt_count,p.net_amount,p.seller_bank_id,p.recipient_account_last4,'manual',false,?,?,?,?,?,
coalesce((SELECT evidence_ids FROM payout_review_cases WHERE id=?),(SELECT evidence_ids FROM manual_payout_attempts WHERE id=?),'{}'::uuid[]),
(SELECT CASE ? WHEN 'MANUAL_CONFIRMED' THEN a.transaction_id WHEN 'RETURNED' THEN a.return_transaction_id
 WHEN 'CONFIRMATION_CORRECTED' THEN a.correction_transaction_id ELSE NULL END FROM manual_payout_attempts a WHERE a.id=?)
FROM payouts p WHERE p.id=?
""",
                event,
                from,
                actor,
                attemptId,
                caseId,
                role,
                reason,
                caseId,
                attemptId,
                event,
                attemptId,
                payoutId);
    }

    public List<Map<String, Object>> evidence(UUID attemptId) {
        return jdbc.queryForList(
                "SELECT * FROM manual_payout_evidence WHERE attempt_id=? ORDER BY created_at,id",
                attemptId);
    }

    public Optional<Map<String, Object>> evidence(UUID payoutId, UUID attemptId, UUID evidenceId) {
        return first(
                jdbc.queryForList(
                        "SELECT * FROM manual_payout_evidence WHERE payout_id=? AND attempt_id=?"
                                + " AND id=?",
                        payoutId,
                        attemptId,
                        evidenceId));
    }

    public void insertEvidence(Map<String, Object> row) {
        insert("manual_payout_evidence", row);
    }

    public void attachEvidence(UUID targetId, boolean reviewCase, List<UUID> ids) {
        jdbc.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "UPDATE "
                                            + (reviewCase
                                                    ? "payout_review_cases"
                                                    : "manual_payout_attempts")
                                            + " SET evidence_ids=? WHERE id=?");
                    statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
                    statement.setObject(2, targetId);
                    return statement;
                });
    }

    public boolean evidenceObjectExists(String key) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM manual_payout_evidence WHERE object_key=?)",
                        Boolean.class,
                        key));
    }

    public List<Map<String, Object>> cases(UUID payoutId) {
        return jdbc.queryForList(
                "SELECT * FROM payout_review_cases WHERE payout_id=? ORDER BY created_at DESC,id"
                        + " DESC",
                payoutId);
    }

    public Optional<Map<String, Object>> reviewCase(UUID payoutId, UUID attemptId, UUID caseId) {
        return first(
                jdbc.queryForList(
                        "SELECT * FROM payout_review_cases WHERE payout_id=? AND attempt_id=? AND"
                                + " id=?",
                        payoutId,
                        attemptId,
                        caseId));
    }

    public void insertCase(Map<String, Object> row) {
        insert("payout_review_cases", row);
    }

    public void updateCase(UUID id, Map<String, Object> fields) {
        update("payout_review_cases", id, fields, CASE_FIELDS);
    }

    public void updateReceipt(UUID attemptId, String status, OffsetDateTime confirmedAt) {
        jdbc.update(
                "UPDATE manual_payout_attempts SET"
                    + " receipt_status=?,receipt_version=receipt_version+1,version=version+1,seller_confirmed_at=coalesce(?,seller_confirmed_at),updated_at=now()"
                    + " WHERE id=?",
                status,
                confirmedAt,
                attemptId);
    }

    public UUID movement(UUID payoutId, UUID attemptId, String type, UUID original) {
        return jdbc.queryForObject(
                """
INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,currency,status,type,idempotency_key,received_at,linked_transaction_id)
SELECT p.session_id,'manual',?,p.net_amount,p.currency,'SUCCESS',?::payment_transaction_type,?,now(),?
FROM payouts p WHERE p.id=? RETURNING id
""",
                UUID.class,
                type + ":" + attemptId,
                type,
                "manual:" + attemptId + ":" + type,
                original,
                payoutId);
    }

    public List<UUID> expiring(UUID after, int limit) {
        return jdbc.queryForList(
                """
SELECT DISTINCT payout_id FROM manual_payout_attempts a WHERE (?::uuid IS NULL OR payout_id>?::uuid) AND ((state='CLAIMED' AND claim_expires_at<=now())
OR (state='QR_ISSUED' AND (qr_expires_at<=now() OR NOT EXISTS(SELECT 1 FROM seller_bank b
    WHERE b.id=a.seller_bank_id AND b.version=a.bank_version AND b.active AND b.is_default AND b.verification_status='VERIFIED'))))
ORDER BY payout_id LIMIT ?
""",
                UUID.class,
                after,
                after,
                limit);
    }

    public List<UUID> issuedBySeller(UUID sellerId) {
        return jdbc.queryForList(
                "SELECT p.id FROM payouts p JOIN manual_payout_attempts a ON"
                    + " a.id=p.current_attempt_id WHERE p.seller_id=? AND a.state='QR_ISSUED' ORDER"
                    + " BY p.id",
                UUID.class,
                sellerId);
    }

    private Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.stream().findFirst();
    }

    private void insert(String table, Map<String, Object> row) {
        var columns = new ArrayList<>(row.keySet());
        String sql =
                "INSERT INTO "
                        + table
                        + "("
                        + String.join(",", columns)
                        + ") VALUES ("
                        + String.join(",", columns.stream().map(c -> ":" + c).toList())
                        + ")";
        new NamedParameterJdbcTemplate(jdbc).update(sql, row);
    }

    private void update(String table, UUID id, Map<String, Object> fields, Set<String> allowed) {
        if (!allowed.containsAll(fields.keySet()))
            throw new IllegalArgumentException("Unsupported internal persistence field");
        var params = new HashMap<String, Object>(fields);
        params.put("rowId", id);
        String sql =
                "UPDATE "
                        + table
                        + " SET "
                        + String.join(
                                ",",
                                fields.keySet().stream()
                                        .map(
                                                k ->
                                                        k
                                                                + "=:"
                                                                + k
                                                                + ("status".equals(k)
                                                                                && "payouts"
                                                                                        .equals(
                                                                                                table)
                                                                        ? "::payout_status"
                                                                        : ""))
                                        .toList())
                        + ",version=version+1,updated_at=now() WHERE id=:rowId";
        new NamedParameterJdbcTemplate(jdbc).update(sql, params);
    }
}
