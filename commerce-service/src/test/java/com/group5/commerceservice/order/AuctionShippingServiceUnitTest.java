package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.order.client.*;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.commerceservice.order.entity.*;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.*;
import com.group5.commerceservice.order.service.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests tập trung tổng tiền, quyền sở hữu, trạng thái và chống phát manifest lần hai. */
class AuctionShippingServiceUnitTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final ShippingAddress ADDRESS = new ShippingAddress("Winner", "0900000000", "1 Main Street", "1003646", "1000001", "VN");
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderGroupRepository groups = mock(OrderGroupRepository.class);
    private final InventoryReservationHttpClient inventory = mock(InventoryReservationHttpClient.class);
    private final PricingService pricing = mock(PricingService.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final AuctionShippingService service = new AuctionShippingService(orders, groups, inventory,
            new OrderRemoteExecutor(5, 30000), pricing, events, VALIDATORS.getValidator(), entities);
    private final UUID warehouse = UUID.randomUUID();
    private final CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("CHECKOUT.START"));
    private final OrderGroup group = new OrderGroup();
    private final Order order = new Order();

    @AfterAll static void closeValidator() { VALIDATORS.close(); }

    @BeforeEach void fixture() {
        group.setBuyerId(buyer.id()); group.setTotalAmount(new BigDecimal("900000.00"));
        group.setDueAt(OffsetDateTime.now().plusDays(1));
        order.setOrderGroup(group); order.setUserId(buyer.id()); order.setOrderType(OrderType.AUCTION);
        order.setSubtotal(new BigDecimal("900000.00")); order.setTotalAmount(order.getSubtotal());
        order.setPaymentDueAt(group.getDueAt()); group.getOrders().add(order);
        var item = new OrderItem(); item.setSkuId(UUID.randomUUID()); item.setProductId(UUID.randomUUID());
        order.getItems().add(item);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(groups.lockStatus(group.getId())).thenReturn(Optional.of("PENDING"));
        when(inventory.linked(order.getId())).thenReturn(ApiResponse.of(List.of(
                new InventoryReservationHttpClient.LinkedReservation(UUID.randomUUID(), warehouse, "HELD"))));
        when(pricing.auctionShippingQuote(any(), any(), any(), any(), any())).thenReturn(quote("30000.00"));
    }

    @Test void persistsExactTotalsAndSnapshotBeforeRequestingPayment() {
        var result = service.confirm(buyer, order.getId(), ADDRESS);
        assertThat(result.totalAmount()).isEqualByComparingTo("930000.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo(result.totalAmount());
        assertThat(group.getTotalAmount()).isEqualByComparingTo(result.totalAmount());
        assertThat(result.dueAt()).isEqualTo(order.getPaymentDueAt());
        assertThat(order.getShippingInfo()).containsEntry("warehouseId", warehouse.toString());
        assertThat(order.getShippingInfo().get("recipient")).isEqualTo(new CreateOrderRequest(ADDRESS, "SEPAY").snapshot().get("recipient"));
        var sequence = inOrder(groups, orders, entities, events);
        sequence.verify(groups).lockStatus(group.getId());
        sequence.verify(orders).lockGroupOrders(group.getId());
        sequence.verify(entities).refresh(order); sequence.verify(entities).refresh(group);
        sequence.verify(groups).saveAndFlush(group);
        sequence.verify(events).checkoutPaymentRequested(group);
        verify(inventory, never()).reserve(any());
    }

    @Test void sameNormalizedAddressReplaysStoredResultWithoutRemoteCallsOrWrites() {
        service.confirm(buyer, order.getId(), ADDRESS);
        clearInvocations(inventory, pricing, events, groups);
        var repeated = new ShippingAddress(" Winner ", "0900000000", " 1 Main Street ", "1003646", "1000001", "vn");
        assertThat(service.confirm(buyer, order.getId(), repeated).totalAmount()).isEqualByComparingTo("930000.00");
        verifyNoInteractions(inventory, pricing, events);
        verify(groups, never()).saveAndFlush(any());
    }

    @Test void changedAddressCannotRepriceExistingPaymentPlan() {
        order.setShippingInfo(new HashMap<>(new CreateOrderRequest(ADDRESS, "SEPAY").snapshot()));
        var changed = new ShippingAddress("Other", "0900000000", "1 Main Street", "1003646", "1000001", "VN");
        assertCode(() -> service.confirm(buyer, order.getId(), changed), "COMMERCE_AUCTION_SHIPPING_CONFLICT");
        verifyNoInteractions(inventory, pricing, events);
    }

    @Test void ownerAndBuyerRoleAreRequiredEvenForAdmin() {
        for (var caller : List.of(new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of()),
                new CurrentUser(buyer.id(), Set.of("ADMIN"), Set.of()))) {
            assertCode(() -> service.confirm(caller, order.getId(), ADDRESS), "COMMERCE_FORBIDDEN");
        }
        verifyNoInteractions(inventory, pricing, events);
        verify(groups, never()).lockStatus(any());
    }

    @Test void anonymousCallerAndInvalidAddressFailBeforeRemoteCalls() {
        assertCode(() -> service.confirm(null, order.getId(), ADDRESS), "COMMERCE_UNAUTHENTICATED");
        assertCode(() -> service.confirm(buyer, order.getId(), null), "COMMERCE_INVALID_SHIPPING_ADDRESS");
        verifyNoInteractions(inventory, pricing, events);
    }

    @ParameterizedTest @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void nonPendingOrderNeverChangesPaymentPlan(OrderStatus status) {
        order.setStatus(status);
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_AUCTION_SHIPPING_CONFLICT");
        verifyNoInteractions(inventory, pricing, events);
    }

    @ParameterizedTest @ValueSource(strings = {"PAID", "CANCELED", "EXPIRED"})
    void closedGroupNeverReopens(String status) {
        group.setStatus(status);
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_AUCTION_SHIPPING_CONFLICT");
        verifyNoInteractions(inventory, pricing, events);
    }

    @Test void expiredOrStandardOrderCannotConfirm() {
        order.setOrderType(OrderType.STANDARD);
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_AUCTION_SHIPPING_CONFLICT");
        order.setOrderType(OrderType.AUCTION); group.setDueAt(OffsetDateTime.now().minusSeconds(1));
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_AUCTION_SHIPPING_CONFLICT");
        verifyNoInteractions(inventory, pricing, events);
    }

    @ParameterizedTest @ValueSource(strings = {"CONFIRMED", "RELEASED", "EXPIRED"})
    void holdMustStillBeHeld(String status) {
        when(inventory.linked(order.getId())).thenReturn(ApiResponse.of(List.of(
                new InventoryReservationHttpClient.LinkedReservation(UUID.randomUUID(), warehouse, status))));
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_AUCTION_HOLD_NOT_LINKED");
        verifyNoInteractions(pricing, events);
    }

    @Test void missingHoldIsConflictAndUnavailableInventoryIsRetryable() {
        when(inventory.linked(order.getId())).thenReturn(ApiResponse.of(List.of()));
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_AUCTION_HOLD_NOT_LINKED");
        when(inventory.linked(order.getId())).thenReturn(null);
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_INVENTORY_UNAVAILABLE");
        assertThat(order.getShippingInfo()).isNull(); verifyNoInteractions(pricing, events);
    }

    @Test void unrepresentableTotalCannotMutateOrderOrPublishManifest() {
        order.setSubtotal(new BigDecimal("99999999999999999.99"));
        assertCode(() -> service.confirm(buyer, order.getId(), ADDRESS), "COMMERCE_INVALID_ORDER_AMOUNT");
        assertThat(order.getShippingInfo()).isNull(); verifyNoInteractions(events);
        verify(groups, never()).saveAndFlush(any());
    }

    private OrderShippingService.Quote quote(String fee) {
        return new OrderShippingService.Quote(new BigDecimal(fee), new OrderShippingService.Parcel(1200, 30, 20, 15),
                "GHN", "STANDARD", OffsetDateTime.now().plusDays(1));
    }
    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
