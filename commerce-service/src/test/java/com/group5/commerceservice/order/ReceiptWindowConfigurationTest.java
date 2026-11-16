package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.OrderStatusHistory;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReceiptWindowConfigurationTest {
    @Test void configuredWaitingDaysArePersistedFromTheDeliveryHistoryTime() {
        var orders = mock(OrderRepository.class);
        var history = mock(OrderStatusHistoryRepository.class);
        var state = new OrderStateMachine(orders, history);
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        ReflectionTestUtils.setField(state, "autoCompleteDays", 3);
        when(orders.findStatusById(id)).thenReturn(Optional.of(OrderStatus.SHIPPED));
        when(orders.moveStatus(id, "SHIPPED", "DELIVERED", actor)).thenReturn(1);
        when(history.save(any(OrderStatusHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var transition = state.move(id, OrderStatus.DELIVERED, actor, "Delivered");
        var delivered = ArgumentCaptor.forClass(OffsetDateTime.class);
        var due = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(orders).recordDeliveredAt(eq(id), delivered.capture(), due.capture());
        assertThat(delivered.getValue()).isEqualTo(transition.getCreatedAt());
        assertThat(due.getValue()).isEqualTo(delivered.getValue().plusDays(3));
    }

    @Test void invalidWindowFailsConfigurationInsteadOfCompletingImmediately() {
        var state = new OrderStateMachine(mock(OrderRepository.class), mock(OrderStatusHistoryRepository.class));
        for (int days : new int[]{0, -1}) {
            ReflectionTestUtils.setField(state, "autoCompleteDays", days);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(state, "validateReceiptWindow"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("auto-complete-days");
        }
    }
}
