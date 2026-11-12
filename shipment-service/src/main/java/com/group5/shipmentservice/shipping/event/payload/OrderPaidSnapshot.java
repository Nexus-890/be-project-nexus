package com.group5.shipmentservice.shipping.event.payload;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;


public record OrderPaidSnapshot(UUID orderId, UUID buyerId, UUID warehouseId,
                               BigDecimal shippingFee, Map<String, Object> shippingInfo, long orderVersion, UUID sellerId) {
    @SuppressWarnings("unchecked")
    public static OrderPaidSnapshot parse(JsonNode payload, JsonMapper mapper) {
        UUID orderId = uuid(payload, "orderId");
        UUID buyerId = uuid(payload, "buyerId");
        UUID warehouseId = uuid(payload, "warehouseId");
        for (String field : new String[]{"orderGroupId", "sellerId", "sessionId", "instructionId", "transactionId"}) {
            uuid(payload, field);
        }
        text(payload, "orderNumber", 40);
        String type = text(payload, "orderType", 20);
        if (!("STANDARD".equals(type) || "AUCTION".equals(type))) invalid();
        if ("AUCTION".equals(type)) uuid(payload, "referenceId");
        else if (!payload.has("referenceId") || !payload.path("referenceId").isNull()) invalid();
        String paidAt = text(payload, "paidAt", 50);
        if (!paidAt.endsWith("Z")) invalid();
        Instant.parse(paidAt);
        if (!"VND".equals(text(payload, "currency", 3))) invalid();
        money(payload, "paidAmount");
        BigDecimal fee = money(payload, "shippingFee");
        JsonNode version = payload.path("orderVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0) invalid();
        JsonNode info = payload.path("shippingInfo");
        if (!info.isObject()) invalid();
        for (String field : new String[]{"carrier", "serviceType"}) {
            if (info.hasNonNull(field) && !text(info, field, 40).matches("\\S+")) invalid();
        }
        JsonNode recipient = info.path("recipient");
        text(recipient, "name", 150);
        text(recipient, "phone", 20);
        address(recipient.path("address"));
        JsonNode items = info.path("items");
        if (!items.isArray() || items.isEmpty()) invalid();
        for (JsonNode item : items) {
            uuid(item, "skuId");
            text(item, "productName", 255);
            if (!item.path("quantity").isIntegralNumber() || !item.path("quantity").canConvertToInt()
                    || item.path("quantity").asInt() < 1) invalid();
        }
        if (!info.has("parcel") || !info.has("pickupAddress")) invalid();
        if (!info.path("pickupAddress").isNull()) address(info.path("pickupAddress"));
        if (!info.path("parcel").isNull()) {
            JsonNode parcel = info.path("parcel");
            for (String field : new String[]{"weightGrams", "lengthCm", "widthCm", "heightCm"}) {
                if (!parcel.path(field).isNumber() || parcel.path(field).decimalValue().signum() <= 0) invalid();
            }
            if (!parcel.path("weightGrams").isIntegralNumber() || !parcel.path("weightGrams").canConvertToInt()) invalid();
        }
        return new OrderPaidSnapshot(orderId, buyerId, warehouseId, fee,
                mapper.convertValue(info, Map.class), version.asLong(), uuid(payload, "sellerId"));
    }

    public boolean isPrepared() {
        return shippingInfo.get("parcel") != null && shippingInfo.get("pickupAddress") != null;
    }

    private static void address(JsonNode node) {
        text(node, "line1", 500);
        text(node, "wardCode", 40);
        text(node, "provinceCode", 40);

    }
    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(text(node, field, 36)); }
    private static BigDecimal money(JsonNode node, String field) {
        String value = text(node, field, 22);
        if (!value.matches("\\d{1,17}\\.\\d{2}")) invalid();
        return new BigDecimal(value);
    }
    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asText().isBlank() || value.asText().length() > max) invalid();
        return value.asText();
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid OrderPaid shipping snapshot"); }
}
