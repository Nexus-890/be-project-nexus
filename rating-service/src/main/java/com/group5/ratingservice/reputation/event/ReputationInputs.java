package com.group5.ratingservice.reputation.event;

import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ReputationInputs {
    private ReputationInputs() { }

    public record Completion(UUID orderId, UUID buyerId, UUID sellerId, UUID auctionId,
            OffsetDateTime completedAt, Long sourceVersion) {
        public Completion {
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(buyerId);
            Objects.requireNonNull(sellerId);
            completedAt = utc(Objects.requireNonNull(completedAt));
            if (buyerId.equals(sellerId) || sourceVersion != null && sourceVersion < 0) {
                throw new IllegalArgumentException("Invalid completed-order reputation input");
            }
        }

        public Map<String, Object> fingerprint() {
            var values = new LinkedHashMap<String, Object>();
            values.put("orderId", orderId);
            values.put("buyerId", buyerId);
            values.put("sellerId", sellerId);
            values.put("auctionId", auctionId);
            values.put("completedAt", completedAt);
            return values;
        }
    }

    public record RatingInput(UUID ratingId, UUID orderId, UUID auctionId, UUID fromUserId, UUID toUserId,
            int score, long sourceVersion, UUID actorId, OffsetDateTime ratedAt) {
        public RatingInput {
            Objects.requireNonNull(ratingId);
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(fromUserId);
            Objects.requireNonNull(toUserId);
            Objects.requireNonNull(actorId);
            ratedAt = utc(Objects.requireNonNull(ratedAt));
            if (fromUserId.equals(toUserId) || !actorId.equals(fromUserId) || score < 1 || score > 5 || sourceVersion != 0) {
                throw new IllegalArgumentException("Invalid UserRated reputation input");
            }
        }

        public static RatingInput from(JsonNode node) {
            return new RatingInput(uuid(node, "ratingId"), uuid(node, "orderId"), nullableUuid(node, "auctionId"),
                    uuid(node, "fromUserId"), uuid(node, "toUserId"), Math.toIntExact(integer(node, "score")),
                    integer(node, "sourceVersion"), uuid(node, "actorId"), timestamp(node, "ratedAt"));
        }

        public Map<String, Object> fingerprint() {
            var values = new LinkedHashMap<String, Object>();
            values.put("ratingId", ratingId);
            values.put("orderId", orderId);
            values.put("auctionId", auctionId);
            values.put("fromUserId", fromUserId);
            values.put("toUserId", toUserId);
            values.put("score", score);
            values.put("sourceVersion", sourceVersion);
            values.put("actorId", actorId);
            values.put("ratedAt", ratedAt);
            return values;
        }
    }

    public record Timeout(UUID auctionId, UUID orderId, UUID winnerId, UUID sellerId, OffsetDateTime paymentDueAt,
            OffsetDateTime timedOutAt, String reason, long auctionVersion) {
        public Timeout {
            Objects.requireNonNull(auctionId);
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(winnerId);
            Objects.requireNonNull(sellerId);
            paymentDueAt = utc(Objects.requireNonNull(paymentDueAt));
            timedOutAt = utc(Objects.requireNonNull(timedOutAt));
            if (winnerId.equals(sellerId) || timedOutAt.isBefore(paymentDueAt)
                    || !"PAYMENT_DEADLINE_EXPIRED".equals(reason) || auctionVersion < 0) {
                throw new IllegalArgumentException("Invalid AuctionPaymentTimeout reputation input");
            }
        }

        public static Timeout from(JsonNode node) {
            return new Timeout(uuid(node, "auctionId"), uuid(node, "orderId"), uuid(node, "winnerId"),
                    uuid(node, "sellerId"), timestamp(node, "paymentDueAt"), timestamp(node, "timedOutAt"),
                    text(node, "reason"), integer(node, "auctionVersion"));
        }

        public Map<String, Object> fingerprint() {
            return Map.of("auctionId", auctionId, "orderId", orderId, "winnerId", winnerId, "sellerId", sellerId,
                    "paymentDueAt", paymentDueAt, "timedOutAt", timedOutAt, "reason", reason);
        }
    }

    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name);
        UUID result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID: " + name);
        return result;
    }

    private static UUID nullableUuid(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode()) throw new IllegalArgumentException("Missing " + name);
        return value.isNull() ? null : uuid(node, name);
    }

    private static String text(JsonNode node, String name) {
        if (node == null || !node.isObject() || !node.path(name).isString() || node.path(name).asString().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid " + name);
        }
        return node.path(name).asString();
    }

    private static long integer(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid " + name);
        return value.asLong();
    }

    private static OffsetDateTime timestamp(JsonNode node, String name) {
        String value = text(node, name);
        if (!value.endsWith("Z")) throw new IllegalArgumentException("UTC timestamp required: " + name);
        return OffsetDateTime.parse(value);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
