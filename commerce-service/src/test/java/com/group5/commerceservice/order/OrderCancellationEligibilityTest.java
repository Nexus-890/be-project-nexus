package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCancellationEligibilityTest {
    @Mock private OrderRepository orders;
    @Mock private OrderGroupRepository groups;
    @InjectMocks private OrderService service;

    private final UUID buyerId = UUID.randomUUID();
    private final CurrentUser buyer = user(buyerId, "BUYER", "ORDER.VIEW", "ORDER.CANCEL");

    @Test
    void ownerNeedsCancelPrivilegeAndAnUnpaidGroupToCancelAPendingOrder() {
        Order order = order(OrderStatus.PENDING, "PENDING");
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        assertThat(service.getOrder(buyer, order.getId()).canCancel()).isTrue();
        assertThat(service.getOrder(user(buyerId, "BUYER", "ORDER.VIEW"), order.getId()).canCancel()).isFalse();
        order.getOrderGroup().setStatus("PAID");
        assertThat(service.getOrder(buyer, order.getId()).canCancel()).isFalse();
        order.getOrderGroup().setStatus("CANCELED");
        assertThat(service.getOrder(buyer, order.getId()).canCancel()).isFalse();
    }

    @Test
    void paidOrderIsCancellableUntilItLeavesPaidStatus() {
        Order order = order(OrderStatus.PAID, "PAID");
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        assertThat(service.getOrder(buyer, order.getId()).canCancel()).isTrue();
        for (OrderStatus status : List.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED,
                OrderStatus.CANCELED, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED)) {
            order.setStatus(status);
            assertThat(service.getOrder(buyer, order.getId()).canCancel()).as("status %s", status).isFalse();
        }
    }

    @Test
    void anotherBuyerCannotReadTheOrderAndAdminStillNeedsCancelPrivilege() {
        Order order = order(OrderStatus.PAID, "PAID");
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getOrder(user(UUID.randomUUID(), "BUYER", "ORDER.VIEW", "ORDER.CANCEL"), order.getId()))
                .isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(403, "COMMERCE_FORBIDDEN");
        assertThat(service.getOrder(user(UUID.randomUUID(), "ADMIN", "ORDER.VIEW"), order.getId()).canCancel()).isFalse();
        assertThat(service.getOrder(user(UUID.randomUUID(), "ADMIN", "ORDER.VIEW", "ORDER.CANCEL"), order.getId()).canCancel()).isTrue();
    }

    @Test
    void groupAndListReadsUseTheCurrentCallersCancellationRights() {
        Order order = order(OrderStatus.PENDING, "PENDING");
        OrderGroup group = order.getOrderGroup();
        group.getOrders().add(order);
        when(groups.findById(group.getId())).thenReturn(Optional.of(group));
        when(orders.findByUserIdOrderByCreatedAtDescOrderNumberDesc(eq(buyerId), any()))
                .thenReturn(new PageImpl<>(List.of(order)));

        assertThat(service.getGroup(buyer, group.getId()).orders().getFirst().canCancel()).isTrue();
        assertThat(service.listOwn(buyer, null, 0, 10).getContent().getFirst().canCancel()).isTrue();
        CurrentUser readOnly = user(buyerId, "BUYER", "ORDER.VIEW");
        assertThat(service.getGroup(readOnly, group.getId()).orders().getFirst().canCancel()).isFalse();
        assertThat(service.listOwn(readOnly, null, 0, 10).getContent().getFirst().canCancel()).isFalse();
    }

    private Order order(OrderStatus status, String groupStatus) {
        OrderGroup group = new OrderGroup();
        group.setBuyerId(buyerId);
        group.setStatus(groupStatus);
        Order order = new Order();
        order.setOrderGroup(group);
        order.setUserId(buyerId);
        order.setSellerId(UUID.randomUUID());
        order.setOrderNumber("NX-ELIGIBILITY");
        order.setCreatedAt(OffsetDateTime.parse("2026-11-12T01:00:00Z"));
        order.setSubtotal(BigDecimal.TEN);
        order.setTotalAmount(BigDecimal.TEN);
        order.setStatus(status);
        return order;
    }

    private static CurrentUser user(UUID id, String role, String... privileges) {
        return new CurrentUser(id, Set.of(role), Set.of(privileges));
    }
}
