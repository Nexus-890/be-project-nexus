package com.group5.ratingservice.reputation.repository;

import com.group5.ratingservice.reputation.dto.PenaltyItem;
import com.group5.ratingservice.reputation.dto.ReputationHistoryItem;
import com.group5.ratingservice.reputation.dto.ReputationSnapshot;
import com.group5.ratingservice.reputation.enums.TrustLevel;
import com.group5.ratingservice.reputation.model.ReputationState;

import com.group5.common_core.exception.DomainException;
import com.group5.ratingservice.shared.exception.RatingErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReputationStore {
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON = JsonMapper.shared();

    public Optional<ReputationState> find(UUID userId) {
        return jdbc.query("SELECT * FROM user_reputations WHERE user_id = ?", this::state, userId).stream().findFirst();
    }

    public ReputationState lock(UUID userId) {
        return lock(userId, new UUID(0, 0));
    }

    public ReputationState lock(UUID userId, UUID actor) {
        jdbc.update("INSERT INTO user_reputations (user_id,created_by,updated_by) VALUES (?,?,?) ON CONFLICT (user_id) DO NOTHING",
                userId, actor, actor);
        return jdbc.queryForObject("SELECT * FROM user_reputations WHERE user_id = ? FOR UPDATE", this::state, userId);
    }

    public void save(ReputationState state, UUID actor) {
        jdbc.update("""
                UPDATE user_reputations SET score=?, trust_level=?::trust_level, total_transaction=?,
                    successful_transaction=?, failed_transaction=?, rating_count=?, rating_score_sum=?,
                    penalty_points=?, successful_auction_count=?, failed_auction_count=?, adjustment_offset=?,
                    policy_version=?, updated_by=?, updated_at=clock_timestamp(), version=version+1
                WHERE user_id=?
                """, state.getScore(), state.getTrustLevel().name(), state.getTotalTransaction(), state.getSuccessfulTransaction(),
                state.getFailedTransaction(), state.getRatingCount(), state.getRatingScoreSum(), state.getPenaltyPoints(),
                state.getSuccessfulAuctionCount(), state.getFailedAuctionCount(), state.getAdjustmentOffset(),
                state.getPolicyVersion(), actor, state.getUserId());
    }

    public void source(String type, UUID source, Map<String, ?> fingerprint) {
        String json = JSON.writeValueAsString(fingerprint);
        int inserted = jdbc.update("""
                INSERT INTO reputation_sources (change_type, source_id, fingerprint) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (change_type, source_id) DO NOTHING
                """, type, source, json);
        if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT fingerprint = ?::jsonb FROM reputation_sources WHERE change_type=? AND source_id=?
                """, Boolean.class, json, type, source))) {
            throw new DomainException(RatingErrorCode.CONFLICT, "Conflicting reputation source snapshot for " + source);
        }
    }

    public void outcome(UUID orderId, UUID auctionId, String outcome, Map<String, ?> fingerprint) {
        String json = JSON.writeValueAsString(fingerprint);
        int inserted = jdbc.update("""
                INSERT INTO reputation_outcomes (order_id, auction_id, outcome, fingerprint)
                VALUES (?, ?, ?, ?::jsonb) ON CONFLICT (order_id) DO NOTHING
                """, orderId, auctionId, outcome, json);
        if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT outcome=? AND auction_id IS NOT DISTINCT FROM ?::uuid AND fingerprint=?::jsonb
                FROM reputation_outcomes WHERE order_id=?
                """, Boolean.class, outcome, auctionId, json, orderId))) {
            throw new DomainException(RatingErrorCode.CONFLICT, "Conflicting canonical order outcome for " + orderId);
        }
    }

    public boolean claim(String type, UUID source, UUID userId, UUID operation, Map<String, ?> fingerprint) {
        return jdbc.update("""
                INSERT INTO reputation_effects (change_type, source_id, user_id, operation_id, fingerprint)
                VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (change_type, source_id, user_id) DO NOTHING
                """, type, source, userId, operation, JSON.writeValueAsString(fingerprint)) == 1;
    }

    public boolean hiddenRating(UUID ratingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ratings WHERE id=? AND hidden)",
                Boolean.class, ratingId));
    }

    public Optional<Integer> appliedRatingScore(UUID ratingId, UUID userId) {
        return jdbc.query("""
                SELECT (fingerprint->>'score')::int FROM reputation_effects
                WHERE change_type='USER_RATED' AND source_id=? AND user_id=?
                """, (row, index) -> row.getInt(1), ratingId, userId).stream().findFirst();
    }

    public void hideRating(UUID ratingId, UUID actor) {
        jdbc.update("UPDATE ratings SET hidden=true,updated_at=clock_timestamp(),updated_by=? WHERE id=?", actor, ratingId);
    }

    public ReputationHistoryItem history(ReputationState state, ReputationSnapshot before, UUID operation, String type,
            UUID source, Long sourceVersion, UUID event, UUID actor, String reason, Map<String, ?> details) {
        return jdbc.queryForObject("""
                INSERT INTO reputation_history (operation_id, user_id, source_id, source_version, event_id,
                    change_type, previous_score, new_score, delta, previous_trust_level, new_trust_level,
                    actor_id, reason, policy_version, change_details, created_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::trust_level, ?::trust_level, ?, ?, ?, ?::jsonb, clock_timestamp(), ?, ?)
                RETURNING *
                """, HISTORY, operation, state.getUserId(), source, sourceVersion, event, type, before.score(), state.getScore(),
                state.getScore() - before.score(), before.trustLevel().name(), state.getTrustLevel().name(), actor, reason,
                state.getPolicyVersion(), JSON.writeValueAsString(details), actor, actor);
    }

    public List<ReputationHistoryItem> histories(UUID userId, int page, int size) {
        return jdbc.query("SELECT * FROM reputation_history WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                HISTORY, userId, size, Math.multiplyExact((long) page, size));
    }

    public List<PenaltyItem> penalties(UUID userId, int page, int size) {
        return jdbc.query("SELECT * FROM reputation_history WHERE user_id=? AND delta<0 ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                PENALTY, userId, size, Math.multiplyExact((long) page, size));
    }

    public long penaltyCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE user_id=? AND delta<0", Long.class, userId);
    }

    public long historyCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE user_id=?", Long.class, userId);
    }

    public Optional<Map<String, Object>> adjustment(UUID userId, String key) {
        return jdbc.queryForList("SELECT * FROM reputation_adjustment_requests WHERE user_id=? AND idempotency_key=?",
                userId, key).stream().findFirst();
    }

    public boolean sameFingerprint(Map<String, Object> request, Map<String, ?> fingerprint) {
        return JSON.readTree(request.get("fingerprint").toString()).equals(JSON.valueToTree(fingerprint));
    }

    public void adjustment(UUID userId, String key, UUID operation, Map<String, ?> fingerprint, ReputationSnapshot result) {
        jdbc.update("""
                INSERT INTO reputation_adjustment_requests (user_id,idempotency_key,operation_id,fingerprint,result)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
                """, userId, key, operation, JSON.writeValueAsString(fingerprint), JSON.writeValueAsString(result));
    }

    private ReputationState state(ResultSet row, int index) throws SQLException {
        var state = new ReputationState();
        state.setUserId(row.getObject("user_id", UUID.class));
        state.setScore(row.getInt("score"));
        state.setTrustLevel(TrustLevel.valueOf(row.getString("trust_level")));
        state.setTotalTransaction(row.getInt("total_transaction"));
        state.setSuccessfulTransaction(row.getInt("successful_transaction"));
        state.setFailedTransaction(row.getInt("failed_transaction"));
        state.setRatingCount(row.getLong("rating_count"));
        state.setRatingScoreSum(row.getLong("rating_score_sum"));
        state.setPenaltyPoints(row.getLong("penalty_points"));
        state.setSuccessfulAuctionCount(row.getInt("successful_auction_count"));
        state.setFailedAuctionCount(row.getInt("failed_auction_count"));
        state.setAdjustmentOffset(row.getBigDecimal("adjustment_offset"));
        state.setPolicyVersion(row.getString("policy_version"));
        return state;
    }

    private static final RowMapper<PenaltyItem> PENALTY = (row, index) -> new PenaltyItem(
            row.getObject("id", UUID.class), row.getString("change_type"), row.getObject("source_id", UUID.class),
            row.getInt("delta"), row.getInt("previous_score"), row.getInt("new_score"),
            TrustLevel.valueOf(row.getString("previous_trust_level")), TrustLevel.valueOf(row.getString("new_trust_level")),
            row.getString("reason"), row.getObject("created_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC));

    private static final RowMapper<ReputationHistoryItem> HISTORY = (row, index) -> new ReputationHistoryItem(
            row.getObject("id", UUID.class), row.getObject("operation_id", UUID.class), row.getObject("user_id", UUID.class),
            row.getObject("source_id", UUID.class), row.getObject("source_version", Long.class), row.getString("change_type"),
            row.getInt("previous_score"), row.getInt("new_score"), row.getInt("delta"),
            TrustLevel.valueOf(row.getString("previous_trust_level")), TrustLevel.valueOf(row.getString("new_trust_level")),
            row.getObject("actor_id", UUID.class), row.getString("reason"), row.getString("policy_version"),
            row.getObject("created_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC));
}
