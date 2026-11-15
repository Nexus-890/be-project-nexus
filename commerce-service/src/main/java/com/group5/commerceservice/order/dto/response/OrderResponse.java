package com.group5.commerceservice.order.dto.response;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.OrderType;
import com.group5.commerceservice.promotion.VoucherCheckoutService.OrderVoucher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String orderNumber,
        UUID userId,
        UUID sellerId,
        OrderType orderType,
        OrderStatus status,
        OffsetDateTime createdAt,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        String recipientName,
        String phoneNumber,
        UUID orderGroupId,
        BigDecimal subtotal,
        BigDecimal shippingFee,
        BigDecimal discountAmount,
        BigDecimal taxAmount,
        boolean canCancel,
        String paymentNote,
        List<OrderVoucher> vouchers) {

    public OrderResponse(UUID orderId, String orderNumber, UUID userId, UUID sellerId, OrderType orderType,
                         OrderStatus status, OffsetDateTime createdAt, BigDecimal totalAmount, List<OrderItemResponse> items,
                         String recipientName, String phoneNumber, UUID orderGroupId, BigDecimal subtotal, BigDecimal shippingFee,
                         BigDecimal discountAmount, BigDecimal taxAmount, boolean canCancel, String paymentNote) {
        this(orderId, orderNumber, userId, sellerId, orderType, status, createdAt, totalAmount, items, recipientName,
                phoneNumber, orderGroupId, subtotal, shippingFee, discountAmount, taxAmount, canCancel, paymentNote, List.of());
    }

    public OrderResponse {
        items = List.copyOf(items);
        vouchers = List.copyOf(vouchers);
    }
}
