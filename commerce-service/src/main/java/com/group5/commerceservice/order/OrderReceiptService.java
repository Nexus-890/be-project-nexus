package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderReceiptService {
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OrderReceiptRenderer renderer;

    @Transactional(readOnly = true)
    public String invoice(CurrentUser user, UUID id) {
        if (user == null || user.id() == null) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        Order order = orders.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(user.id())) throw new DomainException(CommerceErrorCode.FORBIDDEN);
        if (order.getStatus() != OrderStatus.COMPLETED) throw new DomainException(CommerceErrorCode.ORDER_NOT_COMPLETED);
        var completion = history.findByOrderIdOrderByCreatedAtAscIdAsc(id).stream()
                .filter(row -> row.getNewStatus() == OrderStatus.COMPLETED).findFirst()
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_COMPLETED));
        return renderer.render(order, completion.getCreatedAt());
    }
}
