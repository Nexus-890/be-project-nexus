package com.group5.commerceservice.order;

import com.group5.common_events.AuctionWon;
import com.group5.commerceservice.order.entity.*;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.*;
import com.group5.commerceservice.order.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Winning data stays immutable; current private SKU cost is captured when the order is created. */
@Service
@RequiredArgsConstructor
public class AuctionOrderService {
    private final OrderGroupRepository groups;
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OrderEventPublisher events;
    private final PricingService pricing;

    @Transactional
    public void create(AuctionWon won) {
        groups.lockKey("auction-reference:" + won.auctionId());
        if (orders.findByReferenceId(won.auctionId()).stream().anyMatch(order ->
                order.getOrderType() == OrderType.AUCTION && java.util.Objects.equals(order.getOfferId(), won.offerId()))) return;
        String identity = won.offerId() == null ? won.auctionId().toString() : "offer:" + won.offerId();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var amount = new BigDecimal(won.finalPrice());
        var group = new OrderGroup();
        group.setBuyerId(won.winnerId()); group.setIdempotencyKey("auction:" + identity);
        group.setTotalAmount(amount); group.setCurrency(won.currency());
        group.setDueAt(won.paymentDueAt().atOffset(ZoneOffset.UTC));
        group.setCreatedAt(now); group.setUpdatedAt(now);
        group.setCreatedBy(won.winnerId()); group.setUpdatedBy(won.winnerId());
        var order = new Order();
        order.setOrderGroup(group); order.setUserId(won.winnerId()); order.setSellerId(won.sellerId());
        order.setOrderNumber("AUC-" + orders.nextOrderSequence());
        order.setIdempotencyKey("auction-order:" + identity);
        order.setOfferId(won.offerId());
        order.setOrderType(OrderType.AUCTION); order.setReferenceId(won.auctionId());
        order.setSubtotal(amount); order.setTotalAmount(amount); order.setPaymentDueAt(group.getDueAt());
        order.setCreatedAt(now); order.setUpdatedAt(now);
        var item = new OrderItem();
        item.setOrder(order); item.setProductId(won.productId()); item.setSkuId(won.skuId());
        item.setSkuCode(won.skuCode());
        item.setProductName(won.title()); item.setImageUrl(won.imageUrl()); item.setQuantity(won.quantity());
        item.setUnitPrice(amount); item.setTotalPrice(amount); order.getItems().add(item);
        pricing.snapshotCosts(java.util.List.of(order));
        group.getOrders().add(order); groups.saveAndFlush(group);
        var entry = new OrderStatusHistory();
        entry.setOrderId(order.getId()); entry.setNewStatus(OrderStatus.PENDING);
        entry.setChangedBy(won.winnerId()); entry.setCreatedAt(now); entry.setReason("Order created from auction");
        history.save(entry);
        events.orderCreated(order);
        // Payment chỉ nhận manifest sau khi người thắng xác nhận địa chỉ và phí cuối cùng.
    }
}
