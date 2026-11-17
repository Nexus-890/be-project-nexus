package com.group5.ratingservice.reputation.service;

import com.group5.ratingservice.reputation.dto.AdjustmentResult;
import com.group5.ratingservice.reputation.dto.ReputationHistoryItem;
import com.group5.ratingservice.reputation.dto.ReputationSnapshot;
import com.group5.ratingservice.reputation.enums.TrustLevel;
import com.group5.ratingservice.reputation.event.ReputationInputs;
import com.group5.ratingservice.reputation.model.ReputationState;
import com.group5.ratingservice.reputation.policy.ReputationPolicy;
import com.group5.ratingservice.reputation.repository.ReputationStore;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.ratingservice.shared.exception.RatingErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class ReputationService {
    private static final UUID SYSTEM = new UUID(0, 0);
    private final ReputationStore store;
    private final ReputationPolicy policy;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    @Transactional(readOnly = true)
    public ReputationSnapshot get(UUID userId) {
        return store.find(Objects.requireNonNull(userId)).map(state -> state.snapshot(policy.version()))
                .orElseGet(() -> new ReputationSnapshot(userId, 50, TrustLevel.NEW, 0, 0, 0, 0, policy.version()));
    }

    @Transactional(readOnly = true)
    public ReputationSnapshot eligibility(UUID userId) {
        return store.find(Objects.requireNonNull(userId)).map(state -> {
            requireReconciled(state);
            return state.snapshot(policy.version());
        }).orElseGet(() -> new ReputationSnapshot(userId, 50, TrustLevel.NEW, 0, 0, 0, 0, policy.version()));
    }

    @Transactional(readOnly = true)
    public Page<ReputationHistoryItem> history(UUID userId, int page, int size) {
        return new PageImpl<>(store.histories(userId, page, size), PageRequest.of(page, size), store.historyCount(userId));
    }

    @Transactional
    public AdjustmentResult adjust(UUID userId, int delta, String reason, UUID actor, String idempotencyKey) {
        if (userId == null || actor == null || reason == null || reason.isBlank() || reason.length() > 1000
                || idempotencyKey != null && !idempotencyKey.matches("[\\x21-\\x7e]{1,128}")) {
            throw new DomainException(RatingErrorCode.INVALID_PARAMETER);
        }
        String normalizedReason = reason.strip();
        Map<String, Object> fingerprint = Map.of("delta", delta, "reason", normalizedReason, "actorId", actor);
        ReputationState state = store.lock(userId, actor);
        requireReconciled(state);
        if (idempotencyKey != null) {
            var replay = store.adjustment(userId, idempotencyKey);
            if (replay.isPresent()) {
                if (!store.sameFingerprint(replay.get(), fingerprint)) {
                    throw new DomainException(RatingErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return new AdjustmentResult((UUID) replay.get().get("operation_id"),
                        JsonMapper.shared().readValue(replay.get().get("result").toString(), ReputationSnapshot.class), true);
            }
        }
        long target = (long) state.getScore() + delta;
        if (target < 0 || target > 100) throw new DomainException(RatingErrorCode.ADJUSTMENT_OUT_OF_RANGE);
        UUID operation = UUID.randomUUID();
        ReputationSnapshot before = state.snapshot(policy.version());
        var previousInputs = state.inputs();
        state.setAdjustmentOffset(BigDecimal.valueOf(target).subtract(policy.rawBase(state)));
        commit(state, before, previousInputs, operation, "ADMIN_ADJUSTMENT", null, null, null, actor, normalizedReason, null);
        ReputationSnapshot result = state.snapshot(policy.version());
        if (idempotencyKey != null) store.adjustment(userId, idempotencyKey, operation, fingerprint, result);
        return new AdjustmentResult(operation, result, false);
    }

    @Transactional
    public int complete(ReputationInputs.Completion input, UUID eventId) {
        Map<String, Object> fingerprint = input.fingerprint();
        store.outcome(input.orderId(), input.auctionId(), "COMPLETED", fingerprint);
        store.source("ORDER_COMPLETED", input.orderId(), fingerprint);
        UUID operation = operation("ORDER_COMPLETED", input.orderId());
        List<ReputationState> users = List.of(input.buyerId(), input.sellerId()).stream().sorted()
                .map(store::lock).toList();
        int applied = 0;
        for (var user : users) {
            if (!store.claim("ORDER_COMPLETED", input.orderId(), user.getUserId(), operation, fingerprint)) continue;
            change(user, operation, "ORDER_COMPLETED", input.orderId(), input.sourceVersion(), eventId, SYSTEM,
                    "Canonical order completed", state -> {
                        state.setTotalTransaction(Math.incrementExact(state.getTotalTransaction()));
                        state.setSuccessfulTransaction(Math.incrementExact(state.getSuccessfulTransaction()));
                        if (input.auctionId() != null) state.setSuccessfulAuctionCount(Math.incrementExact(state.getSuccessfulAuctionCount()));
                    }, null);
            applied++;
        }
        return applied;
    }

    @Transactional
    public boolean rate(ReputationInputs.RatingInput input, UUID eventId) {
        var fingerprint = input.fingerprint();
        store.source("USER_RATED", input.ratingId(), fingerprint);
        ReputationState state = store.lock(input.toUserId());
        UUID operation = operation("USER_RATED", input.ratingId());
        if (!store.claim("USER_RATED", input.ratingId(), input.toUserId(), operation, fingerprint)) return false;
        change(state, operation, "USER_RATED", input.ratingId(), 0L, eventId, SYSTEM, "Completed-order user rating",
                aggregate -> {
                    aggregate.setRatingCount(Math.incrementExact(aggregate.getRatingCount()));
                    aggregate.setRatingScoreSum(Math.addExact(aggregate.getRatingScoreSum(), input.score()));
                }, null);
        return true;
    }

    @Transactional
    public boolean timeout(ReputationInputs.Timeout input, UUID eventId) {
        var fingerprint = input.fingerprint();
        store.outcome(input.orderId(), input.auctionId(), "PAYMENT_TIMEOUT", fingerprint);
        store.source("AUCTION_PAYMENT_TIMEOUT", input.auctionId(), fingerprint);
        ReputationState state = store.lock(input.winnerId());
        UUID operation = operation("AUCTION_PAYMENT_TIMEOUT", input.auctionId());
        if (!store.claim("AUCTION_PAYMENT_TIMEOUT", input.auctionId(), input.winnerId(), operation, fingerprint)) return false;
        int penalty = policy.timeoutPenalty();
        change(state, operation, "AUCTION_PAYMENT_TIMEOUT", input.auctionId(), input.auctionVersion(), eventId, SYSTEM,
                "Auction payment deadline expired", aggregate -> {
                    aggregate.setPenaltyPoints(Math.addExact(aggregate.getPenaltyPoints(), penalty));
                    aggregate.setTotalTransaction(Math.incrementExact(aggregate.getTotalTransaction()));
                    aggregate.setFailedTransaction(Math.incrementExact(aggregate.getFailedTransaction()));
                    aggregate.setFailedAuctionCount(Math.incrementExact(aggregate.getFailedAuctionCount()));
                    int maximum = Math.max(0, aggregate.getScore() - penalty);
                    if (policy.score(aggregate) > maximum) {
                        aggregate.setAdjustmentOffset(BigDecimal.valueOf(maximum).subtract(policy.rawBase(aggregate)));
                    }
                }, penalty);
        return true;
    }

    private void change(ReputationState state, UUID operation, String type, UUID source, Long sourceVersion,
            UUID event, UUID actor, String reason, Consumer<ReputationState> update, Integer penalty) {
        requireReconciled(state);
        ReputationSnapshot before = state.snapshot(policy.version());
        var previousInputs = state.inputs();
        update.accept(state);
        commit(state, before, previousInputs, operation, type, source, sourceVersion, event, actor, reason, penalty);
    }

    private void commit(ReputationState state, ReputationSnapshot before, Map<String, ?> previousInputs,
            UUID operation, String type, UUID source, Long sourceVersion, UUID event, UUID actor, String reason, Integer penalty) {
        state.setScore(policy.score(state));
        state.setTrustLevel(policy.level(state.getScore(), state));
        state.setPolicyVersion(policy.version());
        store.save(state, actor);
        var details = new LinkedHashMap<String, Object>();
        details.put("schemaVersion", 1);
        details.put("before", previousInputs);
        details.put("after", state.inputs());
        details.put("policy", policy.parameters());
        ReputationHistoryItem history = store.history(state, before, operation, type, source, sourceVersion, event, actor, reason, details);
        var scoreEvent = new LinkedHashMap<String, Object>();
        scoreEvent.put("historyId", history.id());
        scoreEvent.put("userId", state.getUserId());
        scoreEvent.put("operationId", operation);
        scoreEvent.put("sourceId", source);
        scoreEvent.put("sourceVersion", sourceVersion);
        scoreEvent.put("changeType", type);
        scoreEvent.put("policyVersion", state.getPolicyVersion());
        scoreEvent.put("previousScore", before.score());
        scoreEvent.put("newScore", state.getScore());
        scoreEvent.put("delta", state.getScore() - before.score());
        scoreEvent.put("actorId", actor);
        scoreEvent.put("changedAt", history.changedAt());
        publish("ReputationScoreUpdated", state.getUserId(), scoreEvent);
        if (penalty != null) {
            var penaltyEvent = new LinkedHashMap<>(scoreEvent);
            penaltyEvent.put("penaltyPoints", penalty);
            penaltyEvent.put("reason", "PAYMENT_DEADLINE_EXPIRED");
            publish("ReputationPenaltyApplied", state.getUserId(), penaltyEvent);
        }
        if (before.trustLevel() != state.getTrustLevel()) {
            publish("TrustLevelChanged", state.getUserId(), Map.of("historyId", history.id(), "userId", state.getUserId(),
                    "operationId", operation, "previousLevel", before.trustLevel(), "newLevel", state.getTrustLevel(),
                    "score", state.getScore(), "policyVersion", state.getPolicyVersion(), "actorId", actor, "changedAt", history.changedAt()));
        }
    }

    private void publish(String type, UUID userId, Map<String, ?> payload) {
        outbox.write("UserReputation", Topics.RATING, envelopes.create(type, userId.toString(), payload));
    }

    private static UUID operation(String type, UUID source) {
        return UUID.nameUUIDFromBytes((type + ":" + source).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireReconciled(ReputationState state) {
        if (state.getPolicyVersion() == null && (state.getScore() != 50 || state.getTrustLevel() != TrustLevel.NEW
                || state.getTotalTransaction() != 0 || state.getSuccessfulTransaction() != 0 || state.getFailedTransaction() != 0
                || state.getRatingCount() != 0 || state.getRatingScoreSum() != 0 || state.getPenaltyPoints() != 0
                || state.getSuccessfulAuctionCount() != 0 || state.getFailedAuctionCount() != 0
                || state.getAdjustmentOffset().signum() != 0)) {
            throw new DomainException(RatingErrorCode.RECONCILIATION_REQUIRED);
        }
    }
}
