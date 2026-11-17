package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.OrderRefundCompleted;
import com.group5.commerceservice.order.event.OrderRefundRequested;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Trường buyerId thêm mới không làm Commerce từ chối các refund event v1 đã lưu. */
class RefundEventCompatibilityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsHistoricalAndNewRefundPayloads(boolean withBuyer) {
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode json = mapper.createObjectNode();
        for (String field : new String[]{"refundId", "orderId", "actorId", "completedTransactionId"}) {
            json.put(field, UUID.randomUUID().toString());
        }
        json.put("amount", "300000.00");
        json.put("currency", "VND");
        json.put("status", "REQUESTED");
        json.put("reason", "Out of stock");
        json.put("refundVersion", 1);
        json.put("instructionVersion", 1);
        json.put("requestedAt", "2026-11-15T00:00:00Z");
        json.put("completedAt", "2026-11-15T01:00:00Z");
        UUID buyer = withBuyer ? UUID.randomUUID() : null;
        if (withBuyer) json.put("buyerId", buyer.toString());

        // Dùng JSON thực để kiểm tra cả deserialize và Bean Validation với trường thiếu/thêm.
        OrderRefundCompleted completed = mapper.treeToValue(json, OrderRefundCompleted.class);
        json.remove("completedTransactionId");
        json.remove("completedAt");
        OrderRefundRequested requested = mapper.treeToValue(json, OrderRefundRequested.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(requested)).isEmpty();
            assertThat(factory.getValidator().validate(completed)).isEmpty();
        }
        assertThat(requested.buyerId()).isEqualTo(buyer);
        assertThat(completed.buyerId()).isEqualTo(buyer);
    }
}
