package com.group5.ratingservice.event;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** The OrderCompleted contract in docs/api/events.md; extra payload fields are ignored. */
public record OrderCompletedPayload(
        UUID orderId,
        UUID orderGroupId,
        UUID buyerId,
        UUID sellerId,
        String orderType,
        UUID referenceId,
        long orderVersion,
        OffsetDateTime completedAt
) {

    public OrderCompletedPayload {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderGroupId, "orderGroupId");
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(completedAt, "completedAt");
        if (buyerId.equals(sellerId)) {
            throw new IllegalArgumentException("OrderCompleted buyerId and sellerId must differ");
        }
        if (!"STANDARD".equals(orderType) && !"AUCTION".equals(orderType)) {
            throw new IllegalArgumentException("OrderCompleted orderType must be STANDARD or AUCTION");
        }
        if ("AUCTION".equals(orderType) != (referenceId != null)) {
            throw new IllegalArgumentException("OrderCompleted referenceId is required only for AUCTION");
        }
        if (orderVersion < 0) {
            throw new IllegalArgumentException("OrderCompleted orderVersion must be nonnegative");
        }
        // PostgreSQL timestamptz stores microseconds; normalize before insertion and replay comparison.
        completedAt = completedAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static OrderCompletedPayload from(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("OrderCompleted payload must be an object");
        }
        JsonNode reference = payload.path("referenceId");
        if (reference.isMissingNode()) {
            throw new IllegalArgumentException("OrderCompleted payload has no referenceId");
        }
        JsonNode version = payload.path("orderVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong()) {
            throw new IllegalArgumentException("OrderCompleted orderVersion must be a nonnegative long");
        }
        String completedAt = requiredText(payload, "completedAt");
        if (!completedAt.endsWith("Z")) {
            throw new IllegalArgumentException("OrderCompleted completedAt must use UTC (Z)");
        }
        return new OrderCompletedPayload(
                requiredUuid(payload, "orderId"),
                requiredUuid(payload, "orderGroupId"),
                requiredUuid(payload, "buyerId"),
                requiredUuid(payload, "sellerId"),
                requiredText(payload, "orderType"),
                reference.isNull() ? null : requiredUuid(payload, "referenceId"),
                version.asLong(),
                OffsetDateTime.parse(completedAt));
    }

    public UUID auctionId() {
        return "AUCTION".equals(orderType) ? referenceId : null;
    }

    private static UUID requiredUuid(JsonNode payload, String field) {
        String value = requiredText(payload, field);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("OrderCompleted " + field + " must be a UUID");
        }
        return id;
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("OrderCompleted payload has no valid " + field);
        }
        return value.asString();
    }
}
