package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.order.dto.request.ChangeOrderStatusRequest;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Kiểm tra quyền và response của PATCH fallback #158; không xác nhận toàn bộ checkout đã được thanh toán. */
@ExtendWith(MockitoExtension.class)
class DemoManualPaidServiceUnitTest {
    private static final UUID ORDER_ID = UUID.fromString("15800000-0000-4000-8000-000000000011");
    private static final CurrentUser ADMIN = new CurrentUser(
            UUID.fromString("15800000-0000-4000-8000-000000000012"), Set.of("ADMIN"), Set.of());
    private static final ChangeOrderStatusRequest PAID = new ChangeOrderStatusRequest(
            OrderStatus.PAID, "Demo fallback #158: manual reconciliation pending");

    @Mock private OrderRepository orders;
    @Mock private OrderStateMachine stateMachine;
    @Mock private OrderGroupRepository groups;
    @Mock private OrderEventPublisher events;
    @Mock private EntityManager entityManager;
    @InjectMocks private OrderService service;

    @Test
    @DisplayName("Admin chuyển đúng order và trả trạng thái đọc sau bước chuyển, giữ actor và lý do")
    void adminReceivesTheUpdatedChildOrderWithoutClaimingGroupPayment() {
        Order order = pendingOrder();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        doAnswer(invocation -> {
            order.setStatus(OrderStatus.PAID);
            return null;
        }).when(stateMachine).move(ORDER_ID, OrderStatus.PAID, ADMIN.id(), PAID.reason());

        var response = service.changeStatus(ADMIN, ORDER_ID, PAID);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.orderGroupId()).isEqualTo(order.getOrderGroup().getId());
        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.orderNumber()).isEqualTo("NX-DEMO-158");
        assertThat(response.userId()).isEqualTo(order.getUserId());
        assertThat(response.sellerId()).isEqualTo(order.getSellerId());
        assertThat(response.totalAmount()).isEqualByComparingTo("350000.00");
        assertThat(response.subtotal()).isEqualByComparingTo("320000.00");
        assertThat(response.shippingFee()).isEqualByComparingTo("30000.00");
        var calls = inOrder(stateMachine, orders);
        calls.verify(stateMachine).move(ORDER_ID, OrderStatus.PAID, ADMIN.id(), PAID.reason());
        calls.verify(orders, atLeastOnce()).findById(ORDER_ID);

        // PATCH hiện chỉ đổi order con; không giả định nó đã xác nhận receipt, group hoặc phát OrderPaid.
        assertThat(order.getOrderGroup().getStatus()).isEqualTo("PENDING");
        assertThat(order.getOrderGroup().getPaidAt()).isNull();
        assertThat(order.getPaidAt()).isNull();
        verifyNoInteractions(groups, events);
    }

    @ParameterizedTest(name = "{0} không được dùng fallback Admin")
    @ValueSource(strings = {"BUYER", "SELLER", "SUPPORT_STAFF"})
    @DisplayName("Privilege của order không cho role khác quyền đánh dấu PAID thủ công")
    void nonAdminCannotReachTheStatusMachineEvenWithOrderPrivileges(String role) {
        CurrentUser caller = new CurrentUser(UUID.randomUUID(), Set.of(role),
                Set.of("ORDER.VIEW", "ORDER.CREATE", "ORDER.CANCEL", "ORDER.REFUND"));

        assertThatThrownBy(() -> service.changeStatus(caller, ORDER_ID, PAID))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(403);
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_FORBIDDEN");
                });

        verifyNoInteractions(stateMachine, orders, groups, events, entityManager);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthenticatedCallers")
    @DisplayName("Thiếu danh tính trả 401 trước khi tra hoặc thay đổi order")
    void unauthenticatedCallerCannotUseManualPaid(String label, CurrentUser caller) {
        assertThatThrownBy(() -> service.changeStatus(caller, ORDER_ID, PAID))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(401);
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_UNAUTHENTICATED");
                });

        verifyNoInteractions(stateMachine, orders, groups, events, entityManager);
    }

    @Test
    @DisplayName("Xung đột chuyển trạng thái không bị đổi thành response thành công")
    void refusedTransitionPropagatesWithoutReadingASuccessResponse() {
        var failure = new DomainException(CommerceErrorCode.ILLEGAL_TRANSITION);
        doThrow(failure).when(stateMachine).move(ORDER_ID, OrderStatus.PAID, ADMIN.id(), PAID.reason());

        assertThatThrownBy(() -> service.changeStatus(ADMIN, ORDER_ID, PAID)).isSameAs(failure);

        verifyNoInteractions(orders, groups, events, entityManager);
    }

    @Test
    @DisplayName("Không có order sau chuyển trạng thái trả lỗi 404, không dựng response PAID giả")
    void absentOrderAfterTheTransitionCannotProduceAPaidResponse() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(ADMIN, ORDER_ID, PAID))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_ORDER_NOT_FOUND");
                });

        verify(stateMachine).move(ORDER_ID, OrderStatus.PAID, ADMIN.id(), PAID.reason());
        verifyNoInteractions(groups, events);
    }

    @Test
    @DisplayName("Lỗi đọc kết quả sau transition được truyền ra ngoài để caller không nhận PAID giả")
    void responseReadFailureIsNotSwallowed() {
        var failure = new IllegalStateException("Synthetic order read failure");
        when(orders.findById(ORDER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.changeStatus(ADMIN, ORDER_ID, PAID)).isSameAs(failure);

        verify(stateMachine).move(ORDER_ID, OrderStatus.PAID, ADMIN.id(), PAID.reason());
        verifyNoInteractions(groups, events);
    }

    private static Stream<Arguments> unauthenticatedCallers() {
        return Stream.of(Arguments.of("Không có principal", null),
                Arguments.of("Admin principal không có ID", new CurrentUser(null, Set.of("ADMIN"), Set.of())));
    }

    private static Order pendingOrder() {
        OrderGroup group = new OrderGroup();
        group.setId(UUID.fromString("15800000-0000-4000-8000-000000000013"));
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderGroup(group);
        order.setUserId(UUID.fromString("15800000-0000-4000-8000-000000000014"));
        order.setSellerId(UUID.fromString("15800000-0000-4000-8000-000000000015"));
        order.setOrderNumber("NX-DEMO-158");
        order.setSubtotal(new BigDecimal("320000.00"));
        order.setShippingFee(new BigDecimal("30000.00"));
        order.setTotalAmount(new BigDecimal("350000.00"));
        return order;
    }
}
