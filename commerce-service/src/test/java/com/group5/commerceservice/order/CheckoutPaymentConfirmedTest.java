package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed;
import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed.Instruction;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.event.OrderPaid;
import com.group5.commerceservice.order.event.PaymentEventConsumer;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.commerceservice.order.service.RefundStatusHandler;
import com.group5.commerceservice.order.event.OrderRefundCompleted;
import com.group5.commerceservice.order.dto.request.CancelOrderRequest;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.CurrentUser;
import com.group5.common_core.model.ApiResponse;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Kiểm thử PostgreSQL thật cho chuyển PAID, chống xử lý lặp, snapshot và rollback toàn nhóm đơn. */
@SpringJUnitConfig(CheckoutPaymentConfirmedTest.JpaConfig.class)
class CheckoutPaymentConfirmedTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.commerceservice.PostgresTestDatabase("postgres:18-alpine");
    private static final AtomicInteger ORDER_NUMBERS = new AtomicInteger();
    private static final UUID SYSTEM = new UUID(0, 0);
    private static final String PAID_AT = "2026-11-10T03:00:00Z";
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Schema ORDER_PAID = schema();
    static { POSTGRES.start(); }

    @Autowired private PaymentEventConsumer listener;
    @Autowired private OrderEventPublisher publisher;
    @Autowired private DataSource source;
    @Autowired private Tracer tracer;
    @Autowired private OrderService orderService;
    @Autowired private CatalogClient catalog;
    @Autowired private InventoryClient inventory;
    @Autowired private OrderShippingService shipping;

    @BeforeEach void resetPublisher() { reset(publisher, catalog, inventory, shipping); }

    /** Snapshot hai cấp do checkout thật tạo phải thanh toán được và không nhân đôi event khi replay receipt. */
    @Test
    void checkoutTwoLevelAddressPaysBothSellerOrdersAndPublishesOneOrderPaidPerOrderOnReplay() {
        Fixture fixture = checkoutWithTwoLevelAddress();
        assertThat(groupStatus(fixture)).isEqualTo("PENDING");
        assertThat(states(fixture)).containsExactly("PENDING", "PENDING");
        long createdHistory = historyCount(fixture);
        assertThat(createdHistory).isEqualTo(2);
        assertThat(paidEvents(fixture)).isEmpty();
        for (UUID order : fixture.orders()) {
            var address = JSON.readTree(jdbc().queryForObject(
                    "SELECT shipping_info::text FROM orders WHERE id = ?", String.class, order))
                    .path("recipient").path("address");
            assertThat(address.path("provinceCode").asString()).isEqualTo("1000001");
            assertThat(address.path("wardCode").asString()).isEqualTo("1003646");
            assertThat(address.has("districtCode")).isFalse();
        }

        String paidAt = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
        var instructions = fixture.orders().stream().map(order -> new Instruction(UUID.randomUUID(), order,
                jdbc().queryForObject("SELECT total_amount FROM orders WHERE id = ?", BigDecimal.class, order)
                        .toPlainString())).toList();
        var payment = envelope(new CheckoutPaymentConfirmed(UUID.randomUUID(), fixture.group(), UUID.randomUUID(),
                fixture.buyer(), "VND", "350000.00", paidAt, 1L, instructions));
        send(payment);
        // Cả replay cùng eventId và envelope mới của cùng receipt đều không ghi lại PAID/history/outbox.
        send(payment);
        var replay = envelope(payment.payload());
        send(replay);

        assertThat(groupStatus(fixture)).isEqualTo("PAID");
        assertThat(states(fixture)).containsExactly("PAID", "PAID");
        assertThat(jdbc().queryForObject("SELECT paid_at FROM order_groups WHERE id = ?", OffsetDateTime.class,
                fixture.group()).toInstant()).isEqualTo(Instant.parse(paidAt));
        assertThat(jdbc().queryForObject("SELECT payment_session_id FROM order_groups WHERE id = ?", UUID.class,
                fixture.group())).isEqualTo(payment.payload().sessionId());
        assertThat(jdbc().queryForObject("SELECT payment_transaction_id FROM order_groups WHERE id = ?", UUID.class,
                fixture.group())).isEqualTo(payment.payload().transactionId());
        assertThat(historyCount(fixture)).isEqualTo(createdHistory + 2);
        assertThat(paidEvents(fixture)).hasSize(2);
        assertThat(processed(payment.eventId())).isEqualTo(1);
        assertThat(processed(replay.eventId())).isEqualTo(1);
        for (UUID order : fixture.orders()) {
            assertThat(jdbc().queryForObject("SELECT count(*) FROM order_status_history WHERE order_id = ? "
                    + "AND previous_status = 'PENDING' AND new_status = 'PAID'", Long.class, order)).isEqualTo(1);
            assertThat(jdbc().queryForObject("SELECT paid_at FROM orders WHERE id = ?", OffsetDateTime.class, order)
                    .toInstant()).isEqualTo(Instant.parse(paidAt));
            var events = paidEvents(fixture).stream()
                    .filter(event -> event.path("aggregateId").asString().equals(order.toString())).toList();
            assertThat(events).hasSize(1);
            var payload = events.getFirst().path("payload");
            assertThat(ORDER_PAID.validate(payload.toString(), InputFormat.JSON)).isEmpty();
            assertThat(payload.path("warehouseId").asString()).isEqualTo(fixture.warehouse().toString());
            assertThat(payload.path("orderGroupId").asString()).isEqualTo(fixture.group().toString());
            var address = payload.path("shippingInfo").path("recipient").path("address");
            assertThat(address.path("provinceCode").asString()).isEqualTo("1000001");
            assertThat(address.path("wardCode").asString()).isEqualTo("1003646");
            assertThat(address.has("districtCode")).isFalse();
        }
    }

    @Test
    void paymentAndRefundRecordVoucherLifecycleExactlyOnce() {
        UUID voucher = UUID.randomUUID();
        jdbc().update("""
                INSERT INTO promo_vouchers (id, code, name, source_type, discount_type, discount_value, funding_type,
                    product_scope, claim_start_at, claim_end_at, valid_from, valid_until, status)
                VALUES (?, upper(?), 'Paid voucher', 'PLATFORM', 'FIXED', 20000, 'PLATFORM', 'ALL',
                    now() - interval '1 day', now() + interval '1 day',
                    now() - interval '1 day', now() + interval '1 day', 'ACTIVE')
                """, voucher, voucher.toString());

        Fixture fixture = checkoutWithTwoLevelAddress(voucher);
        String paidAt = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
        var instructions = fixture.orders().stream().map(id -> new Instruction(UUID.randomUUID(), id, "165000.00")).toList();
        var payment = envelope(new CheckoutPaymentConfirmed(UUID.randomUUID(), fixture.group(), UUID.randomUUID(),
                fixture.buyer(), "VND", "330000.00", paidAt, 1L, instructions));
        send(payment);
        send(payment);
        send(envelope(payment.payload()));
        assertThat(usageStatuses(voucher)).containsExactly("REDEEMED", "REDEEMED");
        assertThat(voucherEventCount(voucher, "VoucherRedeemed")).isEqualTo(2);
        assertThat(jdbc().queryForList("""
                SELECT payload->'payload'->>'discountAmount' FROM outbox
                WHERE event_type = 'VoucherRedeemed' AND payload->'payload'->>'voucherId' = ?
                  AND jsonb_typeof(payload->'payload'->'discountAmount') = 'string'
                """, String.class, voucher.toString())).containsExactly("10000.00", "10000.00");
        assertThat(walletState(voucher)).isEqualTo("USED");

        Span span = tracer.nextSpan().name("promotion-refund-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            CurrentUser actor = new CurrentUser(fixture.buyer(), Set.of("BUYER"), Set.of("ORDER.CANCEL"));
            for (int i = 0; i < instructions.size(); i++) {
                var instruction = instructions.get(i);
                UUID order = instruction.orderId();
                orderService.cancel(actor, order, new CancelOrderRequest("Refund voucher test"));
                String amount = i == 0 ? instruction.amount() : "82500.00";
                var refund = new OrderRefundCompleted(UUID.randomUUID(), order, fixture.group(), payment.payload().sessionId(),
                        instruction.instructionId(), payment.payload().transactionId(), amount, "VND", "COMPLETED",
                        "Refund voucher test", SYSTEM, 1L, 2L, paidAt, UUID.randomUUID(), Instant.now().toString());
                UUID eventId = UUID.randomUUID();
                ConsumerRecord<String, EventEnvelope<JsonNode>> record = new ConsumerRecord<>(Topics.PAYMENT, 0, 0, order.toString(),
                        new EventEnvelope<>(eventId, "OrderRefundCompleted", order.toString(), Instant.now(),
                                "promotion-refund", JSON.valueToTree(refund)));
                listener.receive(record);
                listener.receive(record);
                assertThat(jdbc().queryForObject("""
                        SELECT sum(reversed_discount_amount) FROM promo_voucher_usage_allocations a
                        JOIN promo_voucher_usages u ON a.voucher_usage_id = u.id WHERE u.order_id = ?
                        """, BigDecimal.class, order)).isEqualByComparingTo(i == 0 ? "10000" : "5000");
            }
        } finally {
            span.end();
        }
        assertThat(usageStatuses(voucher)).containsExactly("REVERSED", "REVERSED");
        assertThat(voucherEventCount(voucher, "VoucherReleased")).isZero();
        assertThat(voucherEventCount(voucher, "VoucherReversed")).isEqualTo(2);
        assertThat(walletState(voucher)).isEqualTo("USED");
    }

    private List<String> usageStatuses(UUID voucher) {
        return jdbc().queryForList("""
                SELECT u.status FROM promo_voucher_usages u
                JOIN promo_customer_vouchers c ON c.id = u.customer_voucher_id WHERE c.voucher_id = ?
                """, String.class, voucher);
    }

    private long voucherEventCount(UUID voucher, String eventType) {
        return jdbc().queryForObject("SELECT count(*) FROM outbox WHERE event_type = ? AND payload->'payload'->>'voucherId' = ?",
                Long.class, eventType, voucher.toString());
    }

    private String walletState(UUID voucher) {
        return jdbc().queryForObject("SELECT status FROM promo_customer_vouchers WHERE voucher_id = ?", String.class, voucher);
    }

    /** Chỉ mock các biên Catalog/Inventory/Shipping; OrderService, snapshot, lịch sử và outbox dùng code thật. */
    private Fixture checkoutWithTwoLevelAddress() {
        return checkoutWithTwoLevelAddress(null);
    }

    private Fixture checkoutWithTwoLevelAddress(UUID voucher) {
        var buyer = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("CHECKOUT.START", "ORDER.CREATE"));
        UUID warehouse = UUID.randomUUID();
        UUID cart = jdbc().queryForObject("INSERT INTO carts(user_id) VALUES (?) RETURNING id", UUID.class, buyer.id());
        var skus = new ArrayList<UUID>();
        for (int index = 0; index < 2; index++) {
            UUID product = UUID.randomUUID(), skuId = UUID.randomUUID();
            jdbc().update("INSERT INTO cart_items(cart_id, product_id, sku_id, quantity, price) VALUES (?, ?, ?, 1, 150000)",
                    cart, product, skuId);
            var sku = new CatalogSkuResponse();
            sku.setId(skuId);
            sku.setProductId(product);
            sku.setSkuCode("SKU-" + skuId.toString().substring(0, 8));
            sku.setPrice(new BigDecimal("150000.00"));
            sku.setStatus("ACTIVE");
            sku.setShippingParcel(new CatalogSkuResponse.ShippingParcel(1000, 20, 15, 10));
            when(catalog.getSkuById(skuId)).thenReturn(ApiResponse.of(sku));
            when(catalog.getProductById(product)).thenReturn(ApiResponse.of(new CatalogClient.ProductResponse(
                    product, UUID.randomUUID(), "Stored product " + index, "ACTIVE", List.of())));
            skus.add(skuId);
        }
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MICROS);
        when(inventory.reserve(any(), any())).thenAnswer(call -> new InventoryClient.Reservation(
                ((List<?>) call.getArgument(1)).stream().map(ignored -> UUID.randomUUID()).toList(), warehouse, expiry));
        when(shipping.quote(any(), any())).thenReturn(new OrderShippingService.Quote(new BigDecimal("25000.00"),
                new OrderShippingService.Parcel(1000, 20, 15, 10), "GHN", "STANDARD", expiry.plusDays(2)));
        var request = new CreateOrderRequest(new CreateOrderRequest.ShippingAddress(
                "Stored recipient", "0900000138", "138 Main Street", "1003646", "1000001", "VN"), "SEPAY",
                voucher == null ? null : voucher.toString(), null);
        if (voucher != null) jdbc().update("""
                INSERT INTO promo_customer_vouchers (customer_id, voucher_id, status, expires_at)
                SELECT ?, id, 'CLAIMED', valid_until FROM promo_vouchers WHERE id = ?
                """, buyer.id(), voucher);
        Span span = tracer.nextSpan().name("checkout-two-level-address-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            var group = orderService.create(buyer, UUID.randomUUID().toString(), request).response();
            assertThat(group.orders()).hasSize(2);
            assertThat(group.totalAmount()).isEqualByComparingTo(voucher == null ? "350000.00" : "330000.00");
            return new Fixture(group.orderGroupId(), buyer.id(), warehouse,
                    group.orders().stream().map(order -> order.orderId()).toList(), skus);
        } finally { span.end(); }
    }

    /** Nhóm hai đơn được thanh toán đầy đủ; từng OrderPaid đúng schema và khớp snapshot đã lưu. */
    @Test
    void completeManifestPaysBothOrdersAndPublishesSchemaValidStoredShippingSnapshots() {
        Fixture fixture = fixture();
        var event = event(fixture);
        send(event);

        assertPaid(fixture);
        assertThat(processed(event.eventId())).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT payment_session_id FROM order_groups WHERE id = ?", UUID.class,
                fixture.group())).isEqualTo(event.payload().sessionId());
        assertThat(jdbc().queryForObject("SELECT payment_transaction_id FROM order_groups WHERE id = ?", UUID.class,
                fixture.group())).isEqualTo(event.payload().transactionId());
        for (int index = 0; index < 2; index++) {
            UUID order = fixture.orders().get(index);
            JsonNode envelope = paidEvents(fixture).stream()
                    .filter(row -> row.path("aggregateId").asString().equals(order.toString())).findFirst().orElseThrow();
            JsonNode payload = envelope.path("payload");
            assertThat(ORDER_PAID.validate(payload.toString(), InputFormat.JSON,
                    context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))).isEmpty();
            assertThat(payload.path("orderId").asString()).isEqualTo(order.toString());
            assertThat(payload.path("orderGroupId").asString()).isEqualTo(fixture.group().toString());
            assertThat(payload.path("sessionId").asString()).isEqualTo(event.payload().sessionId().toString());
            assertThat(payload.path("transactionId").asString()).isEqualTo(event.payload().transactionId().toString());
            assertThat(payload.path("instructionId").asString()).isEqualTo(event.payload().instructions().get(index).instructionId().toString());
            assertThat(payload.path("paidAmount").asString()).isEqualTo(index == 0 ? "200000.00" : "150000.00");
            assertThat(payload.path("orderVersion").asLong()).isEqualTo(1)
                    .isEqualTo(jdbc().queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, order));
            assertThat(payload.path("paidAt").asString()).isEqualTo(PAID_AT);
            assertThat(payload.path("warehouseId").asString()).isEqualTo(fixture.warehouse().toString());
            assertThat(payload.path("shippingFee").asString()).isEqualTo("15000.00");
            JsonNode shipping = payload.path("shippingInfo");
            assertThat(shipping.path("recipient").path("name").asString()).isEqualTo("Stored recipient");
            var address = shipping.path("recipient").path("address");
            assertThat(address.path("wardCode").asString()).isEqualTo("WARD-156");
            assertThat(address.path("districtCode").asString()).isEqualTo("DISTRICT-156");
            assertThat(address.path("provinceCode").asString()).isEqualTo("PROVINCE-156");
            assertThat(shipping.path("items").get(0).path("productName").asString()).isEqualTo("Immutable item " + index);
            assertThat(shipping.path("items").get(0).path("skuId").asString()).isEqualTo(fixture.skus().get(index).toString());
            assertThat(shipping.path("parcel").isNull()).isTrue();
            assertThat(shipping.path("pickupAddress").isNull()).isTrue();
            assertThat(payload.path("referenceId").isNull()).isTrue();
        }
        assertThat(jdbc().queryForList("SELECT DISTINCT changed_by FROM order_status_history WHERE order_id IN (?, ?)",
                UUID.class, fixture.orders().get(0), fixture.orders().get(1))).containsExactly(SYSTEM);
    }

    /** Replay cùng eventId hoặc eventId mới không nhân đôi lịch sử/event và không lùi đơn đã giao. */
    @Test
    void sameEnvelopeAndSameReceiptWithANewEnvelopeNeverDuplicateHistoryOrRegressFulfillment() {
        Fixture fixture = fixture();
        var event = event(fixture);
        send(event);
        send(event);
        jdbc().update("UPDATE orders SET status = 'SHIPPED', version = version + 1 WHERE id = ?", fixture.orders().getFirst());
        send(new EventEnvelope<>(UUID.randomUUID(), event.eventType(), event.aggregateId(), event.occurredAt(),
                event.traceId(), event.payload()));
        assertThat(states(fixture)).containsExactly("SHIPPED", "PAID");
        assertThat(historyCount(fixture)).isEqualTo(2);
        assertThat(paidEvents(fixture)).hasSize(2);
        assertThat(processed(event.eventId())).isEqualTo(1);
    }

    /** Hai luồng nhận cùng envelope chỉ tạo một bộ tác động nghiệp vụ. */
    @Test void concurrentDuplicateEnvelopeHasOneAtomicEffect() throws Exception { race(false); }
    /** Hai eventId khác nhau cho cùng receipt vẫn chỉ thanh toán nhóm một lần. */
    @Test void concurrentDistinctEnvelopeIdsForTheSameReceiptHaveOneAtomicEffect() throws Exception { race(true); }

    /** Dùng latch cho hai consumer chạy đồng thời, kiểm chứng khóa DB thay vì gọi tuần tự. */
    private void race(boolean newIdentity) throws Exception {
        Fixture fixture = fixture();
        var first = event(fixture);
        var second = newIdentity ? new EventEnvelope<>(UUID.randomUUID(), first.eventType(), first.aggregateId(),
                first.occurredAt(), first.traceId(), first.payload()) : first;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(payment -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing start signal");
                send(payment);
                return true;
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertPaid(fixture);
        assertThat(processed(first.eventId())).isEqualTo(1);
        assertThat(processed(second.eventId())).isEqualTo(1);
    }

    /** Sai người mua, tiền, tập đơn hoặc ID trùng đều không được giữ cả dấu processed_events. */
    @Test
    void mismatchedBuyerTotalsAmountsMembershipAndDuplicateIdsRollBackTheClaim() {
        for (String mutation : List.of("buyer", "total", "amount", "missing", "foreign", "duplicate-order", "duplicate-instruction", "currency")) {
            Fixture fixture = fixture();
            var valid = event(fixture);
            var payment = valid.payload();
            var shares = new ArrayList<>(payment.instructions());
            switch (mutation) {
                case "amount" -> { shares.set(0, new Instruction(shares.get(0).instructionId(), shares.get(0).orderId(), "150000.00"));
                    shares.set(1, new Instruction(shares.get(1).instructionId(), shares.get(1).orderId(), "200000.00")); }
                case "missing" -> shares.removeLast();
                case "foreign" -> shares.set(1, new Instruction(shares.get(1).instructionId(), UUID.randomUUID(), "150000.00"));
                case "duplicate-order" -> shares.set(1, new Instruction(shares.get(1).instructionId(), shares.get(0).orderId(), "150000.00"));
                case "duplicate-instruction" -> shares.set(1, new Instruction(shares.get(0).instructionId(), shares.get(1).orderId(), "150000.00"));
                default -> { }
            }
            var invalid = new CheckoutPaymentConfirmed(payment.sessionId(), payment.orderGroupId(), payment.transactionId(),
                    mutation.equals("buyer") ? UUID.randomUUID() : payment.buyerId(), mutation.equals("currency") ? "USD" : "VND",
                    mutation.equals("total") ? "300000.00" : payment.totalAmount(), payment.paidAt(), payment.sessionVersion(), shares);
            var malformed = envelope(invalid);
            assertThatThrownBy(() -> send(malformed)).as(mutation).isInstanceOf(IllegalArgumentException.class);
            assertPending(fixture, malformed.eventId());
        }
    }

    /** Aggregate phải là sessionId và Kafka key phải là groupId trước khi có bất kỳ thay đổi DB. */
    @Test
    void wrongAggregateOrKafkaKeyChangesNothing() {
        Fixture fixture = fixture();
        var payment = event(fixture);
        var invalid = new EventEnvelope<>(payment.eventId(), payment.eventType(), UUID.randomUUID().toString(),
                payment.occurredAt(), payment.traceId(), payment.payload());
        assertThatThrownBy(() -> send(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(payment, UUID.randomUUID().toString())).isInstanceOf(IllegalArgumentException.class);
        assertPending(fixture, payment.eventId());
    }

    /** Đơn con đã hủy giữ nguyên; chỉ đơn PENDING còn đủ điều kiện được chuyển PAID. */
    @Test
    void canceledChildIsNeverResurrectedWhileTheEligibleChildIsPaid() {
        Fixture fixture = fixture();
        jdbc().update("UPDATE orders SET status = 'CANCELED' WHERE id = ?", fixture.orders().getFirst());
        send(event(fixture));
        assertThat(groupStatus(fixture)).isEqualTo("PAID");
        assertThat(states(fixture)).containsExactly("CANCELED", "PAID");
        assertThat(historyCount(fixture)).isEqualTo(1);
        assertThat(paidEvents(fixture)).hasSize(1);
    }

    /** Confirmation đến muộn không mở lại nhóm đã hủy hoặc hết hạn. */
    @Test
    void canceledAndExpiredGroupsRemainClosed() {
        for (String state : List.of("CANCELED", "EXPIRED")) {
            Fixture fixture = fixture();
            jdbc().update("UPDATE order_groups SET status = ? WHERE id = ?", state, fixture.group());
            jdbc().update("UPDATE orders SET status = 'CANCELED' WHERE order_group_id = ?", fixture.group());
            var event = event(fixture);
            send(event);
            assertThat(groupStatus(fixture)).isEqualTo(state);
            assertThat(states(fixture)).containsExactly("CANCELED", "CANCELED");
            assertThat(historyCount(fixture)).isZero();
            assertThat(paidEvents(fixture)).isEmpty();
            assertThat(processed(event.eventId())).isEqualTo(1);
        }
    }

    /** Lỗi phát event thứ hai phải hoàn tác nhóm, đơn, lịch sử, outbox và marker để có thể retry. */
    @Test
    void publisherFailureAfterWritingBothEventsRollsBackGroupChildrenHistoryAndMarkerThenRetryWorks() {
        Fixture fixture = fixture();
        var payment = event(fixture);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("forced second OrderPaid failure");
            return null;
        }).when(publisher).orderPaid(any(OrderPaid.class));
        assertThatThrownBy(() -> send(payment)).isInstanceOf(IllegalStateException.class);
        assertPending(fixture, payment.eventId());
        assertThat(jdbc().queryForObject("SELECT payment_session_id FROM order_groups WHERE id = ?", UUID.class,
                fixture.group())).isNull();
        reset(publisher);
        send(payment);
        assertPaid(fixture);
    }

    /** Snapshot thiếu dữ liệu phải rollback, sau khi bổ sung có thể xử lý lại chính event cũ. */
    @Test
    void incompleteStoredShippingCannotPublishAnInvalidOrderPaidAndCanBeRetriedAfterPreparation() {
        Fixture fixture = fixture();
        var payment = event(fixture);
        jdbc().update("UPDATE orders SET shipping_info = NULL WHERE id = ?", fixture.orders().get(1));
        assertThatThrownBy(() -> send(payment)).isInstanceOf(IllegalArgumentException.class);
        assertPending(fixture, payment.eventId());
        jdbc().update("UPDATE orders SET shipping_info = CAST(? AS jsonb) WHERE id = ?", snapshot(fixture.warehouse()), fixture.orders().get(1));
        send(payment);
        assertPaid(fixture);
    }

    /** Nhóm PAID đã gắn receipt không được chấp nhận một transaction thanh toán khác. */
    @Test
    void paidGroupRejectsAnotherReceiptInsteadOfSilentlyAcceptingIt() {
        Fixture fixture = fixture();
        var original = event(fixture);
        send(original);
        var p = original.payload();
        var different = envelope(new CheckoutPaymentConfirmed(p.sessionId(), p.orderGroupId(), UUID.randomUUID(), p.buyerId(),
                p.currency(), p.totalAmount(), p.paidAt(), p.sessionVersion(), p.instructions()));
        assertThatThrownBy(() -> send(different)).isInstanceOf(IllegalArgumentException.class);
        assertPaid(fixture);
        assertThat(processed(different.eventId())).isZero();
    }

    /** Nếu có dữ liệu kiện hàng/điểm lấy hàng/đấu giá, event phải giữ đúng snapshot đã lưu. */
    @Test
    void providedParcelPickupAndAuctionReferenceAreCopiedFromTheStoredSnapshot() {
        Fixture fixture = fixture();
        UUID auction = UUID.randomUUID();
        String snapshot = snapshot(fixture.warehouse()).replace("\"recipient\":", "\"parcel\":{\"weightGrams\":500,\"lengthCm\":10,\"widthCm\":12,\"heightCm\":8},"
                + "\"pickupAddress\":{\"line1\":\"Stored pickup\",\"wardCode\":\"PW\",\"districtCode\":\"PD\",\"provinceCode\":\"PP\"},\"recipient\":");
        jdbc().update("UPDATE orders SET shipping_info = CAST(? AS jsonb), order_type = 'AUCTION', reference_id = ? WHERE id = ?",
                snapshot, auction, fixture.orders().getFirst());
        send(event(fixture));
        JsonNode payload = paidEvents(fixture).stream().filter(e -> e.path("aggregateId").asString()
                .equals(fixture.orders().getFirst().toString())).findFirst().orElseThrow().path("payload");
        assertThat(payload.path("referenceId").asString()).isEqualTo(auction.toString());
        assertThat(payload.path("shippingInfo").path("parcel").path("weightGrams").asInt()).isEqualTo(500);
        assertThat(payload.path("shippingInfo").path("pickupAddress").path("line1").asString()).isEqualTo("Stored pickup");
        assertThat(ORDER_PAID.validate(payload.toString(), InputFormat.JSON)).isEmpty();
    }

    private void send(EventEnvelope<CheckoutPaymentConfirmed> payment) { send(payment, payment.payload().orderGroupId().toString()); }

    /** Gọi listener với payload JSON và trace giống biên nhận Kafka để kiểm thử cả bước chuyển kiểu. */
    private void send(EventEnvelope<CheckoutPaymentConfirmed> payment, String key) {
        Span span = tracer.nextSpan().name("checkout-confirmation-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            listener.receive(new ConsumerRecord<>(Topics.PAYMENT, 0, 0, key,
                    new EventEnvelope<>(payment.eventId(), payment.eventType(), payment.aggregateId(), payment.occurredAt(),
                            payment.traceId(), JSON.valueToTree(payment.payload()))));
        } finally { span.end(); }
    }

    private EventEnvelope<CheckoutPaymentConfirmed> event(Fixture fixture) {
        return envelope(new CheckoutPaymentConfirmed(UUID.randomUUID(), fixture.group(), UUID.randomUUID(), fixture.buyer(),
                "VND", "350000.00", PAID_AT, 7L,
                List.of(new Instruction(UUID.randomUUID(), fixture.orders().get(0), "200000.00"),
                        new Instruction(UUID.randomUUID(), fixture.orders().get(1), "150000.00"))));
    }

    private EventEnvelope<CheckoutPaymentConfirmed> envelope(CheckoutPaymentConfirmed payment) {
        return new EventEnvelope<>(UUID.randomUUID(), "CheckoutPaymentConfirmed", payment.sessionId().toString(),
                Instant.now(), "156payment", payment);
    }

    /** Tạo nhóm độc lập gồm hai đơn và snapshot hoàn chỉnh, không dựa vào dữ liệu dịch vụ ngoài. */
    private Fixture fixture() {
        UUID buyer = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID group = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, 350000.00, '2026-11-11T00:00:00Z') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString());
        var orders = new ArrayList<UUID>();
        var skus = new ArrayList<UUID>();
        for (int i = 0; i < 2; i++) {
            UUID sku = UUID.randomUUID();
            String amount = i == 0 ? "200000.00" : "150000.00";
            UUID order = jdbc().queryForObject("""
                    INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, subtotal,
                                        shipping_fee, total_amount, shipping_info, idempotency_key)
                    VALUES (?, ?, ?, ?, 'STANDARD', CAST(? AS numeric) - 15000, 15000, CAST(? AS numeric), CAST(? AS jsonb), ?)
                    RETURNING id
                    """, UUID.class, group, buyer, UUID.randomUUID(), "NX-PAY-%08d".formatted(ORDER_NUMBERS.incrementAndGet()),
                    amount, amount, snapshot(warehouse), UUID.randomUUID().toString());
            jdbc().update("""
                    INSERT INTO order_items (order_id, product_id, sku_id, product_name, quantity, unit_price, total_price)
                    VALUES (?, ?, ?, ?, 1, CAST(? AS numeric) - 15000, CAST(? AS numeric) - 15000)
                    """, order, UUID.randomUUID(), sku, "Immutable item " + i, amount, amount);
            orders.add(order);
            skus.add(sku);
        }
        return new Fixture(group, buyer, warehouse, orders, skus);
    }

    private String snapshot(UUID warehouse) {
        return JSON.writeValueAsString(Map.of("warehouseId", warehouse, "recipient", Map.of("name", "Stored recipient",
                "phone", "0900000156", "address", Map.of("line1", "Stored address", "wardCode", "WARD-156",
                        "districtCode", "DISTRICT-156", "provinceCode", "PROVINCE-156")),
                "items", List.of(Map.of("productName", "Untrusted duplicate item list", "quantity", 999))));
    }

    private void assertPaid(Fixture fixture) {
        assertThat(groupStatus(fixture)).isEqualTo("PAID");
        assertThat(states(fixture)).containsExactly("PAID", "PAID");
        assertThat(historyCount(fixture)).isEqualTo(2);
        assertThat(paidEvents(fixture)).hasSize(2);
        Instant paidAt = jdbc().queryForObject("SELECT paid_at FROM order_groups WHERE id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), fixture.group());
        assertThat(paidAt).isEqualTo(Instant.parse(PAID_AT));
        assertThat(jdbc().queryForList("SELECT paid_at FROM orders WHERE order_group_id = ?", OffsetDateTime.class,
                fixture.group())).allSatisfy(time -> assertThat(time.toInstant()).isEqualTo(Instant.parse(PAID_AT)));
    }

    private void assertPending(Fixture fixture, UUID eventId) {
        assertThat(groupStatus(fixture)).isEqualTo("PENDING");
        assertThat(states(fixture)).containsExactly("PENDING", "PENDING");
        assertThat(historyCount(fixture)).isZero();
        assertThat(paidEvents(fixture)).isEmpty();
        assertThat(processed(eventId)).isZero();
    }

    private String groupStatus(Fixture fixture) {
        return jdbc().queryForObject("SELECT status FROM order_groups WHERE id = ?", String.class, fixture.group());
    }

    private List<String> states(Fixture fixture) {
        return jdbc().queryForList("SELECT status::text FROM orders WHERE order_group_id = ? ORDER BY order_number",
                String.class, fixture.group());
    }

    private long historyCount(Fixture fixture) {
        return jdbc().queryForObject("SELECT count(*) FROM order_status_history WHERE order_id IN (?, ?)",
                Long.class, fixture.orders().get(0), fixture.orders().get(1));
    }

    private List<JsonNode> paidEvents(Fixture fixture) {
        return jdbc().queryForList("SELECT payload::text FROM outbox WHERE aggregate_id IN (?, ?) AND event_type = 'OrderPaid'",
                String.class, fixture.orders().get(0).toString(), fixture.orders().get(1).toString()).stream().map(JSON::readTree).toList();
    }

    private long processed(UUID id) {
        return jdbc().queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Long.class, id, CheckoutPaymentConfirmedConsumer.CONSUMER);
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(source); }
    private record Fixture(UUID group, UUID buyer, UUID warehouse, List<UUID> orders, List<UUID> skus) { }

    /** Đọc trực tiếp schema OrderPaid trong tài liệu contract, tránh tự viết một schema khác trong test. */
    private static Schema schema() {
        try {
            String document = Files.readString(Path.of("../docs/api/events.md"));
            var section = Pattern.compile("(?ms)^### OrderPaid\\s*\\n(.*?)(?=^### |\\z)").matcher(document);
            if (!section.find()) throw new IllegalStateException("Missing OrderPaid contract");
            var schema = Pattern.compile("(?s)```json\\s*\\n(.*?)```").matcher(section.group(1));
            if (!schema.find()) throw new IllegalStateException("Missing OrderPaid JSON schema");
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(schema.group(1));
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {OrderRepository.class, CartRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({com.group5.commerceservice.promotion.PromotionTestConfig.class, OrderStateMachine.class, CheckoutPaymentConfirmedConsumer.class, PaymentUnreconciledConsumer.class, PaymentEventConsumer.class,
            RefundStatusHandler.class, OrderService.class, PricingService.class})
    /** Flyway/JPA/outbox thật trên PostgreSQL; mock tracer và các biên Catalog/Inventory/Shipping, spy publisher để gây lỗi. */
    static class JpaConfig {
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean JsonMapper jsonMapper() { return JSON; }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean CatalogClient catalogClient() { return Mockito.mock(CatalogClient.class); }
        @Bean InventoryClient inventoryClient() { return Mockito.mock(InventoryClient.class); }
        @Bean OrderShippingService orderShippingService() { return Mockito.mock(OrderShippingService.class); }
        @Bean Tracer tracer() {
            Tracer tracer = Mockito.mock(Tracer.class);
            ThreadLocal<Span> current = new ThreadLocal<>();
            when(tracer.currentSpan()).thenAnswer(invocation -> current.get());
            when(tracer.nextSpan()).thenAnswer(invocation -> {
                Span span = Mockito.mock(Span.class, Mockito.RETURNS_SELF);
                TraceContext context = Mockito.mock(TraceContext.class);
                when(context.traceId()).thenReturn(UUID.randomUUID().toString().replace("-", ""));
                when(span.context()).thenReturn(context);
                return span;
            });
            when(tracer.withSpan(any(Span.class))).thenAnswer(invocation -> {
                Span previous = current.get();
                current.set(invocation.getArgument(0));
                return (Tracer.SpanInScope) () -> { if (previous == null) current.remove(); else current.set(previous); };
            });
            return tracer;
        }
        @Bean DataSource dataSource() {
            String url = POSTGRES.getJdbcUrl();
            String separator = url.contains("?") ? "&" : "?";
            String user = POSTGRES.getUsername();
            String password = POSTGRES.getPassword();
            DataSource source = new DriverManagerDataSource(url + separator + "currentSchema=commerce_confirmation", user, password);
            Flyway.configure().dataSource(source).schemas("commerce_confirmation").defaultSchema("commerce_confirmation").load().migrate();
            return source;
        }
        @Bean IdempotentConsumer idempotentConsumer(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory), new TransactionTemplate(transactions));
        }
        @Bean OrderEventPublisher orderEventPublisher(DataSource source, PlatformTransactionManager transactions, Tracer tracer) {
            return Mockito.spy(new OrderEventPublisher(new OutboxWriter(new JdbcTemplate(source), JSON, transactions),
                    new EventEnvelopeFactory(tracer, Clock.systemUTC())));
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName(), Cart.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
