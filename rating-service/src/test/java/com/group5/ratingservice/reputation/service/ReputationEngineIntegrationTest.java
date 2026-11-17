package com.group5.ratingservice.reputation.service;

import com.group5.ratingservice.reputation.backfill.ReputationBackfill;
import com.group5.ratingservice.reputation.config.ReputationProperties;
import com.group5.ratingservice.reputation.dto.AdjustmentResult;
import com.group5.ratingservice.reputation.dto.ReputationSnapshot;
import com.group5.ratingservice.reputation.enums.TrustLevel;
import com.group5.ratingservice.reputation.event.ReputationEventConsumer;
import com.group5.ratingservice.reputation.event.ReputationInputs;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.ratingservice.shared.RatingPostgresIntegrationTest;
import com.group5.ratingservice.eligibility.event.OrderCompletedConsumer;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.config.name=rating-service-test", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=rating-test", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1/.well-known/jwks.json"
})
@Import(ReputationEngineIntegrationTest.Config.class)
class ReputationEngineIntegrationTest extends RatingPostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final OffsetDateTime COMPLETED = OffsetDateTime.parse("2026-11-01T10:00:00.123456Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReputationService reputations;
    @Autowired private ReputationEventConsumer consumer;
    @Autowired private OrderCompletedConsumer projection;
    @Autowired private ReputationBackfill backfill;
    @Autowired private OutboxWriter outbox;
    @Autowired private ReputationProperties properties;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void clear() {
        reset(outbox);
        properties.setVersion("reputation-policy-1");
        jdbc.execute("TRUNCATE reputation_adjustment_requests,reputation_history,reputation_effects,reputation_sources,reputation_outcomes,ratings,rating_eligibility,user_reputations,outbox,processed_events");
    }

    @Test
    void completionAndRatingDeduplicateByBusinessSourceAcrossEventIdsAndIgnoreResultFeedback() {
        Order order = order(UUID.randomUUID());
        var original = completion(order, UUID.randomUUID(), 1);
        consumer.commerce(original);
        consumer.commerce(original);
        consumer.commerce(completion(order, UUID.randomUUID(), 2));
        assertThat(reputations.get(order.buyerId()).score()).isEqualTo(70);
        assertThat(reputations.get(order.sellerId()).successfulTransaction()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT successful_auction_count FROM user_reputations WHERE user_id=?",
                Integer.class, order.sellerId())).isEqualTo(1);
        assertThat(count("reputation_history")).isEqualTo(2);
        JsonNode parameters = JSON.readTree(jdbc.queryForObject("SELECT change_details::text FROM reputation_history LIMIT 1", String.class))
                .path("policy");
        assertThat(parameters.path("ratingWeight").asInt()).isEqualTo(30);
        assertThat(parameters.path("successWeight").asInt()).isEqualTo(20);
        assertThat(parameters.path("rounding").asString()).isEqualTo("HALF_UP");
        assertThat(parameters.path("minimumToBid").asInt()).isEqualTo(40);
        var rated = rating(order, UUID.randomUUID(), 2, UUID.randomUUID());
        consumer.rating(rated);
        consumer.rating(rated);
        consumer.rating(copy(rated, UUID.randomUUID(), rated.value().payload()));
        assertThat(reputations.get(order.sellerId()).ratingCount()).isEqualTo(1);
        assertThat(reputations.get(order.sellerId()).score()).isEqualTo(55);
        assertThat(reputations.get(order.buyerId()).score()).isEqualTo(70);
        long effects = count("reputation_history");
        var feedback = new EventEnvelope<>(UUID.randomUUID(), "ReputationScoreUpdated", order.sellerId().toString(),
                Instant.now(), "trace", JSON.readTree("{}"));
        consumer.rating(new ConsumerRecord<>(Topics.RATING, 0, 0, order.sellerId().toString(), feedback));
        assertThat(count("reputation_history")).isEqualTo(effects);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer=?",
                Long.class, ReputationEventConsumer.COMPLETION)).isEqualTo(2);
    }

    @Test
    void timeoutPenalizesOnlyWinnerOnceAcrossRevisionEventAndPolicyChanges() {
        Order order = order(UUID.randomUUID());
        var event = timeout(order, UUID.randomUUID(), 1);
        consumer.auction(event);
        consumer.auction(event);
        properties.setVersion("reputation-policy-2");
        consumer.auction(timeout(order, UUID.randomUUID(), 99));
        ReputationSnapshot winner = reputations.get(order.buyerId());
        assertThat(winner.score()).isEqualTo(40);
        assertThat(winner.failedTransaction()).isEqualTo(1);
        assertThat(winner.totalTransaction()).isEqualTo(1);
        assertThat(winner.trustLevel()).isEqualTo(TrustLevel.BASIC);
        assertThat(winner.policyVersion()).isEqualTo("reputation-policy-1");
        assertThat(reputations.get(order.sellerId()).score()).isEqualTo(50);
        assertThat(count("user_reputations")).isEqualTo(1);
        assertThat(count("reputation_history")).isEqualTo(1);
        JsonNode penalty = JSON.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox WHERE event_type='ReputationPenaltyApplied'", String.class)).path("payload");
        assertThat(penalty.path("penaltyPoints").asInt()).isEqualTo(10);
        assertThat(penalty.path("delta").asInt()).isEqualTo(-10);
        assertThat(penalty.path("reason").asString()).isEqualTo("PAYMENT_DEADLINE_EXPIRED");
        assertThat(penalty.path("userId").asString()).isEqualTo(order.buyerId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id<>? OR topic<>?", Long.class,
                order.buyerId().toString(), Topics.RATING)).isZero();
    }

    @Test
    void sameAuctionCanTimeoutTwoWinnersThenCompleteAnotherOffer() {
        Order first=order(UUID.randomUUID());
        Order second=new Order(UUID.randomUUID(),UUID.randomUUID(),first.sellerId(),first.auctionId());
        Order third=new Order(UUID.randomUUID(),UUID.randomUUID(),first.sellerId(),first.auctionId());
        consumer.auction(timeout(first,UUID.randomUUID(),1));
        consumer.auction(timeout(second,UUID.randomUUID(),2));
        consumer.auction(timeout(second,UUID.randomUUID(),3));
        consumer.commerce(completion(third,UUID.randomUUID(),1));
        assertThat(reputations.get(first.buyerId()).failedTransaction()).isEqualTo(1);
        assertThat(reputations.get(second.buyerId()).failedTransaction()).isEqualTo(1);
        assertThat(reputations.get(third.buyerId()).successfulTransaction()).isEqualTo(1);
        assertThat(reputations.get(first.sellerId()).failedTransaction()).isZero();
        assertThat(reputations.get(first.sellerId()).successfulTransaction()).isEqualTo(1);
        assertThat(count("reputation_outcomes")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE change_type='AUCTION_PAYMENT_TIMEOUT'",Long.class)).isEqualTo(2);
    }

    @Test
    void timeoutAtTheFloorRecordsActualDeltaAndZeroDeltaHistory() {
        Order order = order(UUID.randomUUID());
        reputations.adjust(order.buyerId(), -45, "Verified correction", UUID.randomUUID(), null);
        consumer.auction(timeout(order, UUID.randomUUID(), 1));
        assertThat(reputations.get(order.buyerId()).score()).isZero();
        assertThat(reputations.history(order.buyerId(), 0, 10).getContent().getFirst().delta()).isEqualTo(-5);
        Order another = new Order(UUID.randomUUID(), order.buyerId(), UUID.randomUUID(), UUID.randomUUID());
        consumer.auction(timeout(another, UUID.randomUUID(), 1));
        assertThat(reputations.history(order.buyerId(), 0, 10).getContent().getFirst().delta()).isZero();
        assertThat(reputations.get(order.buyerId()).failedTransaction()).isEqualTo(2);
    }

    @Test
    void adjustmentIsExactAtSaturationSurvivesRecomputeAndCannotHideTimeoutPenalty() {
        UUID target = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        reputations.adjust(target, 50, "Manual boost", actor, "boost");
        Order favorable = new Order(UUID.randomUUID(), UUID.randomUUID(), target, null);
        consumer.commerce(completion(favorable, UUID.randomUUID(), 1));
        consumer.rating(rating(favorable, UUID.randomUUID(), 5, UUID.randomUUID()));
        assertThat(reputations.get(target).score()).isEqualTo(100);
        assertThat(reputations.adjust(target, -10, "Exact reduction", actor, "reduce").reputation().score()).isEqualTo(90);
        Order anotherSuccess = new Order(UUID.randomUUID(), UUID.randomUUID(), target, null);
        consumer.commerce(completion(anotherSuccess, UUID.randomUUID(), 1));
        assertThat(reputations.get(target).score()).isEqualTo(90);
        reputations.adjust(target, 10, "Restore full score", actor, null);
        Order penalty = new Order(UUID.randomUUID(), target, UUID.randomUUID(), UUID.randomUUID());
        consumer.auction(timeout(penalty, UUID.randomUUID(), 1));
        assertThat(reputations.get(target).score()).isLessThanOrEqualTo(90);
        assertThat(reputations.history(target, 0, 20).getContent().getFirst().delta()).isLessThanOrEqualTo(-10);
    }

    @Test
    void adminIdempotencyReplaysOriginalResultAndRejectsDifferentActorPayloadOrBounds() {
        UUID user = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        AdjustmentResult first = reputations.adjust(user, 10, "Reason", actor, "stable-key");
        reputations.adjust(user, 5, "Later change", actor, null);
        AdjustmentResult replay = reputations.adjust(user, 10, "Reason", actor, "stable-key");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(replay.reputation().score()).isEqualTo(60);
        assertThat(reputations.get(user).score()).isEqualTo(65);
        assertThatThrownBy(() -> reputations.adjust(user, 11, "Reason", actor, "stable-key"))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RATING_IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> reputations.adjust(user, 10, "Reason", UUID.randomUUID(), "stable-key"))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RATING_IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> reputations.adjust(user, 36, "Beyond bound", actor, null))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RATING_ADJUSTMENT_OUT_OF_RANGE"));
        assertThatThrownBy(() -> reputations.adjust(user, -66, "Below bound", actor, null)).isInstanceOf(DomainException.class);
        assertThat(count("reputation_history")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT created_by FROM user_reputations WHERE user_id=?", UUID.class, user)).isEqualTo(actor);
    }

    @Test
    void aPriorBoostCannotHideTheVisibleTimeoutPenaltyBehindSaturation() {
        UUID user = UUID.randomUUID();
        reputations.adjust(user, 50, "Verified boost", UUID.randomUUID(), null);
        Order successful = new Order(UUID.randomUUID(), UUID.randomUUID(), user, null);
        consumer.commerce(completion(successful, UUID.randomUUID(), 1));
        consumer.rating(rating(successful, UUID.randomUUID(), 5, UUID.randomUUID()));
        assertThat(reputations.get(user).score()).isEqualTo(100);
        consumer.auction(timeout(new Order(UUID.randomUUID(), user, UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID(), 1));
        assertThat(reputations.get(user).score()).isEqualTo(90);
        assertThat(reputations.history(user, 0, 10).getContent().getFirst().delta()).isEqualTo(-10);
    }

    @Test
    void conflictingCompletionRatingAndCanonicalOutcomeRollBackTheirProcessedMarkers() {
        Order order = order(UUID.randomUUID());
        consumer.commerce(completion(order, UUID.randomUUID(), 1));
        UUID rejected = UUID.randomUUID();
        Order altered = new Order(order.id(), UUID.randomUUID(), order.sellerId(), order.auctionId());
        assertThatThrownBy(() -> consumer.commerce(completion(altered, rejected, 2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> consumer.auction(timeout(order, UUID.randomUUID(), 1))).isInstanceOf(DomainException.class);
        var rating = rating(order, UUID.randomUUID(), 5, UUID.randomUUID());
        consumer.rating(rating);
        JsonNode alteredRating = rating(order, UUID.fromString(rating.value().aggregateId()), 1, UUID.randomUUID()).value().payload();
        assertThatThrownBy(() -> consumer.rating(copy(rating, UUID.randomUUID(), alteredRating))).isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?", Long.class, rejected)).isZero();
        assertThat(reputations.get(order.sellerId()).ratingCount()).isEqualTo(1);
        assertThat(reputations.get(order.buyerId()).failedTransaction()).isZero();
        assertThat(count("reputation_history")).isEqualTo(3);
    }

    @Test
    void outboxFailureRollsBackAggregateInputsHistorySourcesAndProcessedMarker() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Forced outbox failure");
        }).when(outbox).write(anyString(), anyString(), any());
        assertThatThrownBy(() -> consumer.commerce(completion(order(null), UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalStateException.class);
        for (String table : List.of("user_reputations", "reputation_history", "reputation_sources", "reputation_effects",
                "reputation_outcomes", "outbox", "processed_events")) assertThat(count(table)).isZero();
    }

    @Test
    void concurrentRatingCompletionTimeoutAndAdminPreserveEveryAcceptedInput() throws Exception {
        UUID user = UUID.randomUUID();
        Order success = new Order(UUID.randomUUID(), UUID.randomUUID(), user, null);
        Order failure = new Order(UUID.randomUUID(), user, UUID.randomUUID(), UUID.randomUUID());
        var actions = List.<Runnable>of(
                () -> consumer.commerce(completion(success, UUID.randomUUID(), 1)),
                () -> consumer.rating(rating(success, UUID.randomUUID(), 1, UUID.randomUUID())),
                () -> consumer.auction(timeout(failure, UUID.randomUUID(), 1)),
                () -> reputations.adjust(user, 0, "Concurrent audit", UUID.randomUUID(), null));
        concurrent(actions);
        ReputationSnapshot result = reputations.get(user);
        assertThat(result.ratingCount()).isEqualTo(1);
        assertThat(result.totalTransaction()).isEqualTo(2);
        assertThat(result.successfulTransaction()).isEqualTo(1);
        assertThat(result.failedTransaction()).isEqualTo(1);
        assertThat(result.score()).isEqualTo(20);
        assertThat(reputations.history(user, 0, 20).getTotalElements()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type='ReputationScoreUpdated' AND aggregate_id=?",
                Long.class, user.toString())).isEqualTo(4);
    }

    @Test
    void backfillUsesOldEligibilityDespiteProcessedMarkerAndReplaysWithoutFakeEventsOrDoubleCounts() {
        Order order = order(null);
        var old = completion(order, UUID.randomUUID(), 1);
        projection.receive(old);
        UUID ratingId = jdbc.queryForObject("""
                INSERT INTO ratings (from_user_id,to_user_id,order_id,score) VALUES (?,?,?,5) RETURNING id
                """, UUID.class, order.buyerId(), order.sellerId(), order.id());
        ReputationBackfill.Report dryRun = backfill.batch(null, null, 100, true);
        assertThat(dryRun.scannedCompletions()).isEqualTo(1);
        assertThat(dryRun.scannedRatings()).isEqualTo(1);
        assertThat(count("user_reputations")).isZero();
        ReputationBackfill.Report report = backfill.batch(null, null, 100, false);
        assertThat(report.appliedCompletions()).isEqualTo(1);
        assertThat(report.appliedRatings()).isEqualTo(1);
        assertThat(reputations.get(order.sellerId()).score()).isEqualTo(100);
        assertThat(backfill.batch(null, null, 100, false).appliedRatings()).isZero();
        consumer.commerce(old);
        OffsetDateTime ratedAt = jdbc.queryForObject("SELECT created_at FROM ratings WHERE id=?",
                (row, index) -> row.getObject(1, OffsetDateTime.class), ratingId);
        var input = new ReputationInputs.RatingInput(ratingId, order.id(), null, order.buyerId(), order.sellerId(), 5,
                0, order.buyerId(), ratedAt);
        consumer.rating(record(Topics.RATING, "UserRated", ratingId, order.sellerId(), UUID.randomUUID(), input));
        assertThat(count("reputation_history")).isEqualTo(3);
        assertThat(reputations.get(order.sellerId()).ratingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type='UserRated'", Long.class)).isZero();
    }

    @Test
    void backfillAndLiveCompletionRaceAndCursorResumeRemainIdempotent() throws Exception {
        Order first = order(null);
        Order second = order(null);
        var one = completion(first, UUID.randomUUID(), 1);
        var two = completion(second, UUID.randomUUID(), 1);
        projection.receive(one);
        projection.receive(two);
        concurrent(List.of(() -> backfill.batch(null, null, 1, false), () -> consumer.commerce(one)));
        ReputationBackfill.Report initial = backfill.batch(null, null, 1, false);
        backfill.batch(initial.completionCursor(), initial.ratingCursor(), 1, false);
        assertThat(reputations.get(first.buyerId()).successfulTransaction()).isEqualTo(1);
        assertThat(reputations.get(second.buyerId()).successfulTransaction()).isEqualTo(1);
        assertThat(count("reputation_history")).isEqualTo(4);
    }

    @Test
    void unverifiedLegacyDataIsReportedAndUnknownAggregatesArePreservedForReconciliation() {
        jdbc.update("INSERT INTO ratings (from_user_id,to_user_id,score) VALUES (?,?,3)", UUID.randomUUID(), UUID.randomUUID());
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_reputations (user_id,score,total_transaction) VALUES (?,30,2)", user);
        var report = backfill.batch(null, null, 100, true);
        assertThat(report.unverifiedRatings()).isEqualTo(1);
        assertThat(report.unverifiedAggregates()).isEqualTo(1);
        assertThatThrownBy(() -> backfill.batch(null, null, 100, false)).isInstanceOf(IllegalStateException.class);
        assertThat(reputations.get(user).score()).isEqualTo(30);
        assertThat(count("reputation_history")).isZero();
    }

    @Test
    void liveReplayAndAdminCannotBypassTheLegacyReconciliationFence() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_reputations (user_id,total_transaction,successful_transaction) VALUES (?,10,10)", user);
        Order historical = new Order(UUID.randomUUID(), UUID.randomUUID(), user, null);
        assertThatThrownBy(() -> consumer.commerce(completion(historical, UUID.randomUUID(), 1)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> reputations.adjust(user, 1, "Not a reconciliation", UUID.randomUUID(), null))
                .isInstanceOf(DomainException.class);
        assertThat(reputations.get(user).totalTransaction()).isEqualTo(10);
        assertThat(backfill.batch(null, null, 100, true).unverifiedAggregates()).isEqualTo(1);
        for (String table : List.of("reputation_history", "reputation_sources", "reputation_effects", "reputation_outcomes",
                "reputation_adjustment_requests", "processed_events", "outbox")) assertThat(count(table)).isZero();
    }

    @Test
    void historyFollowsAppliedChangesWhenCompletionTransactionStartedBeforeAdminAdjustment() throws Exception {
        UUID user = UUID.randomUUID();
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), user, null);
        CountDownLatch completionStarted = new CountDownLatch(1);
        CountDownLatch adminCommitted = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var completion = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                OffsetDateTime startedAt = jdbc.queryForObject("SELECT current_timestamp", OffsetDateTime.class);
                completionStarted.countDown();
                try {
                    if (!adminCommitted.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Admin adjustment did not commit in time");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                consumer.commerce(completion(order, UUID.randomUUID(), 1));
                return startedAt;
            }));
            OffsetDateTime completionStartedAt;
            try {
                assertThat(completionStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(reputations.adjust(user, 10, "Reviewed adjustment", UUID.randomUUID(), null)
                        .reputation().score()).isEqualTo(60);
            } finally {
                adminCommitted.countDown();
            }
            completionStartedAt = completion.get(10, TimeUnit.SECONDS);
            var history = reputations.history(user, 0, 20).getContent();
            assertThat(reputations.get(user).score()).isEqualTo(80);
            assertThat(history).extracting(item -> item.changeType())
                    .containsExactly("ORDER_COMPLETED", "ADMIN_ADJUSTMENT");
            assertThat(history).extracting(item -> item.previousScore()).containsExactly(60, 50);
            assertThat(history).extracting(item -> item.newScore()).containsExactly(80, 60);
            assertThat(history.getFirst().changedAt().toInstant())
                    .isAfter(history.getLast().changedAt().toInstant());
            assertThat(history.getLast().changedAt().toInstant()).isAfter(completionStartedAt.toInstant());
            JsonNode scoreEvent = JSON.readTree(jdbc.queryForObject("""
                    SELECT payload::text FROM outbox WHERE event_type='ReputationScoreUpdated'
                      AND aggregate_id=? AND payload->'payload'->>'changeType'='ORDER_COMPLETED'
                    """, String.class, user.toString())).path("payload");
            assertThat(OffsetDateTime.parse(scoreEvent.path("changedAt").asString()).toInstant())
                    .isEqualTo(history.getFirst().changedAt().toInstant());
        }
    }

    private void concurrent(List<Runnable> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(actions.size())) {
            var results = new ArrayList<java.util.concurrent.Future<?>>();
            for (Runnable action : actions) results.add(workers.submit(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    action.run();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }));
            start.countDown();
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }
    }

    private static Order order(UUID auction) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), auction);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> completion(Order order, UUID eventId, long version) {
        var input = new LinkedHashMap<String, Object>();
        input.put("orderId", order.id());
        input.put("orderGroupId", UUID.randomUUID());
        input.put("buyerId", order.buyerId());
        input.put("sellerId", order.sellerId());
        input.put("orderType", order.auctionId() == null ? "STANDARD" : "AUCTION");
        input.put("referenceId", order.auctionId());
        input.put("orderVersion", version);
        input.put("completedAt", COMPLETED);
        return record(Topics.COMMERCE, "OrderCompleted", order.id(), order.id(), eventId, input);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> rating(Order order, UUID ratingId, int score, UUID eventId) {
        var input = new ReputationInputs.RatingInput(ratingId, order.id(), order.auctionId(), order.buyerId(), order.sellerId(),
                score, 0, order.buyerId(), COMPLETED.plusHours(1));
        return record(Topics.RATING, "UserRated", ratingId, order.sellerId(), eventId, input);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> timeout(Order order, UUID eventId, long version) {
        var input = new ReputationInputs.Timeout(order.auctionId(), order.id(), order.buyerId(), order.sellerId(),
                COMPLETED, COMPLETED.plusMinutes(1), "PAYMENT_DEADLINE_EXPIRED", version);
        return record(Topics.AUCTION, "AuctionPaymentTimeout", order.auctionId(), order.auctionId(), eventId, input);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> record(String topic, String type, UUID aggregate,
            UUID key, UUID eventId, Object input) {
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(eventId, type, aggregate.toString(), Instant.now(), "213engine", JSON.valueToTree(input));
        return new ConsumerRecord<>(topic, 0, 0, key.toString(), envelope);
    }

    private static ConsumerRecord<String, EventEnvelope<JsonNode>> copy(ConsumerRecord<String, EventEnvelope<JsonNode>> original,
            UUID eventId, JsonNode payload) {
        var event = original.value();
        return new ConsumerRecord<>(original.topic(), 0, 0, original.key(), new EventEnvelope<>(eventId, event.eventType(),
                event.aggregateId(), event.occurredAt(), event.traceId(), payload));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private record Order(UUID id, UUID buyerId, UUID sellerId, UUID auctionId) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean EventEnvelopeFactory envelopes() {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("21300000000000000000000000000000");
            return new EventEnvelopeFactory(tracer, Clock.systemUTC());
        }
        @Bean OutboxWriter outbox(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
            return spy(new OutboxWriter(jdbc, JSON, transactions));
        }
    }
}
