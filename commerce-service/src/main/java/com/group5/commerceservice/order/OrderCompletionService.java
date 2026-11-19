package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderCompleted;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Xác nhận nhận hàng và job tự hoàn tất cùng khóa order, ghi trạng thái và outbox trong một transaction. */
@Service
public class OrderCompletionService {
    private final OrderRepository orders;
    private final OrderStateMachine stateMachine;
    private final OrderEventPublisher events;
    private final OrderReceiptRenderer receipts;
    private final EntityManager entityManager;
    private final Clock clock;

    @Autowired
    public OrderCompletionService(OrderRepository orders, OrderStateMachine stateMachine, OrderEventPublisher events,
                                  OrderReceiptRenderer receipts, EntityManager entityManager) {
        this(orders, stateMachine, events, receipts, entityManager, Clock.systemUTC());
    }

    OrderCompletionService(OrderRepository orders, OrderStateMachine stateMachine, OrderEventPublisher events,
                           OrderReceiptRenderer receipts, EntityManager entityManager, Clock clock) {
        this.orders = orders;
        this.stateMachine = stateMachine;
        this.events = events;
        this.receipts = receipts;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public void confirm(CurrentUser user, UUID orderId) {
        if (user == null || user.id() == null) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        if (!user.hasPrivilege("ORDER.VIEW") || !user.hasRole("BUYER"))
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        Order order = locked(orderId);
        // Kiểm tra chủ đơn trước trạng thái; admin cũng không được xác nhận thay buyer khác.
        if (!order.getUserId().equals(user.id())) throw new DomainException(CommerceErrorCode.FORBIDDEN);
        if (order.getStatus() != OrderStatus.DELIVERED) throw new DomainException(CommerceErrorCode.ORDER_NOT_DELIVERED);
        if (order.getReceiptIssueReportedAt() != null) throw new DomainException(CommerceErrorCode.RECEIPT_ISSUE_OPEN);
        complete(order, user.id(), "Buyer confirmed receipt");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeIfDue(UUID orderId) {
        if (orders.lockOrder(orderId).isEmpty()) return false;
        Order order = orders.findById(orderId).orElseThrow();
        entityManager.refresh(order);
        if (order.getStatus() != OrderStatus.DELIVERED || order.getReceiptIssueReportedAt() != null || order.getAutoCompleteAt() == null
                || order.getAutoCompleteAt().isAfter(OffsetDateTime.now(clock))) return false;
        complete(order, new UUID(0, 0), "Completed automatically after delivery");
        return true;
    }

    @Transactional
    public void reportIssue(CurrentUser user, UUID orderId, String reason) {
        if (user == null || user.id() == null) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        if (!user.hasPrivilege("ORDER.VIEW") || !user.hasRole("BUYER")) throw new DomainException(CommerceErrorCode.FORBIDDEN);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "A receipt issue reason of 1 to 500 characters is required");
        Order order = locked(orderId);
        if (!order.getUserId().equals(user.id())) throw new DomainException(CommerceErrorCode.FORBIDDEN);
        if (order.getStatus() != OrderStatus.DELIVERED) throw new DomainException(CommerceErrorCode.ORDER_NOT_DELIVERED);
        if (order.getReceiptIssueReportedAt() != null) return;
        orders.reportReceiptIssue(orderId, reason.trim(), OffsetDateTime.now(clock), user.id());
        entityManager.refresh(order);
    }

    private Order locked(UUID id) {
        if (orders.lockOrder(id).isEmpty()) throw new DomainException(CommerceErrorCode.ORDER_NOT_FOUND);
        Order order = orders.findById(id).orElseThrow();
        entityManager.refresh(order);
        return order;
    }

    private void complete(Order order, UUID actor, String reason) {
        var completion = stateMachine.move(order.getId(), OrderStatus.COMPLETED, actor, reason);
        entityManager.flush();
        entityManager.refresh(completion);
        entityManager.refresh(order);
        if (order.getUserId().equals(order.getSellerId())
                || (order.getOrderType() == OrderType.AUCTION ? order.getReferenceId() == null : order.getReferenceId() != null))
            throw new IllegalArgumentException("Invalid buyer, seller or auction completion reference");
        events.orderCompleted(new OrderCompleted(order.getId(), order.getOrderGroup().getId(), order.getUserId(),
                order.getSellerId(), order.getOrderType(), order.getReferenceId(), order.getVersion(),
                completion.getCreatedAt().toInstant().toString(), order.getOrderNumber(),
                receipts.renderForEmail(order, completion.getCreatedAt()),
                order.getTotalAmount().setScale(2).toPlainString(),
                order.getShippingFee().setScale(2).toPlainString(),
                order.getItems().stream().map(item -> new OrderCompleted.Item(item.getProductId(), item.getSkuId(),
                        item.getQuantity())).toList()));
    }
}
