package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.entity.OrderStatusHistory;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Bảo vệ bước chuyển trạng thái dùng trong fallback #158; không mô phỏng thanh toán ngân hàng hoặc rollback DB. */
@ExtendWith(MockitoExtension.class)
class OrderStateMachineManualPaidUnitTest {
    private static final UUID ORDER_ID = UUID.fromString("15800000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("15800000-0000-4000-8000-000000000002");
    private static final String REASON = "Demo fallback #158: bank receipt awaiting reconciliation";

    @Mock private OrderRepository orders;
    @Mock private OrderStatusHistoryRepository history;

    private OrderStateMachine machine() {
        return new OrderStateMachine(orders, history);
    }

    @Test
    @DisplayName("Fallback PENDING -> PAID lưu đúng actor, lý do và lịch sử sau cập nhật có điều kiện")
    void pendingOrderRecordsTheManualPaidAuditOnlyAfterTheConditionalWriteSucceeds() {
        when(orders.findStatusById(ORDER_ID)).thenReturn(Optional.of(OrderStatus.PENDING));
        when(orders.moveStatus(ORDER_ID, "PENDING", "PAID", ADMIN_ID)).thenReturn(1);
        when(history.save(any(OrderStatusHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        OrderStatusHistory saved = machine().move(ORDER_ID, OrderStatus.PAID, ADMIN_ID, REASON);

        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);
        var captured = ArgumentCaptor.forClass(OrderStatusHistory.class);
        var writes = inOrder(orders, history);
        writes.verify(orders).findStatusById(ORDER_ID);
        writes.verify(orders).moveStatus(ORDER_ID, "PENDING", "PAID", ADMIN_ID);
        writes.verify(history).save(captured.capture());
        assertThat(saved).isSameAs(captured.getValue());
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getPreviousStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getNewStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(saved.getChangedBy()).isEqualTo(ADMIN_ID);
        assertThat(saved.getReason()).isEqualTo(REASON);
        assertThat(saved.getCreatedAt()).isBetween(before, after);
        assertThat(saved.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(saved.getCreatedAt().getNano() % 1000).isZero();
        verifyNoMoreInteractions(orders, history);
    }

    @ParameterizedTest(name = "{0} -> PAID phải bị từ chối")
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Fallback không mở lại đơn đã hủy, đã thanh toán hoặc đang fulfillment")
    void onlyPendingCanMoveToPaid(OrderStatus current) {
        when(orders.findStatusById(ORDER_ID)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> machine().move(ORDER_ID, OrderStatus.PAID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_ILLEGAL_TRANSITION");
                });

        verify(orders).findStatusById(ORDER_ID);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("Đơn đổi trạng thái sau khi đọc trả 409, không thêm lịch sử PAID giả")
    void staleConditionalWriteCannotAppendAHistoryEntry() {
        when(orders.findStatusById(ORDER_ID)).thenReturn(Optional.of(OrderStatus.PENDING));
        when(orders.moveStatus(ORDER_ID, "PENDING", "PAID", ADMIN_ID)).thenReturn(0);

        assertThatThrownBy(() -> machine().move(ORDER_ID, OrderStatus.PAID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_ILLEGAL_TRANSITION");
                });

        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("Order ID không tồn tại trả 404 trước mọi cập nhật hoặc lịch sử")
    void missingOrderHasNoFinancialOrAuditWrite() {
        when(orders.findStatusById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> machine().move(ORDER_ID, OrderStatus.PAID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_ORDER_NOT_FOUND");
                });

        verify(orders).findStatusById(ORDER_ID);
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("Lỗi cập nhật trạng thái được truyền ra ngoài và chưa ghi lịch sử")
    void failedStatusWriteDoesNotAppendHistory() {
        when(orders.findStatusById(ORDER_ID)).thenReturn(Optional.of(OrderStatus.PENDING));
        var failure = new DataIntegrityViolationException("Synthetic conditional status write failure");
        when(orders.moveStatus(ORDER_ID, "PENDING", "PAID", ADMIN_ID)).thenThrow(failure);

        assertThatThrownBy(() -> machine().move(ORDER_ID, OrderStatus.PAID, ADMIN_ID, REASON))
                .isSameAs(failure);

        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("Lỗi lưu audit không bị nuốt để transaction thật có thể rollback")
    void failedHistoryWritePropagatesToTheTransactionalCaller() {
        when(orders.findStatusById(ORDER_ID)).thenReturn(Optional.of(OrderStatus.PENDING));
        when(orders.moveStatus(ORDER_ID, "PENDING", "PAID", ADMIN_ID)).thenReturn(1);
        var failure = new DataIntegrityViolationException("Synthetic manual audit failure");
        when(history.save(any(OrderStatusHistory.class))).thenThrow(failure);

        // Mock chỉ xác minh exception; atomic rollback được kiểm tra bằng PostgreSQL ở integration test.
        assertThatThrownBy(() -> machine().move(ORDER_ID, OrderStatus.PAID, ADMIN_ID, REASON))
                .isSameAs(failure);

        verify(orders).moveStatus(ORDER_ID, "PENDING", "PAID", ADMIN_ID);
    }
}
