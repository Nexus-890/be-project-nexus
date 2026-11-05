package com.group5.paymentservice.payment.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckoutPaymentRequested(
        @NotNull UUID orderGroupId,
        @NotNull UUID buyerId,
        @NotNull @Pattern(regexp = "VND") String currency,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal totalAmount,
        @NotNull Instant dueAt,
        @NotNull Instant occurredAt,
        @NotNull @Min(1) Integer orderCount,
        @NotEmpty List<@NotNull @Valid SellerOrder> orders) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SellerOrder(
            @NotNull UUID orderId,
            @NotNull UUID sellerId,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull Instant paymentDueAt,
            @NotNull @Min(0) Long orderVersion) {
    }
}
