package com.group5.commerceservice.order;

import com.group5.common_events.AuctionWon;
import com.group5.commerceservice.order.entity.*;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Nghiệp vụ tạo đơn chạy độc lập DB/Kafka; rollback và cạnh tranh có integration test riêng. */
class AuctionOrderServiceUnitTest {
    private final OrderGroupRepository groups = mock(OrderGroupRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderStatusHistoryRepository history = mock(OrderStatusHistoryRepository.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final com.group5.commerceservice.order.service.PricingService pricing = mock(com.group5.commerceservice.order.service.PricingService.class);
    private final AuctionOrderService service = new AuctionOrderService(groups, orders, history, events, pricing);

    @Test void createsOnePendingOrderFromImmutableWinningSnapshotWithoutPaymentRequest() {
        AuctionWon won = won();
        when(orders.nextOrderSequence()).thenReturn(172L);
        doAnswer(call -> {
            java.util.Collection<Order> batch = call.getArgument(0);
            batch.forEach(order -> order.getItems().forEach(item -> item.setUnitCostPrice(new BigDecimal("700000"))));
            return null;
        }).when(pricing).snapshotCosts(any());
        service.create(won);

        var saved = ArgumentCaptor.forClass(OrderGroup.class);
        verify(groups).saveAndFlush(saved.capture());
        OrderGroup group = saved.getValue();
        assertThat(group.getBuyerId()).isEqualTo(won.winnerId());
        assertThat(group.getCurrency()).isEqualTo("VND");
        assertThat(group.getStatus()).isEqualTo("PENDING");
        assertThat(group.getTotalAmount()).isEqualByComparingTo("1500000.00");
        assertThat(group.getDueAt().toInstant()).isEqualTo(won.paymentDueAt());
        assertThat(group.getOrders()).hasSize(1);
        Order order = group.getOrders().getFirst();
        assertThat(order.getOrderType()).isEqualTo(OrderType.AUCTION);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getReferenceId()).isEqualTo(won.auctionId());
        assertThat(order.getSellerId()).isEqualTo(won.sellerId());
        assertThat(order.getUserId()).isEqualTo(won.winnerId());
        assertThat(order.getOrderNumber()).isEqualTo("AUC-172");
        assertThat(order.getPaymentDueAt()).isEqualTo(group.getDueAt());
        assertThat(order.getShippingInfo()).isNull();
        assertThat(order.getShippingFee()).isEqualByComparingTo(BigDecimal.ZERO);
        OrderItem item = order.getItems().getFirst();
        assertThat(item.getProductId()).isEqualTo(won.productId());
        assertThat(item.getSkuId()).isEqualTo(won.skuId());
        assertThat(item.getProductName()).isEqualTo(won.title());
        assertThat(item.getImageUrl()).isEqualTo(won.imageUrl());
        assertThat(item.getQuantity()).isEqualTo(1);
        assertThat(item.getUnitPrice()).isEqualByComparingTo(won.finalPrice());
        assertThat(item.getTotalPrice()).isEqualByComparingTo(won.finalPrice());
        assertThat(item.getUnitCostPrice()).isEqualByComparingTo("700000");
        assertThat(item.getSkuCode()).isEqualTo("NX-ITEM-1");
        var sequence = inOrder(groups, orders, history, events);
        sequence.verify(groups).lockKey("auction-reference:" + won.auctionId());
        sequence.verify(orders).findByReferenceId(won.auctionId());
        sequence.verify(groups).saveAndFlush(group);
        sequence.verify(history).save(any());
        sequence.verify(events).orderCreated(order);
        verify(events, never()).checkoutPaymentRequested(any());
    }

    @Test void existingAuctionReferenceSkipsAllCreationEffects() {
        AuctionWon won = won();
        Order previous = new Order(); previous.setOrderType(OrderType.AUCTION);
        when(orders.findByReferenceId(won.auctionId())).thenReturn(List.of(previous));
        service.create(won);
        verify(groups).lockKey("auction-reference:" + won.auctionId());
        verify(groups, never()).saveAndFlush(any());
        verify(orders, never()).nextOrderSequence();
        verifyNoInteractions(history, events, pricing);
    }

    @Test void standardReferenceDoesNotSuppressAuctionCreation() {
        AuctionWon won = won();
        when(orders.findByReferenceId(won.auctionId())).thenReturn(List.of(new Order()));
        service.create(won);
        verify(groups).saveAndFlush(any()); verify(events).orderCreated(any());
    }

    @Test void persistenceFailureNeverPublishesCreationOrPayment() {
        doThrow(new IllegalStateException("storage failed")).when(groups).saveAndFlush(any());
        assertThatThrownBy(() -> service.create(won())).hasMessage("storage failed");
        verifyNoInteractions(history, events);
    }

    private AuctionWon won() {
        return new AuctionWon(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "1500000.00", "VND", "Winning snapshot", "https://example.com/item.jpg",
                1, Instant.parse("2031-01-01T00:00:00Z"), Instant.parse("2031-01-02T00:00:00Z"), null, "NX-ITEM-1");
    }
}
