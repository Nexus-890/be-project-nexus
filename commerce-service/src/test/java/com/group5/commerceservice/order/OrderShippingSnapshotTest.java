package com.group5.commerceservice.order;

import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderShippingSnapshotTest {
    @Mock private OrderRepository orders;
    @InjectMocks private OrderService service;
    private final CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("ORDER.VIEW"));

    @Test
    void currentCheckoutSnapshotProvidesContactAlongsideOrderGroupAndAmounts() {
        var request = new CreateOrderRequest(new CreateOrderRequest.ShippingAddress(
                "  Snapshot Buyer  ", " 0901234567 ", "1 Main Street", "1003646", "1000001", "VN"), "SEPAY");
        Order order = order(request.snapshot());
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        var response = service.getOrder(buyer, order.getId());

        assertThat(response.recipientName()).isEqualTo("Snapshot Buyer");
        assertThat(response.phoneNumber()).isEqualTo("0901234567");
        assertThat(response.orderGroupId()).isEqualTo(order.getOrderGroup().getId());
        assertThat(response.subtotal()).isEqualByComparingTo("100000.00");
        assertThat(response.shippingFee()).isEqualByComparingTo("30000.00");
        assertThat(response.discountAmount()).isEqualByComparingTo("10000.00");
        assertThat(response.taxAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void legacySnapshotContactRemainsReadable() {
        Order order = order(Map.of("shippingAddress", Map.of(
                "recipientName", "  Original recipient  ", "phoneNumber", " 0901111222 ")));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));

        var response = service.getOrder(buyer, order.getId());

        assertThat(response.recipientName()).isEqualTo("Original recipient");
        assertThat(response.phoneNumber()).isEqualTo("0901111222");
    }

    @Test
    void sellerSalesUseTheCurrentImmutableRecipientSnapshot() {
        Order order = order(Map.of("recipient", Map.of("name", "Actual recipient", "phone", "0905555666")));
        var seller = new CurrentUser(order.getSellerId(), Set.of("SELLER"), Set.of("ORDER.LIST"));
        when(orders.findAll(org.mockito.ArgumentMatchers.<Specification<Order>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));

        var response = service.listSales(seller, null, null, null, 0, 10).getContent().getFirst();

        assertThat(response.recipientName()).isEqualTo("Actual recipient");
        assertThat(response.phoneNumber()).isEqualTo("0905555666");
    }

    @Test
    void absentMalformedAndBlankContactStaysUnavailableWithoutUsingAStaleLegacyShape() {
        var snapshots = new ArrayList<Map<String, Object>>();
        snapshots.add(null);
        snapshots.add(Map.of());
        snapshots.add(Map.of("recipient", "invalid"));
        snapshots.add(Map.of("recipient", Map.of("name", 42, "phone", " ")));
        snapshots.add(Map.of("shippingAddress", "invalid"));
        snapshots.add(Map.of("shippingAddress", Map.of("recipientName", 42, "phoneNumber", " ")));
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("recipient", null);
        mixed.put("shippingAddress", Map.of("recipientName", "Stale name", "phoneNumber", "0901111222"));
        snapshots.add(mixed);
        for (Map<String, Object> snapshot : snapshots) {
            Order order = order(snapshot);
            when(orders.findById(order.getId())).thenReturn(Optional.of(order));

            var response = service.getOrder(buyer, order.getId());

            assertThat(response.recipientName()).isNull();
            assertThat(response.phoneNumber()).isNull();
        }
    }

    private Order order(Map<String, Object> snapshot) {
        Order order = new Order();
        order.setOrderGroup(new OrderGroup());
        order.setUserId(buyer.id());
        order.setSellerId(UUID.randomUUID());
        order.setShippingInfo(snapshot);
        order.setSubtotal(new BigDecimal("100000.00"));
        order.setShippingFee(new BigDecimal("30000.00"));
        order.setDiscountAmount(new BigDecimal("10000.00"));
        order.setTaxAmount(new BigDecimal("5000.00"));
        order.setTotalAmount(new BigDecimal("125000.00"));
        return order;
    }
}
