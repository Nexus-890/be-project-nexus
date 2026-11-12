package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderReadTest.JpaConfig.class)
class OrderPriceRevalidationTest {
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest(
            new CreateOrderRequest.ShippingAddress("Buyer", "0900000000", "1 Main Street",
                    "1003646", "1000001", "VN"), "SEPAY");

    @Autowired private OrderService orderService;
    @Autowired private PricingService pricingService;
    @Autowired private DataSource dataSource;
    @Autowired private CatalogClient catalogClient;
    @Autowired private InventoryClient inventoryClient;
    @Autowired private com.group5.commerceservice.order.service.OrderShippingService shipping;

    @BeforeEach
    void prepareCheckout() {
        // Test thay đổi giá chỉ đo giá sản phẩm, độc lập fixture phí của các test checkout khác.
        org.mockito.Mockito.reset(shipping);
        when(shipping.quote(any(), any())).thenReturn(new com.group5.commerceservice.order.service.OrderShippingService.Quote(
                BigDecimal.ZERO, new com.group5.commerceservice.order.service.OrderShippingService.Parcel(1200, 30, 20, 15),
                "FAKE", "STANDARD", java.time.OffsetDateTime.parse("2031-02-04T04:05:06Z")));
        ReflectionTestUtils.setField(pricingService, "priceRevalidation", true);
        clearInvocations(catalogClient, inventoryClient);
        when(inventoryClient.reserve(any(), any())).thenReturn(new InventoryClient.Reservation(
                List.of(UUID.randomUUID()), UUID.randomUUID(), java.time.OffsetDateTime.parse("2031-02-03T04:05:06.123456Z")));
    }

    @AfterEach
    void restoreRevalidation() {
        ReflectionTestUtils.setField(pricingService, "priceRevalidation", true);
    }

    @Test
    void priceConflictCommitsTheCartRefreshAndAConfirmedRetryCreatesOneOrder() {
        Fixture fixture = cart(new String[][]{{"150000.00", "180000.00"}});
        String rejectedKey = UUID.randomUUID().toString();

        DomainException failure = assertThrows(DomainException.class,
                () -> orderService.create(fixture.buyer(), rejectedKey, REQUEST));

        assertThat(failure.getCode()).isEqualTo(409);
        assertThat(failure.getErrorCode()).isEqualTo("COMMERCE_PRICE_CHANGED");
        assertThat(failure.getProperties()).containsEntry("skuId", fixture.lines().getFirst().skuId());
        assertThat((BigDecimal) failure.getProperties().get("oldPrice")).isEqualByComparingTo("150000");
        assertThat((BigDecimal) failure.getProperties().get("newPrice")).isEqualByComparingTo("180000");
        assertThat(cartPrice(fixture.lines().getFirst())).isEqualByComparingTo("180000");
        assertThat(cartStatus(fixture)).isEqualTo("ACTIVE");
        assertNoOrders(fixture);
        verifyNoInteractions(inventoryClient);

        String confirmedKey = UUID.randomUUID().toString();
        OrderService.CreationResult confirmed = orderService.create(fixture.buyer(), confirmedKey, REQUEST);

        assertThat(confirmed.created()).isTrue();
        assertThat(confirmed.response().orders()).hasSize(1);
        assertThat(confirmed.response().orders().getFirst().items().getFirst().unitPrice())
                .isEqualByComparingTo("180000");
        assertThat(count("SELECT count(*) FROM order_groups WHERE buyer_id = ?", fixture.buyer().id()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", fixture.buyer().id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM order_groups WHERE idempotency_key = ?", rejectedKey)).isZero();
        assertThat(count("SELECT count(*) FROM order_groups WHERE idempotency_key = ?", confirmedKey)).isEqualTo(1);
        assertThat(cartStatus(fixture)).isEqualTo("CHECKED_OUT");
    }

    @Test
    void unchangedPricesCreateAnOrderAtTheCartPrice() {
        Fixture fixture = cart(new String[][]{{"150000.00", "150000.00"}});

        OrderService.CreationResult result = orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST);

        assertThat(result.created()).isTrue();
        assertThat(result.response().orders().getFirst().items().getFirst().unitPrice())
                .isEqualByComparingTo("150000");
        assertThat(cartStatus(fixture)).isEqualTo("CHECKED_OUT");
    }

    @Test
    void disabledRevalidationCreatesAtTheCartPriceEvenWhenTheCatalogPriceChanged() {
        ReflectionTestUtils.setField(pricingService, "priceRevalidation", false);
        Fixture fixture = cart(new String[][]{{"150000.00", "180000.00"}});

        OrderService.CreationResult result = orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST);

        assertThat(result.created()).isTrue();
        assertThat(result.response().orders().getFirst().items().getFirst().unitPrice())
                .isEqualByComparingTo("150000");
        assertThat(result.response().totalAmount()).isEqualByComparingTo("150000");
        assertThat(cartPrice(fixture.lines().getFirst())).isEqualByComparingTo("150000");
        assertThat(cartStatus(fixture)).isEqualTo("CHECKED_OUT");
    }

    @Test
    void aChangeOnALaterLineCreatesNothingAndReservesNoInventory() {
        Fixture fixture = cart(new String[][]{{"150000.00", "150000.00"}, {"200000.00", "250000.00"}});

        DomainException failure = assertThrows(DomainException.class,
                () -> orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST));

        assertThat(failure.getProperties()).containsEntry("skuId", fixture.lines().get(1).skuId());
        assertThat(cartPrice(fixture.lines().getFirst())).isEqualByComparingTo("150000");
        assertThat(cartPrice(fixture.lines().get(1))).isEqualByComparingTo("250000");
        assertThat(cartStatus(fixture)).isEqualTo("ACTIVE");
        assertNoOrders(fixture);
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void replayReturnsTheExistingOrderWithoutRevalidatingLaterPriceChanges() {
        Fixture fixture = cart(new String[][]{{"150000.00", "150000.00"}});
        String key = UUID.randomUUID().toString();
        OrderService.CreationResult first = orderService.create(fixture.buyer(), key, REQUEST);
        currentSku(fixture.lines().getFirst(), "180000.00");
        clearInvocations(catalogClient, inventoryClient);

        OrderService.CreationResult replay = orderService.create(fixture.buyer(), key, REQUEST);

        assertThat(replay.created()).isFalse();
        assertThat(replay.response().orderGroupId()).isEqualTo(first.response().orderGroupId());
        assertThat(replay.response().orders().getFirst().orderId())
                .isEqualTo(first.response().orders().getFirst().orderId());
        assertThat(replay.response().orders().getFirst().items().getFirst().unitPrice())
                .isEqualByComparingTo("150000");
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", fixture.buyer().id())).isEqualTo(1);
        verifyNoInteractions(catalogClient, inventoryClient);
    }

    private Fixture cart(String[][] prices) {
        CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("CHECKOUT.START", "ORDER.CREATE"));
        UUID sellerId = UUID.randomUUID();
        UUID cartId = jdbc().queryForObject("INSERT INTO carts (user_id) VALUES (?) RETURNING id", UUID.class, buyer.id());
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < prices.length; index++) {
            Line line = new Line(new UUID(cartId.getMostSignificantBits(), index + 1), UUID.randomUUID(), UUID.randomUUID());
            jdbc().update("""
                    INSERT INTO cart_items (id, cart_id, product_id, sku_id, quantity, price)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, line.itemId(), cartId, line.productId(), line.skuId(), new BigDecimal(prices[index][0]));
            currentSku(line, prices[index][1]);
            when(catalogClient.getProductById(line.productId())).thenReturn(ApiResponse.of(
                    new CatalogClient.ProductResponse(line.productId(), sellerId, "Checkout price fixture", "ACTIVE", List.of())));
            lines.add(line);
        }
        return new Fixture(buyer, cartId, lines);
    }

    private void currentSku(Line line, String price) {
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(line.skuId());
        sku.setProductId(line.productId());
        sku.setSkuCode("SKU-" + line.skuId());
        sku.setPrice(new BigDecimal(price));
        sku.setStatus("ACTIVE");
        when(catalogClient.getSkuById(line.skuId())).thenReturn(ApiResponse.of(sku));
    }

    private void assertNoOrders(Fixture fixture) {
        assertThat(count("SELECT count(*) FROM order_groups WHERE buyer_id = ?", fixture.buyer().id())).isZero();
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", fixture.buyer().id())).isZero();
        assertThat(count("""
                SELECT count(*) FROM order_status_history h
                JOIN orders o ON o.id = h.order_id WHERE o.user_id = ?
                """, fixture.buyer().id())).isZero();
    }

    private BigDecimal cartPrice(Line line) {
        return jdbc().queryForObject("SELECT price FROM cart_items WHERE id = ?", BigDecimal.class, line.itemId());
    }

    private String cartStatus(Fixture fixture) {
        return jdbc().queryForObject("SELECT status FROM carts WHERE id = ?", String.class, fixture.cartId());
    }

    private int count(String sql, Object... arguments) {
        return jdbc().queryForObject(sql, Integer.class, arguments);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Fixture(CurrentUser buyer, UUID cartId, List<Line> lines) { }
    private record Line(UUID itemId, UUID productId, UUID skuId) { }
}
