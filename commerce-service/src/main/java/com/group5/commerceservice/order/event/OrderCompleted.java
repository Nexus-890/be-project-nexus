package com.group5.commerceservice.order.event;

import com.group5.commerceservice.order.OrderType;

import java.util.UUID;

/** Snapshot đơn hoàn tất dùng chung cho payout, đánh giá và gửi biên nhận qua email. */
public record OrderCompleted(UUID orderId, UUID orderGroupId, UUID buyerId, UUID sellerId,
        OrderType orderType, UUID referenceId, long orderVersion, String completedAt,
        String orderNumber, String receiptHtml, String orderAmount, String shippingFee) { }
