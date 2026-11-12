package com.group5.commerceservice.order.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.UUID;

/** Kế hoạch thu tiền đầy đủ của session bất biến; sessionVersion thuộc Payment, không phải version nhóm đơn. */
public record CheckoutPaymentConfirmed(
        @NotNull UUID sessionId,
        @NotNull UUID orderGroupId,
        @NotNull UUID transactionId,
        @NotNull UUID buyerId,
        @NotNull @Pattern(regexp = "VND") String currency,
        @NotNull @Pattern(regexp = "\\d+\\.\\d{2}") String totalAmount,
        @NotNull @Pattern(regexp = ".*Z$") String paidAt,
        @NotNull @PositiveOrZero Long sessionVersion,
        @NotEmpty List<@NotNull @Valid Instruction> instructions) {

    /** Phần tiền đã cấp cho một đơn người bán; tổng các phần phải khớp tổng của nhóm. */
    public record Instruction(@NotNull UUID instructionId, @NotNull UUID orderId,
                              @NotNull @Pattern(regexp = "\\d+\\.\\d{2}") String amount) { }
}
