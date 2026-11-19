package com.group5.ratingservice.reputation.backfill;

import com.group5.ratingservice.reputation.event.ReputationInputs;
import com.group5.ratingservice.reputation.service.ReputationService;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReputationBackfill {
    private final JdbcTemplate jdbc;
    private final ReputationService reputations;
    private final Tracer tracer;

    public Report batch(UUID completionCursor, UUID ratingCursor, int size, boolean dryRun) {
        if (size < 1 || size > 1000) throw new IllegalArgumentException("Backfill batch size must be between 1 and 1000");
        long legacyAggregates = jdbc.queryForObject("""
                SELECT count(*) FROM user_reputations WHERE policy_version IS NULL
                  AND (score <> 50 OR trust_level <> 'NEW'::trust_level OR total_transaction <> 0
                    OR successful_transaction <> 0 OR failed_transaction <> 0 OR rating_count <> 0
                    OR rating_score_sum <> 0 OR penalty_points <> 0 OR successful_auction_count <> 0
                    OR failed_auction_count <> 0 OR adjustment_offset <> 0)
                """, Long.class);
        if (!dryRun && legacyAggregates > 0) {
            throw new IllegalStateException("Legacy reputation aggregates require evidence reconciliation before backfill");
        }
        var completions = jdbc.query("""
                SELECT * FROM rating_eligibility WHERE (?::uuid IS NULL OR order_id > ?::uuid)
                ORDER BY order_id LIMIT ?
                """, (row, index) -> new CompletionCandidate(new ReputationInputs.Completion(
                row.getObject("order_id", UUID.class), row.getObject("buyer_id", UUID.class), row.getObject("seller_id", UUID.class),
                row.getObject("auction_id", UUID.class), row.getObject("completed_at", OffsetDateTime.class),
                row.getObject("source_version", Long.class)), row.getObject("source_event_id", UUID.class)),
                completionCursor, completionCursor, size);
        var ratings = jdbc.query("""
                SELECT r.*, e.buyer_id, e.seller_id, e.auction_id AS eligible_auction_id, e.completed_at
                FROM ratings r LEFT JOIN rating_eligibility e ON e.order_id=r.order_id
                WHERE NOT r.hidden AND (?::uuid IS NULL OR r.id > ?::uuid) ORDER BY r.id LIMIT ?
                """, (row, index) -> new RatingCandidate(row.getObject("id", UUID.class),
                row.getObject("order_id", UUID.class), row.getObject("auction_id", UUID.class),
                row.getObject("from_user_id", UUID.class), row.getObject("to_user_id", UUID.class), row.getInt("score"),
                row.getObject("created_at", OffsetDateTime.class), row.getObject("buyer_id", UUID.class),
                row.getObject("seller_id", UUID.class), row.getObject("eligible_auction_id", UUID.class),
                row.getObject("completed_at", OffsetDateTime.class)), ratingCursor, ratingCursor, size);
        int appliedCompletions = 0;
        int appliedRatings = 0;
        int unverifiedRatings = 0;
        var span = tracer.nextSpan().name("reputation-backfill").start();
        try (var scope = tracer.withSpan(span)) {
            if (!dryRun) {
                for (var completion : completions) {
                    if (reputations.complete(completion.input(), completion.sourceEventId()) > 0) appliedCompletions++;
                }
            }
            for (var rating : ratings) {
                if (!rating.verified()) {
                    unverifiedRatings++;
                } else if (!dryRun && reputations.rate(rating.input(), null)) {
                    appliedRatings++;
                }
            }
        } finally {
            span.end();
        }
        UUID nextCompletion = completions.isEmpty() ? completionCursor : completions.getLast().input().orderId();
        UUID nextRating = ratings.isEmpty() ? ratingCursor : ratings.getLast().id();
        return new Report(dryRun, completions.size(), ratings.size(), appliedCompletions, appliedRatings,
                unverifiedRatings, legacyAggregates, nextCompletion, nextRating,
                completions.size() == size, ratings.size() == size);
    }

    public record Report(boolean dryRun, int scannedCompletions, int scannedRatings, int appliedCompletions,
            int appliedRatings, int unverifiedRatings, long unverifiedAggregates, UUID completionCursor,
            UUID ratingCursor, boolean moreCompletions, boolean moreRatings) { }

    private record CompletionCandidate(ReputationInputs.Completion input, UUID sourceEventId) { }

    private record RatingCandidate(UUID id, UUID orderId, UUID auctionId, UUID fromUserId, UUID toUserId, int score,
            OffsetDateTime ratedAt, UUID buyerId, UUID sellerId, UUID eligibleAuctionId, OffsetDateTime completedAt) {
        boolean verified() {
            return orderId != null && buyerId != null && sellerId != null && completedAt != null
                    && !ratedAt.isBefore(completedAt) && Objects.equals(auctionId, eligibleAuctionId)
                    && (fromUserId.equals(buyerId) && toUserId.equals(sellerId)
                    || fromUserId.equals(sellerId) && toUserId.equals(buyerId));
        }

        ReputationInputs.RatingInput input() {
            return new ReputationInputs.RatingInput(id, orderId, auctionId, fromUserId, toUserId, score, 0, fromUserId, ratedAt);
        }
    }
}
