package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewRequest;
import com.group5.commerceservice.order.dto.request.CancelOrderRequest;
import com.group5.commerceservice.promotion.VoucherCheckoutService;
import com.group5.commerceservice.shared.config.CheckoutProperties;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.group5.commerceservice.promotion.PromotionTestConfig;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderCreationOutboxTest.JpaConfig.class)
class OrderCreationOutboxTest {
    private static final String LOCAL_DB = System.getProperty("promotion.test.jdbc-url");
    private static final PostgreSQLContainer POSTGRES = LOCAL_DB == null ? new PostgreSQLContainer("postgres:18-alpine") : null;
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest(new CreateOrderRequest.ShippingAddress(
            "Buyer", "0900000000", "1 Main Street", "1003646", "1000001", "VN"), "SEPAY");

    static {
        if (POSTGRES != null) POSTGRES.start();
    }

    @Autowired private OrderService orderService;
    @Autowired private CartPricingService cartPricing;
    @Autowired private OrderEventPublisher publisher;
    @Autowired private CatalogClient catalogClient;
    @Autowired private InventoryClient inventoryClient;
    @Autowired private DataSource dataSource;
    private long outboxBefore;

    @BeforeEach
    void prepareCheckout() {
        reset(publisher, catalogClient, inventoryClient);
        when(inventoryClient.reserve(any(), any())).thenReturn(new InventoryClient.Reservation(
                List.of(UUID.randomUUID()), UUID.randomUUID(), java.time.OffsetDateTime.parse("2031-02-03T04:05:06.123456Z")));
        outboxBefore = count("SELECT count(*) FROM outbox");
    }

    @Test
    void twoSellerOrdersCommitTwoCreationEventsAndOneCompletePaymentManifest() {
        Fixture fixture = cart();

        OrderService.CreationResult result = orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST);

        assertThat(result.created()).isTrue();
        UUID groupId = result.response().orderGroupId();
        List<EventRow> rows = events(fixture.buyer().id());
        assertThat(rows).hasSize(3);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(outboxBefore + 3);
        assertThat(rows.stream().filter(row -> row.type().equals("OrderCreated"))).hasSize(2);
        assertThat(rows.stream().filter(row -> row.type().equals("CheckoutPaymentRequested"))).hasSize(1);
        assertThat(count("SELECT count(*) FROM orders WHERE order_group_id = ?", groupId)).isEqualTo(2);

        for (EventRow row : rows) {
            assertThat(row.topic()).isEqualTo("nexus.commerce.events");
            assertThat(row.version()).isEqualTo(1);
            assertThat(row.envelope().get("eventId").asString()).isEqualTo(row.id().toString());
            assertThat(row.envelope().get("eventType").asString()).isEqualTo(row.type());
            assertThat(row.envelope().get("aggregateId").asString()).isEqualTo(row.aggregateId());
            assertThat(row.envelope().get("traceId").asString()).isEqualTo("118checkout");
            if (row.type().equals("OrderCreated")) {
                assertOrderCreated(row, groupId, fixture.buyer().id());
            } else {
                assertManifest(row, groupId, fixture.buyer().id());
            }
        }
        assertThat(cartStatus(fixture)).isEqualTo("CHECKED_OUT");
    }

    @Test
    void replayKeepsTheSameOrderAndOutboxEventIds() {
        Fixture fixture = cart();
        String key = UUID.randomUUID().toString();
        OrderService.CreationResult first = orderService.create(fixture.buyer(), key, REQUEST);
        List<UUID> eventIds = events(fixture.buyer().id()).stream().map(EventRow::id).sorted().toList();

        OrderService.CreationResult replay = orderService.create(fixture.buyer(), key, REQUEST);

        assertThat(replay.created()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(events(fixture.buyer().id()).stream().map(EventRow::id).sorted().toList()).isEqualTo(eventIds);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(outboxBefore + 3);
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", fixture.buyer().id())).isEqualTo(2);
    }

    @Test
    void aFailureAfterAllCreationEventsWereWrittenRollsBackOrdersCartAndOutbox() {
        Fixture fixture = cart();
        AtomicBoolean wroteManifest = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(events(fixture.buyer().id())).hasSize(3);
            wroteManifest.set(true);
            throw new IllegalStateException("Fail after the manifest insert");
        }).when(publisher).checkoutPaymentRequested(any(OrderGroup.class));

        assertThatThrownBy(() -> orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST))
                .isInstanceOf(IllegalStateException.class).hasMessage("Fail after the manifest insert");

        assertThat(wroteManifest).isTrue();
        assertNoCreation(fixture);
        assertThat(count("SELECT count(*) FROM cart_items WHERE cart_id = ?", fixture.cartId())).isEqualTo(3);
    }

    @Test
    void aRefusedReservationLeavesNoCreationEventOrOrder() {
        Fixture fixture = cart();
        when(inventoryClient.reserve(any(), any())).thenThrow(new DomainException(CommerceErrorCode.INSUFFICIENT_STOCK));

        assertThatThrownBy(() -> orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_INSUFFICIENT_STOCK");

        assertNoCreation(fixture);
    }

    @Test
    void aPriceConflictRefreshesTheCartWithoutWritingCreationEvents() {
        Fixture fixture = cart();
        Line changed = fixture.lines().getFirst();
        currentSku(changed, new BigDecimal("180000.00"));

        assertThatThrownBy(() -> orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_PRICE_CHANGED");

        assertNoCreation(fixture);
        assertThat(jdbc().queryForObject("SELECT price FROM cart_items WHERE sku_id = ?", BigDecimal.class, changed.skuId()))
                .isEqualByComparingTo("180000.00");
    }

    @Test
    void reservationDeadlineReplacesInsertDefaultsBeforeManifestIsPublished() {
        jdbc().execute("""
                CREATE FUNCTION checkout_test_deadline() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF TG_TABLE_NAME = 'order_groups' THEN
                        NEW.due_at := '2031-02-03T04:05:06.123456Z'::timestamptz;
                    ELSE
                        NEW.payment_due_at := '2031-02-03T04:06:07.654321Z'::timestamptz;
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc().execute("CREATE TRIGGER checkout_test_group BEFORE INSERT ON order_groups FOR EACH ROW EXECUTE FUNCTION checkout_test_deadline()");
        jdbc().execute("CREATE TRIGGER checkout_test_order BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION checkout_test_deadline()");
        try {
            Fixture fixture = cart();
            UUID groupId = orderService.create(fixture.buyer(), UUID.randomUUID().toString(), REQUEST).response().orderGroupId();
            EventRow manifest = events(fixture.buyer().id()).stream()
                    .filter(row -> row.type().equals("CheckoutPaymentRequested")).findFirst().orElseThrow();

            assertManifest(manifest, groupId, fixture.buyer().id());
            assertThat(manifest.envelope().get("payload").get("dueAt").asString())
                    .isEqualTo("2031-02-03T04:05:06.123456Z");
            assertThat(manifest.envelope().get("payload").get("orders").valueStream()
                    .map(order -> order.get("paymentDueAt").asString()).toList())
                    .containsOnly("2031-02-03T04:05:06.123456Z");
        } finally {
            jdbc().execute("DROP TRIGGER checkout_test_order ON orders");
            jdbc().execute("DROP TRIGGER checkout_test_group ON order_groups");
            jdbc().execute("DROP FUNCTION checkout_test_deadline()");
        }
    }

    @Test
    void keepsPricingPreviewAndOrderInSyncAndPersistsEveryAllocation() {
        Fixture fixture = cart();
        UUID voucher = voucher(null, null);
        claim(voucher, fixture.buyer().id());
        List<String> codes = List.of(voucher.toString());

        var pricing = cartPricing.pricing(fixture.buyer(), null, codes);
        var preview = cartPricing.previewCheckout(fixture.buyer(),
                new CheckoutPreviewRequest(REQUEST.shippingAddress(), REQUEST.paymentMethod(), null, codes));
        String idempotencyKey = UUID.randomUUID().toString();
        var request = new CreateOrderRequest(REQUEST.shippingAddress(), REQUEST.paymentMethod(), null, codes);
        var placed = orderService.create(fixture.buyer(), idempotencyKey, request);
        UUID group = placed.response().orderGroupId();
        assertThat(pricing.discountAmount()).isEqualByComparingTo("10");
        assertThat(preview.discountAmount()).isEqualByComparingTo(pricing.discountAmount());
        assertThat(placed.response().totalAmount()).isEqualByComparingTo(preview.total());
        assertThat(placed.response().orders()).allSatisfy(order -> {
            assertThat(order.vouchers()).singleElement().satisfies(entry -> {
                assertThat(entry.code()).isEqualTo(voucher.toString().toUpperCase(java.util.Locale.ROOT));
                assertThat(entry.discountAmount()).isEqualByComparingTo(order.discountAmount());
                assertThat(entry.platformFunded()).isEqualByComparingTo(order.discountAmount());
                assertThat(entry.sellerFunded()).isZero();
            });
        });
        assertThat(orderService.listOwn(fixture.buyer(), null, 0, 10).getContent())
                .containsExactlyInAnyOrderElementsOf(placed.response().orders());
        assertThat(count("""
                SELECT count(*) FROM promo_voucher_usages u JOIN orders o ON u.order_id = o.id
                WHERE o.order_group_id = ? AND u.status = 'RESERVED' AND u.reserved_until = o.payment_due_at
                """, group)).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM promo_voucher_usage_allocations a
                JOIN promo_voucher_usages u ON a.voucher_usage_id = u.id JOIN orders o ON u.order_id = o.id
                WHERE o.order_group_id = ? AND a.discount_amount = a.seller_funded_amount + a.platform_funded_amount
                """, group)).isEqualTo(3);
        assertThat(jdbc().queryForObject("""
                SELECT sum(discount_amount) FROM promo_voucher_usage_allocations
                WHERE order_item_id IN (
                    SELECT i.id FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.order_group_id = ?)
                """, BigDecimal.class, group)).isEqualByComparingTo("10");

        var replay = orderService.create(fixture.buyer(), idempotencyKey,
                new CreateOrderRequest(REQUEST.shippingAddress(), REQUEST.paymentMethod(), voucher.toString(), null));
        assertThat(replay.created()).isFalse();
        assertThat(replay.response()).isEqualTo(placed.response());
        assertThatThrownBy(() -> orderService.create(fixture.buyer(), idempotencyKey, REQUEST))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo("COMMERCE_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void fiftyCheckoutTransactionsCannotOversellUsageOrDiscountBudget() throws Exception {
        checkConcurrency(10, null, 10);
        checkConcurrency(50, new BigDecimal("73"), 7);
    }

    @Test
    void sharedVoucherCheckoutsReachSlowInventoryTogetherAndReleaseTheQuotaLosers() throws Exception {
        UUID voucher = voucher(10, null);
        List<Fixture> buyers = IntStream.range(0, 20).mapToObj(i -> cart(false)).toList();
        buyers.forEach(fixture -> claim(voucher, fixture.buyer().id()));
        var atInventory = new CountDownLatch(buyers.size());
        var heldOrders = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
        when(inventoryClient.reserve(any(), any())).thenAnswer(call -> {
            List<InventoryClient.Item> items = call.getArgument(1);
            items.forEach(item -> heldOrders.add(item.orderId()));
            atInventory.countDown();
            assertThat(atInventory.await(12, TimeUnit.SECONDS))
                    .as("all checkouts reach Inventory before any takes voucher quota").isTrue();
            Thread.sleep(200);
            return new InventoryClient.Reservation(items.stream().map(item -> UUID.randomUUID()).toList(),
                    UUID.randomUUID(), java.time.OffsetDateTime.now().plusMinutes(15));
        });
        var request = new CreateOrderRequest(REQUEST.shippingAddress(), REQUEST.paymentMethod(), voucher.toString(), null);
        int created = 0;
        try (var workers = Executors.newFixedThreadPool(buyers.size())) {
            var attempts = buyers.stream().map(fixture -> workers.submit((Callable<Boolean>) () -> {
                try {
                    return orderService.create(fixture.buyer(), UUID.randomUUID().toString(), request).created();
                } catch (DomainException failure) {
                    assertThat(failure.getErrorCode()).isEqualTo("COMMERCE_VOUCHER_NOT_APPLICABLE");
                    return false;
                }
            })).toList();
            for (var attempt : attempts) if (attempt.get(30, TimeUnit.SECONDS)) created++;
        }
        assertThat(created).isEqualTo(10);
        assertThat(heldOrders).hasSize(20);
        var releases = jdbc().queryForList("""
                SELECT aggregate_id::uuid FROM outbox
                WHERE event_type = 'CheckoutStockReleaseRequested'
                """, UUID.class).stream().filter(heldOrders::contains).toList();
        assertThat(releases).hasSize(10).doesNotHaveDuplicates();
        for (UUID order : releases) {
            assertThat(count("SELECT count(*) FROM orders WHERE id = ?", order)).isZero();
        }
        assertThat(count("SELECT committed_uses FROM promo_vouchers WHERE id = ?", voucher)).isEqualTo(10);
    }

    @Test
    void cancellationAndExpiryReleaseReservationsAndRetainAValidClaim() {
        var buyer = cart(false).buyer();
        UUID voucher = voucher(1, BigDecimal.TEN);
        claim(voucher, buyer.id());
        var request = new CreateOrderRequest(REQUEST.shippingAddress(), REQUEST.paymentMethod(), voucher.toString(), null);
        var order = orderService.create(buyer, UUID.randomUUID().toString(), request).response().orders().getFirst();

        orderService.cancel(buyer, order.orderId(), new CancelOrderRequest("Buyer changed their mind"));
        assertThat(usageStatus(order.orderId())).isEqualTo("RELEASED");
        assertThat(voucherEvents(order.orderId(), "VoucherReleased")).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT status FROM promo_customer_vouchers WHERE voucher_id = ? AND customer_id = ?",
                String.class, voucher, buyer.id())).isEqualTo("CLAIMED");

        var expiring = cart(false).buyer();
        claim(voucher, expiring.id());
        var expiringOrder = orderService.create(expiring, UUID.randomUUID().toString(), request).response().orders().getFirst();
        jdbc().update("UPDATE order_groups SET due_at = now() - interval '1 second' WHERE id = ?", expiringOrder.orderGroupId());
        jdbc().update("UPDATE orders SET payment_due_at = now() - interval '1 second' WHERE id = ?", expiringOrder.orderId());
        jdbc().update("UPDATE promo_voucher_usages SET reserved_until = now() - interval '1 second' WHERE order_id = ?",
                expiringOrder.orderId());
        assertThat(orderService.expire(expiringOrder.orderGroupId())).containsExactly(expiringOrder.orderId());
        assertThat(usageStatus(expiringOrder.orderId())).isEqualTo("EXPIRED");
        assertThat(voucherEvents(expiringOrder.orderId(), "VoucherReleased")).isEqualTo(1);
        assertThat(count("SELECT committed_uses FROM promo_vouchers WHERE id = ?", voucher)).isZero();
        assertThat(orderService.expire(expiringOrder.orderGroupId())).isEmpty();
    }

    private void checkConcurrency(int usageLimit, BigDecimal budget, int expected) throws Exception {
        UUID voucher = voucher(usageLimit, budget);
        List<Fixture> buyers = IntStream.range(0, 50).mapToObj(i -> cart(false)).toList();
        buyers.forEach(fixture -> claim(voucher, fixture.buyer().id()));
        var request = new CreateOrderRequest(REQUEST.shippingAddress(), REQUEST.paymentMethod(), voucher.toString(), null);
        var start = new CountDownLatch(1);
        long created = 0;
        try (var workers = Executors.newFixedThreadPool(50)) {
            var attempts = buyers.stream().map(fixture -> workers.submit((Callable<Boolean>) () -> {
                start.await();
                try {
                    return orderService.create(fixture.buyer(), UUID.randomUUID().toString(), request).created();
                } catch (DomainException rejected) {
                    assertThat(rejected.getErrorCode()).isEqualTo("COMMERCE_VOUCHER_NOT_APPLICABLE");
                    return false;
                }
            })).toList();
            start.countDown();
            for (var attempt : attempts) {
                if (attempt.get(90, TimeUnit.SECONDS)) created++;
            }
        }
        assertThat(created).isEqualTo(expected);
        assertThat(count("SELECT count(*) FROM promo_voucher_usages u JOIN promo_customer_vouchers c ON u.customer_voucher_id = c.id "
                + "WHERE c.voucher_id = ? AND u.status = 'RESERVED'", voucher)).isEqualTo(expected);
        assertThat(jdbc().queryForObject("SELECT committed_discount FROM promo_vouchers WHERE id = ?", BigDecimal.class, voucher))
                .isEqualByComparingTo(BigDecimal.TEN.multiply(BigDecimal.valueOf(expected)));
    }

    private UUID voucher(Integer usageLimit, BigDecimal budget) {
        UUID id = UUID.randomUUID();
        jdbc().update("""
                INSERT INTO promo_vouchers (id, code, name, source_type, discount_type, discount_value, funding_type,
                      product_scope, usage_limit, discount_budget, claim_start_at, claim_end_at, valid_from, valid_until, status)
                VALUES (?, upper(?), 'Checkout voucher', 'PLATFORM', 'FIXED', 10, 'PLATFORM', 'ALL', ?, ?,
                      now() - interval '1 day', now() + interval '1 day',
                      now() - interval '1 day', now() + interval '1 day', 'ACTIVE')
                """, id, id.toString(), usageLimit, budget);
        return id;
    }

    private void claim(UUID voucher, UUID buyer) {
        jdbc().update("""
                INSERT INTO promo_customer_vouchers (customer_id, voucher_id, status, expires_at)
                SELECT ?, id, 'CLAIMED', valid_until FROM promo_vouchers WHERE id = ?
                """, buyer, voucher);
    }

    private String usageStatus(UUID order) {
        return jdbc().queryForObject("SELECT status FROM promo_voucher_usages WHERE order_id = ?", String.class, order);
    }

    private long voucherEvents(UUID order, String type) {
        return count("SELECT count(*) FROM outbox WHERE event_type = ? AND payload->'payload'->>'orderId' = ?",
                type, order.toString());
    }

    private void assertOrderCreated(EventRow row, UUID groupId, UUID buyerId) {
        UUID orderId = UUID.fromString(row.aggregateId());
        Map<String, Object> stored = jdbc().queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        JsonNode payload = row.envelope().get("payload");
        assertThat(row.aggregateType()).isEqualTo("Order");
        assertThat(payload.size()).isEqualTo(9);
        assertThat(payload.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(payload.get("buyerId").asString()).isEqualTo(buyerId.toString());
        assertThat(payload.get("orderNumber").asString()).isEqualTo(stored.get("order_number"));
        assertThat(payload.get("orderType").asString()).isEqualTo("STANDARD");
        assertThat(payload.get("referenceId").isNull()).isTrue();
        assertThat(payload.get("orderGroupId").asString()).isEqualTo(groupId.toString());
        assertThat(payload.get("sellerId").asString()).isEqualTo(stored.get("seller_id").toString());
        assertThat(payload.get("orderVersion").asLong()).isEqualTo(((Number) stored.get("version")).longValue());
        List<String> products = jdbc().queryForList("SELECT DISTINCT product_id::text FROM order_items WHERE order_id = ?",
                String.class, orderId);
        assertThat(payload.get("productIds").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrderElementsOf(products);
    }

    private void assertManifest(EventRow row, UUID groupId, UUID buyerId) {
        JsonNode payload = row.envelope().get("payload");
        assertThat(row.aggregateType()).isEqualTo("OrderGroup");
        assertThat(row.aggregateId()).isEqualTo(groupId.toString());
        assertThat(payload.size()).isEqualTo(8);
        assertThat(payload.get("orderGroupId").asString()).isEqualTo(groupId.toString());
        assertThat(payload.get("buyerId").asString()).isEqualTo(buyerId.toString());
        assertThat(payload.get("currency").asString()).isEqualTo("VND");
        assertThat(payload.get("occurredAt").asString()).isEqualTo(row.envelope().get("occurredAt").asString());
        assertThat(payload.get("occurredAt").asString()).endsWith("Z");
        assertThat(Instant.parse(payload.get("dueAt").asString())).isEqualTo(deadline("order_groups", "due_at", groupId));
        List<UUID> storedIds = jdbc().queryForList("SELECT id FROM orders WHERE order_group_id = ?", UUID.class, groupId);
        List<JsonNode> shares = payload.get("orders").valueStream().toList();
        assertThat(payload.get("orderCount").asInt()).isEqualTo(storedIds.size());
        assertThat(shares).hasSize(storedIds.size());
        assertThat(shares.stream().map(share -> UUID.fromString(share.get("orderId").asString())).toList())
                .containsExactlyInAnyOrderElementsOf(storedIds);
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode share : shares) {
            UUID orderId = UUID.fromString(share.get("orderId").asString());
            Map<String, Object> stored = jdbc().queryForMap("SELECT seller_id, total_amount, version FROM orders WHERE id = ?", orderId);
            assertThat(share.size()).isEqualTo(5);
            assertThat(share.get("sellerId").asString()).isEqualTo(stored.get("seller_id").toString());
            assertThat(share.get("amount").isString()).isTrue();
            assertThat(share.get("amount").asString()).isEqualTo(((BigDecimal) stored.get("total_amount")).toPlainString());
            assertThat(share.get("paymentDueAt").asString()).endsWith("Z");
            assertThat(Instant.parse(share.get("paymentDueAt").asString())).isEqualTo(deadline("orders", "payment_due_at", orderId));
            assertThat(share.get("orderVersion").asLong()).isEqualTo(((Number) stored.get("version")).longValue());
            sum = sum.add(new BigDecimal(share.get("amount").asString()));
        }
        assertThat(payload.get("totalAmount").isString()).isTrue();
        assertThat(payload.get("totalAmount").asString())
                .isEqualTo(jdbc().queryForObject("SELECT total_amount FROM order_groups WHERE id = ?", BigDecimal.class, groupId).toPlainString());
        assertThat(new BigDecimal(payload.get("totalAmount").asString())).isEqualByComparingTo(sum);
    }

    private Fixture cart() {
        return cart(true);
    }

    private Fixture cart(boolean splitByShop) {
        CurrentUser buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("CHECKOUT.START", "ORDER.CREATE"));
        UUID cartId = jdbc().queryForObject("INSERT INTO carts (user_id) VALUES (?) RETURNING id", UUID.class, buyer.id());
        UUID firstSeller = UUID.randomUUID();
        UUID firstProduct = UUID.randomUUID();
        Line first = new Line(firstSeller, firstProduct, UUID.randomUUID(), new BigDecimal("100000.25"));
        List<Line> lines = splitByShop ? List.of(first,
                new Line(firstSeller, firstProduct, UUID.randomUUID(), new BigDecimal("50000.00")),
                new Line(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("200000.50")))
                : List.of(first);
        for (Line line : lines) {
            jdbc().update("INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price) VALUES (?, ?, ?, 1, ?)",
                    cartId, line.productId(), line.skuId(), line.price());
            currentSku(line, line.price());
            when(catalogClient.getProductById(line.productId())).thenReturn(ApiResponse.of(new CatalogClient.ProductResponse(
                    line.productId(), line.sellerId(), "Outbox checkout fixture", "ACTIVE", List.of())));
        }
        return new Fixture(buyer, cartId, lines);
    }

    private void currentSku(Line line, BigDecimal price) {
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(line.skuId());
        sku.setProductId(line.productId());
        sku.setSkuCode("SKU-" + line.skuId());
        sku.setPrice(price);
        sku.setStatus("ACTIVE");
        when(catalogClient.getSkuById(line.skuId())).thenReturn(ApiResponse.of(sku));
    }

    private void assertNoCreation(Fixture fixture) {
        assertThat(count("SELECT count(*) FROM order_groups WHERE buyer_id = ?", fixture.buyer().id())).isZero();
        assertThat(count("SELECT count(*) FROM orders WHERE user_id = ?", fixture.buyer().id())).isZero();
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(outboxBefore);
        assertThat(cartStatus(fixture)).isEqualTo("ACTIVE");
    }

    private List<EventRow> events(UUID buyerId) {
        return jdbc().query("""
                SELECT id, event_type, aggregate_type, aggregate_id, event_version, topic, payload::text
                FROM outbox WHERE payload->'payload'->>'buyerId' = ? ORDER BY id
                """, (rs, row) -> new EventRow(rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getInt("event_version"),
                rs.getString("topic"), JsonMapper.shared().readTree(rs.getString("payload"))), buyerId.toString());
    }

    private Instant deadline(String table, String column, UUID id) {
        return jdbc().queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), id);
    }

    private String cartStatus(Fixture fixture) {
        return jdbc().queryForObject("SELECT status FROM carts WHERE id = ?", String.class, fixture.cartId());
    }

    private long count(String sql, Object... parameters) {
        return jdbc().queryForObject(sql, Long.class, parameters);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Line(UUID sellerId, UUID productId, UUID skuId, BigDecimal price) { }
    private record Fixture(CurrentUser buyer, UUID cartId, List<Line> lines) { }
    private record EventRow(UUID id, String type, String aggregateType, String aggregateId, int version,
                            String topic, JsonNode envelope) { }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {CartRepository.class, OrderRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({PromotionTestConfig.class, OrderService.class, OrderStateMachine.class, PricingService.class})
    static class JpaConfig {
        // Fixture báo giá tách riêng khỏi transport Shipping; các test này kiểm nghiệp vụ Order hiện hữu.
        @Bean
        OrderShippingService shipping() {
            OrderShippingService shipping = Mockito.mock(OrderShippingService.class);
            Mockito.when(shipping.quote(Mockito.any(), Mockito.any())).thenReturn(new OrderShippingService.Quote(
                    BigDecimal.ZERO, new OrderShippingService.Parcel(1200, 30, 20, 15), "FAKE", "STANDARD",
                    java.time.OffsetDateTime.parse("2031-02-04T04:05:06Z")));
            return shipping;
        }
        @Bean
        DataSource dataSource() {
            String url = POSTGRES == null ? LOCAL_DB : POSTGRES.getJdbcUrl();
            String separator = url.contains("?") ? "&" : "?";
            String user = POSTGRES == null ? System.getProperty("promotion.test.jdbc-user", "postgres") : POSTGRES.getUsername();
            String password = POSTGRES == null ? System.getProperty("promotion.test.jdbc-password", "") : POSTGRES.getPassword();
            DataSource source = new DriverManagerDataSource(url + separator + "currentSchema=commerce_creation_outbox", user, password);
            Flyway.configure().dataSource(source).schemas("commerce_creation_outbox")
                    .defaultSchema("commerce_creation_outbox").load().migrate();
            return source;
        }

        @Bean
        CartPricingService cartPricing(CartRepository carts, CartItemRepository items, CatalogClient catalog,
                                       PricingService pricing, VoucherCheckoutService vouchers) {
            return new CartPricingService(carts, items, catalog, new CheckoutProperties(), pricing, vouchers);
        }

        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean CatalogClient catalogClient() { return Mockito.mock(CatalogClient.class); }
        @Bean InventoryClient inventoryClient() { return Mockito.mock(InventoryClient.class); }

        @Bean
        OrderEventPublisher orderEventPublisher(DataSource source, PlatformTransactionManager transactions) {
            Tracer tracer = Mockito.mock(Tracer.class, Mockito.RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("118checkout");
            return Mockito.spy(new OrderEventPublisher(new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions),
                    new EventEnvelopeFactory(tracer, Clock.systemUTC())));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Cart.class.getPackageName(), Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
