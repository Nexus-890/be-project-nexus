package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PostgreSQL thật kiểm tra transaction Commerce; biên Inventory/Shipping được mock riêng có chủ đích. */
@SpringJUnitConfig(OrderReadTest.JpaConfig.class)
class OrderReservationIntegrationTest {
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest(
            new CreateOrderRequest.ShippingAddress("Buyer", "0900000138", "138 Main Street",
                    "1003646", "1000001", "VN"), "SEPAY");
    @Autowired OrderService orders;
    @Autowired CatalogClient catalog;
    @Autowired InventoryClient inventory;
    @Autowired OrderShippingService shipping;
    @Autowired OrderEventPublisher events;
    @Autowired DataSource source;
    private OffsetDateTime expiry;

    @BeforeEach void dependencies() {
        reset(catalog, inventory, shipping, events);
        expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MICROS);
        when(inventory.reserve(any(), anyList())).thenAnswer(call -> new InventoryClient.Reservation(
                ((List<?>) call.getArgument(1)).stream().map(ignored -> UUID.randomUUID()).toList(), WAREHOUSE, expiry));
        when(shipping.quote(any(), anyList())).thenReturn(new OrderShippingService.Quote(
                new BigDecimal("25000.00"), new OrderShippingService.Parcel(1000, 20, 15, 10),
                "GHN", "STANDARD", expiry.plusDays(2)));
    }

    @Test void reserveOneCompleteGroupAfterBothOrdersExistAndPersistAuthoritativeSnapshot() {
        var buyer = cart(2);
        doAnswer(call -> {
            UUID group = call.getArgument(0);
            List<InventoryClient.Item> items = call.getArgument(1);
            assertThat(count("SELECT count(*) FROM orders WHERE order_group_id = ?", group)).isEqualTo(2);
            assertThat(items).hasSize(2);
            for (var item : items) {
                assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ? AND sku_id = ? AND quantity = ?",
                        item.orderId(), item.skuId(), item.quantity())).isEqualTo(1);
            }
            return new InventoryClient.Reservation(items.stream().map(x -> UUID.randomUUID()).toList(), WAREHOUSE, expiry);
        }).when(inventory).reserve(any(), anyList());

        var group = orders.create(buyer, UUID.randomUUID().toString(), REQUEST).response();

        assertThat(group.totalAmount()).isEqualByComparingTo("350000.00");
        assertThat(group.dueAt()).isEqualTo(expiry);
        assertThat(group.orders()).allSatisfy(order -> {
            assertThat(order.orderGroupId()).isEqualTo(group.orderGroupId());
            assertThat(order.subtotal()).isEqualByComparingTo("150000.00");
            assertThat(order.shippingFee()).isEqualByComparingTo("25000.00");
            assertThat(order.totalAmount()).isEqualByComparingTo("175000.00");
            assertThat(jdbc().queryForObject("SELECT payment_due_at FROM orders WHERE id = ?", OffsetDateTime.class,
                    order.orderId())).isEqualTo(expiry);
            assertThat(jdbc().queryForObject("SELECT shipping_info->>'warehouseId' FROM orders WHERE id = ?", String.class,
                    order.orderId())).isEqualTo(WAREHOUSE.toString());
            assertThat(jdbc().queryForObject("SELECT shipping_info #>> '{recipient,address,wardCode}' FROM orders WHERE id = ?",
                    String.class, order.orderId())).isEqualTo("1003646");
            assertThat(jdbc().queryForObject("SELECT shipping_info #>> '{recipient,address,districtCode}' FROM orders WHERE id = ?",
                    String.class, order.orderId())).isNull();
            assertThat(orders.getOrder(buyer, order.orderId()).orderGroupId()).isEqualTo(group.orderGroupId());
        });
        verify(inventory).reserve(eq(group.orderGroupId()), anyList());
        var sequence = inOrder(shipping, inventory);
        sequence.verify(shipping, times(2)).quote(any(), anyList());
        sequence.verify(inventory).reserve(any(), anyList());
        assertThat(orders.listOwn(buyer, null, 0, 10).getContent()).allSatisfy(order ->
                assertThat(order.orderGroupId()).isEqualTo(group.orderGroupId()));
    }

    @Test void eachSellerOrderStoresTheWarehouseItsStockIsHeldIn() {
        var buyer = cart(2);
        var warehouses = new java.util.HashMap<UUID, UUID>();
        doAnswer(call -> {
            List<InventoryClient.Item> items = call.getArgument(1);
            var orders = items.stream().map(InventoryClient.Item::orderId).distinct()
                    .map(order -> new InventoryClient.OrderWarehouse(order,
                            warehouses.computeIfAbsent(order, ignored -> UUID.randomUUID()))).toList();
            return new InventoryClient.Reservation(items.stream().map(x -> UUID.randomUUID()).toList(),
                    orders.getFirst().warehouseId(), expiry, orders);
        }).when(inventory).reserve(any(), anyList());

        var group = orders.create(buyer, UUID.randomUUID().toString(), REQUEST).response();

        assertThat(warehouses).hasSize(2);
        assertThat(group.orders()).allSatisfy(order -> assertThat(jdbc().queryForObject(
                "SELECT shipping_info->>'warehouseId' FROM orders WHERE id = ?", String.class, order.orderId()))
                .isEqualTo(warehouses.get(order.orderId()).toString()));
    }

    @Test void aSellerWarehousePickupIsQuotedAndStoredOnlyWhenTheStockIsHeldThere() {
        var buyer = cart(2);
        var otherWarehouse = UUID.randomUUID();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(inventory.pickup(any(), anyList())).thenAnswer(call -> java.util.Optional.of(new OrderShippingService.Pickup(
                calls.getAndIncrement() == 0 ? WAREHOUSE : otherWarehouse, "201", "20309", "12 Kim Mã")));
        when(shipping.offer(any(), anyList(), any())).thenAnswer(call -> new OrderShippingService.Offer(
                new OrderShippingService.Parcel(1000, 20, 15, 10), "STANDARD", List.of(new OrderShippingService.Option(
                "GHN", "GHN", new BigDecimal("31000.00"), expiry.plusDays(2), true)), call.getArgument(2)));

        var group = orders.create(buyer, UUID.randomUUID().toString(), REQUEST).response();

        verify(inventory, times(2)).pickup(any(), anyList());
        verify(shipping, never()).quote(any(), anyList());
        assertThat(group.orders()).allSatisfy(order -> assertThat(order.shippingFee()).isEqualByComparingTo("31000.00"));
        var stored = group.orders().stream().map(order -> jdbc().queryForObject(
                "SELECT shipping_info->>'pickupAddress' FROM orders WHERE id = ?", String.class, order.orderId())).toList();
        assertThat(stored).filteredOn(java.util.Objects::nonNull).singleElement().satisfies(pickup -> {
            assertThat(pickup).contains("\"line1\": \"12 Kim Mã\"");
            assertThat(pickup).contains("\"wardCode\": \"20309\"");
            assertThat(pickup).contains("\"provinceCode\": \"201\"");
        });
        assertThat(stored).filteredOn(java.util.Objects::isNull).hasSize(1);
    }

    @Test void insufficientStockRollsBackBothSellerOrdersGroupAndCart() {
        var buyer = cart(2);
        when(inventory.reserve(any(), anyList())).thenThrow(new DomainException(CommerceErrorCode.INSUFFICIENT_STOCK,
                "SKU has one available", Map.of("skuId", UUID.randomUUID(), "available", 1, "requested", 2)));
        assertThatThrownBy(() -> orders.create(buyer, UUID.randomUUID().toString(), REQUEST))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_INSUFFICIENT_STOCK");
        assertUnchanged(buyer);
        verifyNoInteractions(events);
    }

    @Test void shippingFailureBeforeSecondShopNeverCallsInventoryOrCommitsOrders() {
        var buyer = cart(2);
        when(shipping.quote(any(), anyList())).thenReturn(new OrderShippingService.Quote(
                new BigDecimal("25000.00"), new OrderShippingService.Parcel(1000, 20, 15, 10), "GHN", "STANDARD", expiry))
                .thenThrow(new DomainException(CommerceErrorCode.SHIPPING_UNAVAILABLE));
        assertThatThrownBy(() -> orders.create(buyer, UUID.randomUUID().toString(), REQUEST))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_SHIPPING_UNAVAILABLE");
        assertUnchanged(buyer);
        verify(inventory, never()).reserve(any(), anyList());
        verifyNoInteractions(events);
    }

    @Test void inventoryTimeoutRollsBackSavedOrdersAndLeavesCartActive() {
        var buyer = cart(1);
        when(inventory.reserve(any(), anyList())).thenThrow(new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE));
        assertThatThrownBy(() -> orders.create(buyer, UUID.randomUUID().toString(), REQUEST))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_INVENTORY_UNAVAILABLE");
        assertUnchanged(buyer);
    }

    @Test void replayComparesOnlyOriginalRequestAndNeverReservesOrQuotesAgain() {
        var buyer = cart(2);
        String key = UUID.randomUUID().toString();
        var first = orders.create(buyer, key, REQUEST);
        clearInvocations(inventory, shipping);
        var replay = orders.create(buyer, key, REQUEST);
        assertThat(replay.created()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        verifyNoInteractions(inventory, shipping);
        var changed = new CreateOrderRequest(new CreateOrderRequest.ShippingAddress("Other recipient", "0900000138",
                "138 Main Street", "1003646", "1000001", "VN"), "SEPAY");
        assertThatThrownBy(() -> orders.create(buyer, key, changed)).isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("COMMERCE_IDEMPOTENCY_CONFLICT");
    }

    @Test void groupReadChecksOwnerAdminPrivilegeAndMissingGroup() {
        var buyer = cart(2);
        var group = orders.create(buyer, UUID.randomUUID().toString(), REQUEST).response();
        assertThat(orders.getGroup(buyer, group.orderGroupId()).orders()).hasSize(2);
        var admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of("ORDER.VIEW"));
        assertThat(orders.getGroup(admin, group.orderGroupId())).isEqualTo(group);
        var other = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("ORDER.VIEW"));
        assertThatThrownBy(() -> orders.getGroup(other, group.orderGroupId())).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> orders.getGroup(admin, UUID.randomUUID())).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo(404);
    }

    private CurrentUser cart(int shops) {
        var buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"),
                Set.of("CHECKOUT.START", "ORDER.CREATE", "ORDER.VIEW", "ORDER.LIST"));
        UUID cart = jdbc().queryForObject("INSERT INTO carts(user_id) VALUES (?) RETURNING id", UUID.class, buyer.id());
        for (int i = 0; i < shops; i++) {
            UUID product = UUID.randomUUID(), skuId = UUID.randomUUID(), seller = UUID.randomUUID();
            jdbc().update("INSERT INTO cart_items(cart_id,product_id,sku_id,quantity,price) VALUES(?,?,?,1,150000)", cart, product, skuId);
            var sku = new CatalogSkuResponse();
            sku.setId(skuId); sku.setProductId(product); sku.setPrice(new BigDecimal("150000.00"));
            sku.setStatus("ACTIVE"); sku.setSkuCode("SKU-" + skuId.toString().substring(0,8));
            sku.setShippingParcel(new CatalogSkuResponse.ShippingParcel(1000,20,15,10));
            when(catalog.getOrderSkuById(skuId)).thenReturn(ApiResponse.of(sku));
            when(catalog.getProductById(product)).thenReturn(ApiResponse.of(new CatalogClient.ProductResponse(
                    product,seller,"Stored product","ACTIVE",List.of())));
        }
        return buyer;
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(source); }
    private long count(String sql, Object... params) { return jdbc().queryForObject(sql, Long.class, params); }
    private void assertUnchanged(CurrentUser buyer) {
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", buyer.id())).isZero();
        assertThat(count("SELECT count(*) FROM order_groups WHERE buyer_id = ?", buyer.id())).isZero();
        assertThat(jdbc().queryForObject("SELECT status::text FROM carts WHERE user_id = ?", String.class, buyer.id())).isEqualTo("ACTIVE");
    }
}
