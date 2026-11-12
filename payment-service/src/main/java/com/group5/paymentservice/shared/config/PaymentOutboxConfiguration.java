package com.group5.paymentservice.shared.config;

import com.group5.common_outbox.OutboxEventKeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** Chọn Kafka key cho event thanh toán mà không sửa aggregate hoặc nội dung envelope đã lưu. */
@Configuration(proxyBeanMethods = false)
public class PaymentOutboxConfiguration {

    /** Confirmation dùng nhóm đơn làm key để các event cùng nhóm vào cùng partition. */
    @Bean
    OutboxEventKeyResolver paymentOutboxEventKeyResolver() {
        return event -> {
            // Các event khác tiếp tục dùng aggregate làm key theo hành vi mặc định của outbox.
            if (!"CheckoutPaymentConfirmed".equals(event.eventType())) {
                return event.aggregateId();
            }
            // Key thiếu hoặc sai UUID phải gây lỗi để relay giữ event chờ retry, không gửi nhầm theo session.
            JsonNode groupId = event.payload().get("orderGroupId");
            if (groupId == null || !groupId.isString()) {
                throw new IllegalArgumentException("CheckoutPaymentConfirmed requires an orderGroupId Kafka key");
            }
            String value = groupId.asString();
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("CheckoutPaymentConfirmed orderGroupId must be a UUID");
            }
            return id.toString();
        };
    }
}
