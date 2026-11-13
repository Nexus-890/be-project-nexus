package com.group5.shipmentservice.shipping.adapter;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

public final class CarrierTrackingPayload {
    private CarrierTrackingPayload() {
    }

    public static String trackingCode(JsonNode node, String field, ShipmentErrorCode errorCode) {
        String value = requiredText(node, field, errorCode);
        if (value.length() > 80 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new DomainException(errorCode);
        }
        return value;
    }

    public static String requiredText(JsonNode node, String field, ShipmentErrorCode errorCode) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new DomainException(errorCode);
        }
        return value.asString();
    }

    public static OffsetDateTime timestamp(JsonNode node, String field, ShipmentErrorCode errorCode) {
        try {
            return OffsetDateTime.parse(requiredText(node, field, errorCode));
        } catch (java.time.DateTimeException error) {
            throw new DomainException(errorCode);
        }
    }

    public static String webhookType(JsonNode node) {
        String type = requiredText(node, "Type", ShipmentErrorCode.INVALID_WEBHOOK);
        if (!List.of("create", "switch_status", "update_weight", "update_cod", "update_fee", "update_payment_type",
                "cod", "update_partial_return").contains(type)) {
            throw new DomainException(ShipmentErrorCode.INVALID_WEBHOOK);
        }
        return type;
    }

    public static String description(JsonNode node) {
        String text = node.path("Reason").asString("");
        if (text.isBlank()) text = node.path("Description").asString("");
        text = text.replaceAll("[\\p{Cntrl}]", " ");
        return text.length() <= 255 ? text : text.substring(0, 255);
    }

    public static String identity(String carrier, String trackingCode, String type, OffsetDateTime occurredAt) {
        try {
            String value = trackingCode + "|" + type + "|" + occurredAt.toInstant();
            return carrier + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
