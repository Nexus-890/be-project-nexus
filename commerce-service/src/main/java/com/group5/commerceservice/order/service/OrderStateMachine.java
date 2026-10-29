package com.group5.commerceservice.order.service;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.entity.OrderStatusHistory;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStateMachine {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;

    @Transactional
    public OrderStatusHistory move(UUID orderId, OrderStatus target, UUID actor, String reason) {
        OrderStatus current = orderRepository.findStatusById(orderId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
        if (!current.canMoveTo(target)
                || orderRepository.moveStatus(orderId, current.name(), target.name(), actor) == 0) {
            log.info("Order transition refused: orderId={}, from={}, to={}", orderId, current, target);
            throw new DomainException(409, "ILLEGAL_TRANSITION: order cannot move from " + current + " to " + target);
        }
        log.info("Order transition accepted: orderId={}, from={}, to={}, actor={}", orderId, current, target, actor);
        return historyRepository.save(entry(orderId, current, target, actor, reason));
    }

    @Transactional
    public OrderStatusHistory recordCreated(UUID orderId, UUID actor) {
        return historyRepository.save(entry(orderId, null, OrderStatus.PENDING, actor, "Order created from cart"));
    }

    private OrderStatusHistory entry(UUID orderId, OrderStatus previous, OrderStatus next, UUID actor, String reason) {
        OrderStatusHistory entry = new OrderStatusHistory();
        entry.setOrderId(orderId);
        entry.setPreviousStatus(previous);
        entry.setNewStatus(next);
        entry.setReason(reason);
        entry.setChangedBy(actor);
        entry.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        return entry;
    }
}
