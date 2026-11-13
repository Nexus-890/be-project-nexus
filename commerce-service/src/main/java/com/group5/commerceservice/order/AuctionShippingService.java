package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.order.client.InventoryReservationHttpClient;
import com.group5.commerceservice.order.client.OrderRemoteExecutor;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.*;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import feign.FeignException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuctionShippingService {
    private final OrderRepository orders;
    private final OrderGroupRepository groups;
    private final InventoryReservationHttpClient inventory;
    private final OrderRemoteExecutor remote;
    private final PricingService pricing;
    private final OrderEventPublisher events;
    private final Validator validator;
    private final jakarta.persistence.EntityManager entityManager;

    public record Confirmation(UUID orderGroupId, BigDecimal shippingFee, BigDecimal totalAmount, OffsetDateTime dueAt) { }

    @Transactional
    public Confirmation confirm(CurrentUser user, UUID orderId, ShippingAddress address) {
        if (user == null || user.id() == null) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        Order initial = orders.findById(orderId).orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!initial.getUserId().equals(user.id()) || !user.hasRole("BUYER")) throw new DomainException(CommerceErrorCode.FORBIDDEN);
        if (address == null || !validator.validate(address).isEmpty()) throw new DomainException(CommerceErrorCode.INVALID_SHIPPING_ADDRESS);
        // Đồng thứ tự khóa với payment/cancel: group trước order. Reload sau khóa tránh snapshot cũ.
        groups.lockStatus(initial.getOrderGroup().getId()).orElseThrow();
        orders.lockGroupOrders(initial.getOrderGroup().getId());
        // EntityManager refresh nằm dưới để hai request cùng lúc luôn thấy snapshot đã commit.
        return confirmLocked(user, initial, address);
    }

    private Confirmation confirmLocked(CurrentUser user, Order order, ShippingAddress address) {
        entityManager.refresh(order);
        var group = order.getOrderGroup();
        entityManager.refresh(group);
        if (order.getOrderType() != OrderType.AUCTION || order.getStatus() != OrderStatus.PENDING
                || !"PENDING".equals(group.getStatus()) || group.getDueAt() == null
                || !group.getDueAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new DomainException(CommerceErrorCode.AUCTION_SHIPPING_CONFLICT);
        }
        Map<String, Object> snapshot = new CreateOrderRequest(address, "SEPAY").snapshot();
        if (order.getShippingInfo() != null) {
            if (!snapshot.entrySet().stream().allMatch(entry -> entry.getValue().equals(order.getShippingInfo().get(entry.getKey())))) {
                throw new DomainException(CommerceErrorCode.AUCTION_SHIPPING_CONFLICT);
            }
            return result(order);
        }
        UUID warehouse = remote.execute("inventory", CommerceErrorCode.INVENTORY_UNAVAILABLE, () -> {
            try {
                var response = inventory.linked(order.getId());
                if (response == null || response.code() != 0 || response.data() == null) throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
                if (response.data().isEmpty()) throw new DomainException(CommerceErrorCode.AUCTION_HOLD_NOT_LINKED);
                if (response.data().size() != 1) throw new DomainException(CommerceErrorCode.AUCTION_HOLD_NOT_LINKED);
                var hold = response.data().getFirst();
                if (hold == null || hold.reservationId() == null || hold.warehouseId() == null || !"HELD".equals(hold.status())) {
                    throw new DomainException(CommerceErrorCode.AUCTION_HOLD_NOT_LINKED);
                }
                return hold.warehouseId();
            } catch (FeignException error) { throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE); }
        });
        var item = order.getItems().getFirst();
        var quote = pricing.auctionShippingQuote(address, item.getSkuId(), item.getProductId(), warehouse, order.getSubtotal());
        BigDecimal fee = quote.fee().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal total = order.getSubtotal().add(fee);
        if (total.precision() > 19) throw new DomainException(CommerceErrorCode.INVALID_ORDER_AMOUNT);
        Map<String, Object> shipping = new HashMap<>(snapshot);
        shipping.put("warehouseId", warehouse.toString());
        shipping.put("parcel", Map.of("weightGrams", quote.parcel().weightGrams(), "lengthCm", quote.parcel().lengthCm(),
                "widthCm", quote.parcel().widthCm(), "heightCm", quote.parcel().heightCm()));
        shipping.put("carrier", quote.carrier()); shipping.put("serviceType", quote.serviceType());
        order.setShippingInfo(shipping); order.setShippingFee(fee); order.setTotalAmount(total);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        order.setUpdatedAt(now); group.setTotalAmount(total); group.setUpdatedAt(now); group.setUpdatedBy(user.id());
        groups.saveAndFlush(group);
        events.checkoutPaymentRequested(group);
        return result(order);
    }

    private Confirmation result(Order order) {
        return new Confirmation(order.getOrderGroup().getId(), order.getShippingFee(), order.getTotalAmount(), order.getPaymentDueAt());
    }
}
