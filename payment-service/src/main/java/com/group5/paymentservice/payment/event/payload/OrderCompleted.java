package com.group5.paymentservice.payment.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Event cũ vẫn đọc được, nhưng không được tạo payout nếu thiếu dữ liệu phí vận chuyển. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCompleted(@NotNull UUID orderId, @NotNull UUID orderGroupId, @NotNull UUID buyerId,
        @NotNull UUID sellerId, @NotBlank String orderType, UUID referenceId,
        @PositiveOrZero long orderVersion, @NotNull Instant completedAt,
        @NotBlank @Size(max = 100) String orderNumber, String orderAmount, String shippingFee) {
    public OrderCompleted(UUID orderId, UUID orderGroupId, UUID buyerId, UUID sellerId, String orderType,
            UUID referenceId, long orderVersion, Instant completedAt, String orderNumber) {
        this(orderId, orderGroupId, buyerId, sellerId, orderType, referenceId,
                orderVersion, completedAt, orderNumber, null, null);
    }
}
