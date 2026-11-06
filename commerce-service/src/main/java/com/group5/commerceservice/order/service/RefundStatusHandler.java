package com.group5.commerceservice.order.service;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.event.OrderRefundCompleted;
import com.group5.commerceservice.order.event.OrderRefundRequested;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RefundStatusHandler {
    public static final String CONSUMER = "commerce-order-refund";

    private final IdempotentConsumer consumer;
    private final OrderRepository orders;
    private final OrderStateMachine stateMachine;
    private final Validator validator;

    public RefundStatusHandler(IdempotentConsumer consumer, OrderRepository orders,
                               OrderStateMachine stateMachine, Validator validator) {
        this.consumer = consumer;
        this.orders = orders;
        this.stateMachine = stateMachine;
        this.validator = validator;
    }

    public void requested(EventEnvelope<OrderRefundRequested> event) {
        OrderRefundRequested refund = event.payload();
        require(validator.validate(refund).isEmpty(), "Invalid OrderRefundRequested payload");
        require(event.aggregateId().equals(refund.orderId().toString()), "Order and aggregate must match");
        consumer.handle(CONSUMER, event, ignored ->
                apply(refund.orderId(), refund.refundVersion(), false, refund.actorId(), refund.reason()));
    }

    public void completed(EventEnvelope<OrderRefundCompleted> event) {
        OrderRefundCompleted refund = event.payload();
        require(validator.validate(refund).isEmpty(), "Invalid OrderRefundCompleted payload");
        require(event.aggregateId().equals(refund.orderId().toString()), "Order and aggregate must match");
        consumer.handle(CONSUMER, event, ignored ->
                apply(refund.orderId(), refund.refundVersion(), true, refund.actorId(), refund.reason()));
    }

    private void apply(UUID orderId, long refundVersion, boolean completed, UUID actor, String reason) {
        // KHÓA DÒNG ĐƠN RỒI MỚI ĐỌC VERSION: 2 EVENT REFUND CỦA CÙNG ĐƠN KHÔNG CHẠY CHỒNG NHAU
        long applied = orders.lockRefundVersion(orderId)
                .orElseThrow(() -> new IllegalStateException("Refund event for an unknown order " + orderId));
        // EVENT CŨ HƠN BẢN ĐÃ ÁP DỤNG (VD REQUESTED ĐẾN SAU COMPLETED) -> BỎ QUA
        if (refundVersion < applied) {
            return;
        }
        OrderStatus current = orders.findStatusById(orderId).orElseThrow();
        switch (current) {
            case CANCELED -> {
                stateMachine.move(orderId, OrderStatus.REFUND_REQUESTED, actor, reason);
                // COMPLETED ĐẾN TRƯỚC REQUESTED -> ĐI ĐỦ 2 BƯỚC ĐỂ HISTORY KHÔNG BỊ THIẾU
                if (completed) {
                    stateMachine.move(orderId, OrderStatus.REFUNDED, actor, reason);
                }
            }
            case REFUND_REQUESTED -> {
                if (completed) {
                    stateMachine.move(orderId, OrderStatus.REFUNDED, actor, reason);
                }
            }
            case REFUNDED -> {
            }
            // ĐƠN CHƯA HỦY MÀ ĐÃ CÓ REFUND = SAI THỨ TỰ -> NÉM LỖI, KHÔNG GHI processed_events, KAFKA THỬ LẠI
            default -> throw new IllegalStateException("Order " + orderId + " is " + current + ", not refundable yet");
        }
        orders.recordRefundVersion(orderId, refundVersion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
