package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.entity.OrderItem;
import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed;
import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed.Instruction;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.event.OrderPaid;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Kiểm tra nghiệp vụ Commerce bằng entity/validator thật; không khởi động Spring, PostgreSQL hay Kafka. */
class CheckoutPaymentConfirmedConsumerTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATORS.getValidator();
    private static final Instant PAID_AT = Instant.parse("2026-11-11T03:00:00.123456Z");
    private static final OffsetDateTime PAID_TIME = PAID_AT.atOffset(ZoneOffset.UTC);
    private static final UUID SYSTEM = new UUID(0, 0);
    private static final UUID BUYER = UUID.fromString("15600000-0000-4000-8000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("15600000-0000-4000-8000-000000000002");

    private final IdempotentConsumer idempotency = mock(IdempotentConsumer.class);
    private final OrderGroupRepository groups = mock(OrderGroupRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderStateMachine stateMachine = mock(OrderStateMachine.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Tracer tracer = mock(Tracer.class);
    private final CheckoutPaymentConfirmedConsumer consumer = new CheckoutPaymentConfirmedConsumer(
            idempotency, groups, orders, stateMachine, events, entityManager, VALIDATOR, JsonMapper.shared(), tracer,
            mock(com.group5.commerceservice.promotion.VoucherLifecycleService.class));
    private final OrderGroup group = new OrderGroup();
    private Order first;
    private Order second;
    private CheckoutPaymentConfirmed payment;

    @AfterAll static void closeValidator() { VALIDATORS.close(); }

    @BeforeEach void fixture() {
        group.setBuyerId(BUYER);
        group.setTotalAmount(new BigDecimal("350000.00"));
        group.setDueAt(PAID_TIME.plusMinutes(15));
        first = order("15600000-0000-4000-8000-000000000010", "200000.00");
        second = order("15600000-0000-4000-8000-000000000020", "150000.00");
        // Đảo thứ tự entity và instructions để event phải ghép theo orderId, không theo vị trí danh sách.
        group.setOrders(new ArrayList<>(List.of(second, first)));
        payment = payment(BUYER, "VND", "350000.00", PAID_AT.toString(), List.of(
                new Instruction(UUID.randomUUID(), second.getId(), "150000.00"),
                new Instruction(UUID.randomUUID(), first.getId(), "200000.00")));
    }

    @Test
    @DisplayName("AC1/AC8: trả đủ tiền hai đơn và phát OrderPaid từ đúng snapshot đã lưu")
    void completePlanPaysEachPendingOrderAndPublishesItsStoredSnapshot() {
        applyCallbacks();
        persistPaymentEvidence();

        receive(payment);

        assertThat(group.getStatus()).isEqualTo("PAID");
        assertThat(group.getPaidAt()).isEqualTo(PAID_TIME);
        assertThat(group.getPaymentSessionId()).isEqualTo(payment.sessionId());
        assertThat(group.getPaymentTransactionId()).isEqualTo(payment.transactionId());
        assertThat(group.getUpdatedBy()).isEqualTo(SYSTEM);
        assertThat(first.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(second.getStatus()).isEqualTo(OrderStatus.PAID);
        var captured = ArgumentCaptor.forClass(OrderPaid.class);
        verify(events, times(2)).orderPaid(captured.capture());
        assertThat(captured.getAllValues()).extracting(OrderPaid::orderId)
                .containsExactly(first.getId(), second.getId());
        for (OrderPaid event : captured.getAllValues()) {
            Order source = event.orderId().equals(first.getId()) ? first : second;
            Instruction share = payment.instructions().stream().filter(i -> i.orderId().equals(source.getId()))
                    .findFirst().orElseThrow();
            assertThat(VALIDATOR.validate(event)).isEmpty();
            assertThat(event.buyerId()).isEqualTo(BUYER);
            assertThat(event.sessionId()).isEqualTo(payment.sessionId());
            assertThat(event.transactionId()).isEqualTo(payment.transactionId());
            assertThat(event.instructionId()).isEqualTo(share.instructionId());
            assertThat(event.paidAmount()).isEqualTo(share.amount());
            assertThat(event.orderVersion()).isEqualTo(8);
            assertThat(event.paidAt()).isEqualTo(PAID_AT.toString());
            assertThat(event.warehouseId()).isEqualTo(WAREHOUSE);
            assertThat(event.shippingFee()).isEqualTo("15000.00");
            assertThat(event.shippingInfo().recipient()).isEqualTo(new OrderPaid.Recipient("Stored recipient", "0900000156",
                    new OrderPaid.Address("Stored street", "WARD-156", "DISTRICT-156", "PROVINCE-156")));
            assertThat(event.shippingInfo().items()).containsExactly(new OrderPaid.Item(
                    source.getItems().getFirst().getSkuId(), "Stored product", 2));
            assertThat(event.shippingInfo().parcel()).isNull();
            assertThat(event.shippingInfo().pickupAddress()).isNull();
            assertThat(event.referenceId()).isNull();
        }
        verify(stateMachine).move(first.getId(), OrderStatus.PAID, SYSTEM, "Checkout payment confirmed");
        verify(stateMachine).move(second.getId(), OrderStatus.PAID, SYSTEM, "Checkout payment confirmed");
    }

    @ParameterizedTest(name = "Envelope không hợp lệ: {0}")
    @ValueSource(strings = {"null-event", "event-type", "session-aggregate", "group-key", "currency", "money-format", "empty-plan", "invalid-paid-at"})
    @DisplayName("Không nhận quyền xử lý event khi contract hoặc định tuyến không hợp lệ")
    void rejectsInvalidContractBeforeIdempotentClaim(String invalid) {
        String key = group.getId().toString();
        EventEnvelope<CheckoutPaymentConfirmed> event = envelope(payment);
        switch (invalid) {
            case "null-event" -> event = null;
            case "event-type" -> event = envelope("PaymentConfirmed", payment.sessionId().toString(), payment);
            case "session-aggregate" -> event = envelope("CheckoutPaymentConfirmed", group.getId().toString(), payment);
            case "group-key" -> key = UUID.randomUUID().toString();
            case "currency" -> event = envelope(payment(BUYER, "USD", "350000.00", PAID_AT.toString(), payment.instructions()));
            case "money-format" -> event = envelope(payment(BUYER, "VND", "350000", PAID_AT.toString(), payment.instructions()));
            case "empty-plan" -> event = envelope(payment(BUYER, "VND", "350000.00", PAID_AT.toString(), List.of()));
            case "invalid-paid-at" -> event = envelope(payment(BUYER, "VND", "350000.00", "invalidZ", payment.instructions()));
            default -> throw new AssertionError(invalid);
        }
        String finalKey = key;
        EventEnvelope<CheckoutPaymentConfirmed> finalEvent = event;
        assertThatThrownBy(() -> consumer.receive(finalKey, finalEvent))
                .isInstanceOf(invalid.equals("invalid-paid-at") ? DateTimeParseException.class : IllegalArgumentException.class);
        verifyNoInteractions(idempotency, groups, orders, stateMachine, events, entityManager);
    }

    @ParameterizedTest(name = "Kế hoạch không khớp Commerce: {0}")
    @ValueSource(strings = {"buyer", "currency", "total", "zero-total", "missing-order", "foreign-order", "duplicate-order",
            "duplicate-instruction", "share-amount", "order-buyer", "stored-order-total", "no-orders"})
    @DisplayName("Từ chối toàn bộ kế hoạch sai buyer, tập đơn hoặc số tiền trước khi chuyển trạng thái")
    void rejectsAPlanThatDoesNotExactlyMatchCommerce(String mismatch) {
        applyCallbacks();
        var shares = new ArrayList<>(payment.instructions());
        CheckoutPaymentConfirmed candidate = payment;
        switch (mismatch) {
            case "buyer" -> candidate = payment(UUID.randomUUID(), "VND", "350000.00", PAID_AT.toString(), shares);
            case "currency" -> group.setCurrency("USD");
            case "total" -> candidate = payment(BUYER, "VND", "350001.00", PAID_AT.toString(), shares);
            case "zero-total" -> { group.setTotalAmount(BigDecimal.ZERO); candidate = payment(BUYER, "VND", "0.00", PAID_AT.toString(), shares); }
            case "missing-order" -> shares.removeLast();
            case "foreign-order" -> shares.set(0, new Instruction(shares.getFirst().instructionId(), UUID.randomUUID(), "150000.00"));
            case "duplicate-order" -> shares.set(0, new Instruction(shares.getFirst().instructionId(), first.getId(), "150000.00"));
            case "duplicate-instruction" -> shares.set(0, new Instruction(shares.getLast().instructionId(), second.getId(), "150000.00"));
            case "share-amount" -> { shares.set(0, new Instruction(shares.getFirst().instructionId(), second.getId(), "150001.00")); shares.set(1, new Instruction(shares.getLast().instructionId(), first.getId(), "199999.00")); }
            case "order-buyer" -> second.setUserId(UUID.randomUUID());
            case "stored-order-total" -> second.setTotalAmount(new BigDecimal("150001.00"));
            case "no-orders" -> group.getOrders().clear();
            default -> throw new AssertionError(mismatch);
        }
        if (candidate == payment) candidate = payment(BUYER, "VND", "350000.00", PAID_AT.toString(), shares);
        CheckoutPaymentConfirmed rejected = candidate;

        assertThatThrownBy(() -> receive(rejected)).isInstanceOf(IllegalArgumentException.class);

        assertThat(group.getStatus()).isEqualTo("PENDING");
        assertThat(group.getPaidAt()).isNull();
        assertThat(group.getPaymentSessionId()).isNull();
        assertThat(group.getPaymentTransactionId()).isNull();
        assertThat(first.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(groups, never()).saveAndFlush(any());
        verifyNoInteractions(stateMachine, events, entityManager);
        verify(orders, never()).recordPaidAt(any(), any());
    }

    @Test
    @DisplayName("Nhóm không tồn tại không tạo trạng thái PAID hoặc OrderPaid")
    void unknownGroupCannotAuthorizeOrderPayment() {
        applyCallbacks();
        when(groups.lockStatus(group.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receive(payment)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown order group");

        verifyNoInteractions(orders, stateMachine, events, entityManager);
        verify(groups, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "Nhóm đóng: {0}")
    @ValueSource(strings = {"CANCELED", "EXPIRED"})
    @DisplayName("Confirmation đến sau không mở lại nhóm đã hủy hoặc hết hạn")
    void closedGroupKeepsItsStateAndDoesNotEmitPaidOrders(String status) {
        group.setStatus(status);
        applyCallbacks();

        receive(payment);

        assertThat(group.getStatus()).isEqualTo(status);
        assertThat(group.getPaidAt()).isNull();
        verify(groups, never()).saveAndFlush(any());
        verifyNoInteractions(stateMachine, events, entityManager);
    }

    @Test
    @DisplayName("AC3: cùng receipt với eventId mới không lùi fulfillment hoặc tạo lại OrderPaid")
    void sameReceiptWithNewEnvelopePreservesShippedAndCanceledOrders() {
        bindAcceptedReceipt();
        first.setStatus(OrderStatus.SHIPPED);
        second.setStatus(OrderStatus.CANCELED);
        first.setShippingInfo(null);
        second.setShippingInfo(null);
        OffsetDateTime auditedAt = PAID_TIME.minusHours(1);
        group.setUpdatedAt(auditedAt);
        applyCallbacks();

        receive(payment);
        receive(payment);

        assertThat(first.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(second.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(group.getUpdatedAt()).isEqualTo(auditedAt);
        assertThat(group.getPaidAt()).isEqualTo(PAID_TIME);
        verify(groups, never()).saveAndFlush(any());
        verifyNoInteractions(stateMachine, events, entityManager);
        verify(orders, never()).recordPaidAt(any(), any());
    }

    @ParameterizedTest(name = "Bằng chứng PAID không khớp: {0}")
    @ValueSource(strings = {"other-session", "other-receipt", "other-time", "missing-session", "missing-receipt", "missing-time"})
    @DisplayName("Nhóm PAID không chấp nhận receipt khác hoặc tự suy diễn payment identity cho dữ liệu cũ")
    void paidGroupRequiresTheSameCompletePaymentEvidence(String mismatch) {
        bindAcceptedReceipt();
        switch (mismatch) {
            case "other-session" -> group.setPaymentSessionId(UUID.randomUUID());
            case "other-receipt" -> group.setPaymentTransactionId(UUID.randomUUID());
            case "other-time" -> group.setPaidAt(PAID_TIME.plusNanos(1000));
            case "missing-session" -> group.setPaymentSessionId(null);
            case "missing-receipt" -> group.setPaymentTransactionId(null);
            case "missing-time" -> group.setPaidAt(null);
            default -> throw new AssertionError(mismatch);
        }
        applyCallbacks();

        assertThatThrownBy(() -> receive(payment)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different payment receipt");

        verify(groups, never()).saveAndFlush(any());
        verifyNoInteractions(stateMachine, events, entityManager);
    }

    @Test
    @DisplayName("Thời điểm trả tiền đúng deadline vẫn hợp lệ, không dựa vào giờ consumer nhận event")
    void fundingAtTheStoredDeadlineRemainsPayable() {
        group.setDueAt(PAID_TIME);
        applyCallbacks();
        persistPaymentEvidence();

        receive(payment);

        assertThat(group.getStatus()).isEqualTo("PAID");
        assertThat(group.getPaidAt()).isEqualTo(PAID_TIME);
        verify(events, times(2)).orderPaid(any());
    }

    @Test
    @DisplayName("Tiền về sau deadline không chuyển bất kỳ đơn nào sang PAID")
    void fundingAfterTheStoredDeadlineIsRejectedBeforeAnyTransition() {
        group.setDueAt(PAID_TIME.minusNanos(1000));
        applyCallbacks();

        assertThatThrownBy(() -> receive(payment)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after the group deadline");

        assertThat(group.getStatus()).isEqualTo("PENDING");
        verify(groups, never()).saveAndFlush(any());
        verifyNoInteractions(stateMachine, events, entityManager);
    }

    @Test
    @DisplayName("Đơn đã hủy không được nhận lại PAID khi nhóm còn một đơn cần thanh toán")
    void canceledChildIsNotResurrectedOrRequiredToHaveAPaidSnapshot() {
        first.setStatus(OrderStatus.CANCELED);
        first.setShippingInfo(null);
        applyCallbacks();
        persistPaymentEvidence();

        receive(payment);

        assertThat(first.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(first.getPaidAt()).isNull();
        assertThat(second.getStatus()).isEqualTo(OrderStatus.PAID);
        var captured = ArgumentCaptor.forClass(OrderPaid.class);
        verify(events).orderPaid(captured.capture());
        assertThat(captured.getValue().orderId()).isEqualTo(second.getId());
        verify(stateMachine, never()).move(eq(first.getId()), any(), any(), any());
        verify(orders, never()).recordPaidAt(eq(first.getId()), any());
    }

    @Test
    @DisplayName("Không phát OrderPaid nếu không ghi được paid_at của đơn")
    void missingFirstPaymentWritePropagatesBeforePublishingOrderPaid() {
        applyCallbacks();
        when(orders.recordPaidAt(first.getId(), PAID_TIME)).thenReturn(0);

        assertThatThrownBy(() -> receive(payment)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has payment evidence");

        verifyNoInteractions(events);
        verify(stateMachine, never()).move(eq(second.getId()), any(), any(), any());
    }

    @ParameterizedTest(name = "Snapshot không hợp lệ: {0}")
    @ValueSource(strings = {"missing-warehouse", "missing-recipient", "missing-locality", "no-items", "zero-quantity", "buyer-is-seller", "standard-with-reference", "auction-without-reference"})
    @DisplayName("Không phát OrderPaid từ snapshot thiếu dữ liệu hoặc định danh đơn sai")
    void invalidStoredSnapshotCannotProduceAnOrderPaid(String invalid) {
        applyCallbacks();
        persistPaymentEvidence();
        switch (invalid) {
            case "missing-warehouse" -> first.setShippingInfo(Map.of());
            case "missing-recipient" -> first.setShippingInfo(Map.of("warehouseId", WAREHOUSE));
            case "missing-locality" -> first.setShippingInfo(Map.of("warehouseId", WAREHOUSE, "recipient",
                    Map.of("name", "Stored recipient", "phone", "0900000156", "address", Map.of("line1", "Street"))));
            case "no-items" -> first.getItems().clear();
            case "zero-quantity" -> first.getItems().getFirst().setQuantity(0);
            case "buyer-is-seller" -> first.setSellerId(BUYER);
            case "standard-with-reference" -> first.setReferenceId(UUID.randomUUID());
            case "auction-without-reference" -> first.setOrderType(OrderType.AUCTION);
            default -> throw new AssertionError(invalid);
        }

        assertThatThrownBy(() -> receive(payment)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Trace tự tạo được kết thúc khi xử lý confirmation thất bại")
    void fallbackTraceIsClosedWhenApplicationRejectsTheEvent() {
        applyCallbacks();
        when(tracer.currentSpan()).thenReturn(null);
        Span span = mock(Span.class, RETURNS_SELF);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        group.setDueAt(PAID_TIME.minusSeconds(1));

        assertThatThrownBy(() -> receive(payment)).isInstanceOf(IllegalArgumentException.class);

        verify(scope).close();
        verify(span).end();
        verifyNoInteractions(events);
    }

    /** Chỉ chạy callback nghiệp vụ; khả năng chống trùng/rollback của DB thuộc các integration test hiện có. */
    private void applyCallbacks() {
        when(tracer.currentSpan()).thenReturn(mock(Span.class));
        when(groups.lockStatus(group.getId())).thenAnswer(ignored -> Optional.of(group.getStatus()));
        when(groups.findById(group.getId())).thenReturn(Optional.of(group));
        when(idempotency.handle(eq(CheckoutPaymentConfirmedConsumer.CONSUMER), any(), any())).thenAnswer(invocation -> {
            EventEnvelope<CheckoutPaymentConfirmed> event = invocation.getArgument(1);
            Consumer<EventEnvelope<CheckoutPaymentConfirmed>> handler = invocation.getArgument(2);
            handler.accept(event);
            return true;
        });
    }

    /** Tách dữ liệu đã lưu khỏi entity: payload chỉ thấy version/paid_at mới sau EntityManager.refresh. */
    private void persistPaymentEvidence() {
        Map<UUID, StoredOrder> persisted = new HashMap<>();
        group.getOrders().forEach(order -> persisted.put(order.getId(),
                new StoredOrder(order.getStatus(), order.getVersion(), order.getPaidAt())));
        when(stateMachine.move(any(), eq(OrderStatus.PAID), eq(SYSTEM), any())).thenAnswer(invocation -> {
            UUID orderId = invocation.getArgument(0);
            StoredOrder before = persisted.get(orderId);
            persisted.put(orderId, new StoredOrder(OrderStatus.PAID, before.version() + 1, before.paidAt()));
            return null;
        });
        when(orders.recordPaidAt(any(), any())).thenAnswer(invocation -> {
            UUID orderId = invocation.getArgument(0);
            StoredOrder before = persisted.get(orderId);
            persisted.put(orderId, new StoredOrder(before.status(), before.version(), invocation.getArgument(1)));
            return 1;
        });
        doAnswer(invocation -> {
            Order managed = invocation.getArgument(0);
            StoredOrder stored = persisted.get(managed.getId());
            managed.setStatus(stored.status());
            managed.setVersion(stored.version());
            managed.setPaidAt(stored.paidAt());
            return null;
        }).when(entityManager).refresh(any(Order.class));
    }

    private record StoredOrder(OrderStatus status, long version, OffsetDateTime paidAt) { }

    private void bindAcceptedReceipt() {
        group.setStatus("PAID");
        group.setPaidAt(PAID_TIME);
        group.setPaymentSessionId(payment.sessionId());
        group.setPaymentTransactionId(payment.transactionId());
    }

    private void receive(CheckoutPaymentConfirmed payload) { consumer.receive(group.getId().toString(), envelope(payload)); }

    private CheckoutPaymentConfirmed payment(UUID buyer, String currency, String total, String paidAt, List<Instruction> shares) {
        return new CheckoutPaymentConfirmed(UUID.fromString("15600000-0000-4000-8000-000000000030"), group.getId(),
                UUID.fromString("15600000-0000-4000-8000-000000000040"), buyer, currency, total, paidAt, 1L, shares);
    }

    private EventEnvelope<CheckoutPaymentConfirmed> envelope(CheckoutPaymentConfirmed payload) {
        return envelope("CheckoutPaymentConfirmed", payload.sessionId().toString(), payload);
    }

    private EventEnvelope<CheckoutPaymentConfirmed> envelope(String type, String aggregate, CheckoutPaymentConfirmed payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, aggregate, PAID_AT, "0123456789abcdef0123456789abcdef", payload);
    }

    /** Snapshot có cả items giả trong JSON để chứng minh consumer chỉ tin danh sách order_items đã lưu. */
    private Order order(String id, String amount) {
        Order order = new Order();
        order.setId(UUID.fromString(id));
        order.setOrderGroup(group);
        order.setUserId(BUYER);
        order.setSellerId(UUID.randomUUID());
        order.setOrderNumber("NX-UNIT-" + id.substring(id.length() - 4));
        order.setTotalAmount(new BigDecimal(amount));
        order.setShippingFee(new BigDecimal("15000.00"));
        order.setVersion(7);
        order.setShippingInfo(Map.of("warehouseId", WAREHOUSE, "recipient",
                Map.of("name", "Stored recipient", "phone", "0900000156", "address",
                        Map.of("line1", "Stored street", "wardCode", "WARD-156", "districtCode", "DISTRICT-156", "provinceCode", "PROVINCE-156")),
                "items", List.of(Map.of("productName", "Untrusted JSON item", "quantity", 99))));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setSkuId(UUID.randomUUID());
        item.setProductName("Stored product");
        item.setQuantity(2);
        order.getItems().add(item);
        return order;
    }
}
