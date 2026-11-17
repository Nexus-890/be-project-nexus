package com.group5.shipmentservice.shipping;

import com.group5.shipmentservice.shipping.service.ShipmentStatusService;

import com.group5.shipmentservice.shipping.event.OrderPaidShipmentConsumer;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.shipmentservice.ShipmentServiceApplication;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.dto.request.CreateShipmentRequest;
import com.group5.shipmentservice.shipping.dto.response.PaidOrderShippingResponse;
import com.group5.shipmentservice.shipping.client.CommerceOrderClient;
import com.group5.shipmentservice.shipping.client.config.CommerceResponseDecoder;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.ShipmentCreationService;
import com.group5.shipmentservice.shipping.service.ShipmentReadService;
import com.group5.shipmentservice.shipping.service.PaidOrderSnapshotService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import feign.Request;
import feign.Response;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@SpringBootTest(classes = {ShipmentServiceApplication.class, ShipmentCreationIntegrationTest.CarrierFixture.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=shipment-creation-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.main.allow-bean-definition-overriding=true",
        "nexus.security.enabled=false", "nexus.shipping.carrier=NONE", "nexus.shipping.booking-carrier=FAKE", "nexus.shipping.breaker-failure-threshold=100",
        "nexus.shipping.order-paid-consumer-enabled=false", "nexus.outbox.relay.enabled=false",
        "nexus.shipping.order-cancelled-consumer-enabled=false", "nexus.shipping.status-poll-enabled=false",
        "nexus.shipping.default-warehouse-id=00000000-0000-4000-8000-000000000099",
        "nexus.shipping.default-pickup.province-code=1000001",
        "nexus.shipping.default-pickup.ward-code=1003646",
        "nexus.shipping.default-pickup.address-line=Configured pickup warehouse",
        "nexus.shipping.tracking-retry-poll-ms=3600000", "management.tracing.export.zipkin.enabled=false"
})
class ShipmentCreationIntegrationTest {
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired OrderPaidShipmentConsumer consumer;
    @Autowired ShipmentCreationService creation;
    @Autowired ShipmentReadService reads;
    @Autowired ShipmentStatusService statuses;
    @Autowired TestCarrier carrier;
    @Autowired CarrierExecutor carrierExecutor;
    @Autowired IdempotentConsumer idempotency;
    @MockitoSpyBean OutboxWriter outbox;
    @MockitoSpyBean ShipmentRepository shipments;
    @MockitoBean PaidOrderSnapshotService paidOrders;
    @MockitoBean EventEnvelopeFactory envelopes;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE carrier_webhook_receipts, cancelled_shipment_orders, shipment_history, shipments, outbox, processed_events");
        carrier.requests.clear();
        carrier.down.set(false);
        reset(outbox, shipments, paidOrders, envelopes);
        when(envelopes.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                new EventEnvelope<>(UUID.randomUUID(), invocation.getArgument(0), invocation.getArgument(1),
                        Instant.now(), "4bf92f3577b34da6a3ce929d0e0e4736", invocation.getArgument(2)));
    }

    @Test
    void commercePayloadWithoutDispatchDetailsUsesConfiguredPickupAndBooksInOneWorkerCycle() {
        var event = commerceShapedEvent();
        var snapshot = OrderPaidSnapshot.parse(event.payload(), mapper);
        idempotency.handle(OrderPaidShipmentConsumer.CONSUMER, event, ignored -> creation.createPaid(event, snapshot));
        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments", UUID.class);
        assertThat(carrier.requests).isEmpty();

        creation.retryDueTracking();

        assertThat(carrier.requests).containsExactly(shipmentId);
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isEqualTo("FAKE-" + shipmentId);
        assertThat(jdbc.queryForObject("SELECT service_type FROM shipments", String.class)).isEqualTo("STANDARD");
        assertThat(jdbc.queryForObject("SELECT shipping_info->'pickupAddress'->>'line1' FROM shipments", String.class))
                .isEqualTo("Configured pickup warehouse");
        receive(withId(event, UUID.randomUUID()));
        assertThat(carrier.requests).containsExactly(shipmentId);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void workerRepairsMissingDispatchDetailsOfAnEarlierPendingShipmentWithoutNewIdentity() {
        var event = commerceShapedEvent();
        var snapshot = OrderPaidSnapshot.parse(event.payload(), mapper);
        idempotency.handle(OrderPaidShipmentConsumer.CONSUMER, event, ignored -> creation.createPaid(event, snapshot));
        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments", UUID.class);
        // Reproduce a row persisted by the previous implementation before the dispatch preparation fix.
        jdbc.update("UPDATE shipments SET service_type=null, shipping_info=jsonb_set(shipping_info,'{pickupAddress}','null'::jsonb)");

        creation.retryDueTracking();

        assertThat(carrier.requests).containsExactly(shipmentId);
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isEqualTo("FAKE-" + shipmentId);
        assertThat(jdbc.queryForObject("SELECT service_type FROM shipments", String.class)).isEqualTo("STANDARD");
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(2);
    }

    private EventEnvelope<JsonNode> commerceShapedEvent() {
        var original = event(UUID.randomUUID(), true);
        ObjectNode payload = ((ObjectNode) original.payload()).deepCopy();
        payload.put("warehouseId", "00000000-0000-4000-8000-000000000099");
        ObjectNode info = (ObjectNode) payload.path("shippingInfo");
        info.remove("carrier");
        info.remove("serviceType");
        info.putNull("pickupAddress");
        return new EventEnvelope<>(original.eventId(), original.eventType(), original.aggregateId(),
                original.occurredAt(), original.traceId(), payload);
    }

    @Test
    void duplicateEventAndFreshEventIdForSameOrderCreateOneShipmentAndCreationFact() {
        var event = event(UUID.randomUUID(), true);

        receive(event);
        receive(event);
        receive(withId(event, UUID.randomUUID()));

        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(2);
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(carrier.requests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class))
                .isEqualTo("FAKE-" + carrier.requests.getFirst());
        JsonNode published = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox", String.class));
        assertThat(published.path("eventType").asString()).isEqualTo("ShipmentCreated");
        assertThat(published.path("aggregateId").asString()).isEqualTo(carrier.requests.getFirst().toString());
        assertThat(published.path("payload").path("orderId").asString()).isEqualTo(event.aggregateId());
        assertThat(published.path("payload").path("status").asString()).isEqualTo("PENDING_TRACKING");
        assertThat(published.path("payload").path("trackingCode").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT topic FROM outbox", String.class)).isEqualTo(Topics.SHIPMENT);
    }

    @Test
    void trackingReadsOnlyOwnedPersistedHistoryInChronologicalOrderWithStableTies() {
        var event = event(UUID.randomUUID(), true);
        receive(event);
        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments", UUID.class);
        UUID buyerId = jdbc.queryForObject("SELECT buyer_id FROM shipments", UUID.class);
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000010");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000020");
        UUID third = UUID.fromString("00000000-0000-4000-8000-000000000030");
        jdbc.update("UPDATE shipment_history SET created_at = '2026-11-12T00:00:00Z' WHERE shipment_id = ?", shipmentId);
        // Deliberately insert the tied IDs in reverse order; the read must order by timestamp and ID.
        jdbc.update("""
                INSERT INTO shipment_history (id, shipment_id, shipment_status, description, created_at, source)
                VALUES (?, ?, 'IN_TRANSIT', NULL, '2026-11-13T02:00:00Z', 'SYSTEM'),
                       (?, ?, 'SHIPPED', 'Handed to carrier', '2026-11-13T01:00:00Z', 'SYSTEM'),
                       (?, ?, 'OUT_FOR_DELIVERY', 'Delivery started', '2026-11-13T02:00:00Z', 'SYSTEM')
                """, third, shipmentId, first, shipmentId, second, shipmentId);
        CurrentUser buyer = new CurrentUser(buyerId, Set.of("BUYER"), Set.of("SHIPPING.VIEW_SHIPMENT"));

        var tracking = reads.tracking(buyer, shipmentId);

        assertThat(tracking.shipmentId()).isEqualTo(shipmentId);
        assertThat(tracking.trackingCode()).isEqualTo("FAKE-" + shipmentId);
        assertThat(tracking.history()).hasSize(5);
        assertThat(tracking.history().stream().map(history -> history.id()).toList())
                .endsWith(first, second, third);
        assertThat(tracking.history().getLast().description()).isNull();
        assertThatThrownBy(() -> reads.tracking(new CurrentUser(UUID.randomUUID(), Set.of("BUYER"),
                Set.of("SHIPPING.VIEW_SHIPMENT")), shipmentId))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_NOT_FOUND"));
    }

    @Test
    void carrierOutageCommitsPendingThenRetriesSameBookingWithoutRepeatingCreation() {
        carrier.down.set(true);
        var event = event(UUID.randomUUID(), true);

        receive(event);

        assertThat(jdbc.queryForObject("SELECT shipment_status::text FROM shipments", String.class))
                .isEqualTo("PENDING_TRACKING");
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'status' FROM outbox", String.class))
                .isEqualTo("PENDING_TRACKING");
        UUID booking = carrier.requests.getFirst();
        carrier.down.set(false);
        jdbc.update("UPDATE shipments SET next_tracking_retry_at=now()-interval '1 second'");

        creation.retryDueTracking();

        assertThat(carrier.requests).containsExactly(booking, booking);
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isEqualTo("FAKE-" + booking);
        assertThat(jdbc.queryForObject("SELECT shipment_status::text FROM shipments", String.class)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT tracking_retry_count FROM shipments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments", Object.class)).isNull();
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(3);
    }

    @Test
    void scheduledWorkerCanMakeTheInitialBookingWithoutCountingItAsARetry() {
        var event = event(UUID.randomUUID(), true);
        OrderPaidSnapshot snapshot = OrderPaidSnapshot.parse(event.payload(), mapper);
        // Simulate a consumer process stopping after commit and before its immediate wakeup.
        idempotency.handle(OrderPaidShipmentConsumer.CONSUMER, event, ignored -> creation.createPaid(event, snapshot));
        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments", UUID.class);
        assertThat(carrier.requests).isEmpty();

        creation.retryDueTracking();

        assertThat(carrier.requests).containsExactly(shipmentId);
        assertThat(jdbc.queryForObject("SELECT tracking_retry_count FROM shipments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isEqualTo("FAKE-" + shipmentId);
        assertThat(count("shipment_history")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    @Test
    void duplicatePaidEventsCannotBypassTheCarrierBackoff() {
        carrier.down.set(true);
        var event = event(UUID.randomUUID(), true);
        receive(event);
        Object retryAt = jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments", Object.class);

        receive(event);
        receive(withId(event, UUID.randomUUID()));

        assertThat(carrier.requests).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT tracking_retry_count FROM shipments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments", Object.class)).isEqualTo(retryAt);
        assertThat(count("shipment_history")).isEqualTo(2);
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackBusinessAndMarkerBeforeAnyCarrierBooking() {
        var event = event(UUID.randomUUID(), true);
        doThrow(new IllegalStateException("simulated outbox failure")).doCallRealMethod()
                .when(outbox).write(eq("Shipment"), eq(Topics.SHIPMENT), any(EventEnvelope.class));

        assertThatThrownBy(() -> receive(event)).isInstanceOf(IllegalStateException.class);

        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("processed_events")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(carrier.requests).isEmpty();

        receive(event);

        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments", UUID.class);
        assertThat(carrier.requests).containsExactly(shipmentId);
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void trackingCommitFailureKeepsCommittedShipmentAndReusesItsBookingIdentity() {
        var event = event(UUID.randomUUID(), true);
        doThrow(new IllegalStateException("simulated tracking database failure")).when(shipments).flush();

        // A booking result cannot undo the already committed creation transaction.
        receive(event);

        UUID shipmentId = jdbc.queryForObject("SELECT id FROM shipments", UUID.class);
        assertThat(carrier.requests).containsExactly(shipmentId);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shipment_status::text FROM shipments", String.class))
                .isEqualTo("PENDING_TRACKING");
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isNull();

        reset(shipments);
        creation.assignTracking(shipmentId);

        assertThat(carrier.requests).containsExactly(shipmentId, shipmentId);
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class))
                .isEqualTo("FAKE-" + shipmentId);
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(2);
    }

    @Test
    void concurrentDeliveriesSerializeByOrderAndCreateOnlyOneCarrierBooking() throws Exception {
        var event = event(UUID.randomUUID(), true);
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                EventEnvelope<JsonNode> delivery = i < 2 ? event : withId(event, UUID.randomUUID());
                futures.add(executor.submit(() -> {
                    start.await();
                    receive(delivery);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(3);
        assertThat(carrier.requests).hasSize(1);
    }

    @Test
    void pendingShipmentKeepsCarrierAndServiceChoicesImmutable() {
        var event = event(UUID.randomUUID(), true);
        carrier.down.set(true);
        receive(event);
        UUID order = UUID.fromString(event.aggregateId());
        UUID booking = carrier.requests.getFirst();

        for (CreateShipmentRequest conflicting : List.of(
                new CreateShipmentRequest(order, null, "GHN", null),
                new CreateShipmentRequest(order, null, null, "EXPRESS"))) {
            assertThatThrownBy(() -> creation.createManually("new-options", conflicting))
                    .isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).getErrorCode())
                    .isEqualTo("SHIPMENT_IDEMPOTENCY_CONFLICT");
        }

        assertThat(carrier.requests).containsExactly(booking);
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM shipments", String.class)).isNull();
        carrier.down.set(false);
        jdbc.update("UPDATE shipments SET next_tracking_retry_at=now()-interval '1 second'");
        creation.assignTracking(booking);
        assertThat(carrier.requests).containsExactly(booking, booking);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void openCircuitDefersTrackingWithoutCountingAnUnattemptedCarrierRetry() {
        carrier.down.set(true);
        receive(event(UUID.randomUUID(), true));
        Map<String, CircuitBreaker> breakers = (Map<String, CircuitBreaker>)
                ReflectionTestUtils.getField(carrierExecutor, "breakers");
        CircuitBreaker breaker = breakers.get("FAKE");
        breaker.transitionToForcedOpenState();
        try {
            carrier.down.set(false);
            jdbc.update("UPDATE shipments SET next_tracking_retry_at=now()-interval '1 second'");

            creation.retryDueTracking();

            assertThat(carrier.requests).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT tracking_retry_count FROM shipments", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT shipment_status::text FROM shipments", String.class))
                    .isEqualTo("PENDING_TRACKING");
            assertThat(count("outbox")).isEqualTo(1);
            assertThat(count("shipment_history")).isEqualTo(2);
        } finally {
            breaker.reset();
        }
    }

    @Test
    void manualReplayStoresOneCanonicalKeyOnShipmentAndRejectsDifferentKeysOrContents() {
        var event = event(UUID.randomUUID(), true);
        receive(event);
        CreateShipmentRequest request = new CreateShipmentRequest(UUID.fromString(event.aggregateId()), null, null, null);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM shipments", String.class)).isNull();

        UUID shipment = creation.createManually("manual-key", request).getId();

        assertThat(creation.createManually("manual-key", request).getId()).isEqualTo(shipment);
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM shipments", String.class)).isEqualTo("manual-key");
        assertThat(jdbc.queryForObject("SELECT idempotency_fingerprint FROM shipments", String.class)).hasSize(64);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(carrier.requests).hasSize(1);
        assertThatThrownBy(() -> creation.createManually("manual-key",
                new CreateShipmentRequest(request.orderId(), UUID.randomUUID(), null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo("SHIPMENT_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> creation.createManually("another-key", request))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo("SHIPMENT_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void manualCreationForUnknownCommerceOrderReturnsNotFoundWithoutWrites() {
        UUID orderId = UUID.randomUUID();
        when(paidOrders.load(orderId)).thenThrow(new DomainException(ShipmentErrorCode.ORDER_NOT_FOUND));
        assertThatThrownBy(() -> creation.createManually("missing-order",
                new CreateShipmentRequest(orderId, null, null, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException problem = (DomainException) error;
                    assertThat(problem.getCode()).isEqualTo(404);
                    assertThat(problem.getErrorCode()).isEqualTo("SHIPMENT_ORDER_NOT_FOUND");
                });
        assertThat(count("shipments")).isZero();
    }

    @Test
    void manualKeyCannotBeReusedForAnotherPaidOrder() {
        var first = event(UUID.randomUUID(), true);
        var second = event(UUID.randomUUID(), true);
        receive(first);
        receive(second);
        UUID firstOrder = UUID.fromString(first.aggregateId());
        UUID secondOrder = UUID.fromString(second.aggregateId());
        creation.createManually("shared-key", new CreateShipmentRequest(firstOrder, null, null, null));

        assertThatThrownBy(() -> creation.createManually("shared-key",
                new CreateShipmentRequest(secondOrder, null, null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("SHIPMENT_IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM shipments WHERE order_id=?", String.class, secondOrder))
                .isNull();
        assertThat(carrier.requests).hasSize(2);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    void invalidSnapshotAndWrongKafkaKeyAreNotMarkedOrCreated() {
        var event = event(UUID.randomUUID(), true);
        var invalidPayload = new LinkedHashMap<>(mapper.convertValue(event.payload(), Map.class));
        invalidPayload.put("shippingFee", "0");
        EventEnvelope<JsonNode> invalid = new EventEnvelope<>(event.eventId(), event.eventType(), event.aggregateId(),
                event.occurredAt(), event.traceId(), mapper.valueToTree(invalidPayload));

        assertThatThrownBy(() -> receive(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0,
                UUID.randomUUID().toString(), event))).isInstanceOf(IllegalArgumentException.class);

        assertThat(count("processed_events")).isZero();
        assertThat(count("shipments")).isZero();
        assertThat(carrier.requests).isEmpty();
    }

    @Test
    void missingPreparationCreatesPendingWithoutCallingTheCarrier() {
        receive(event(UUID.randomUUID(), false));

        assertThat(jdbc.queryForObject("SELECT shipment_status::text FROM shipments", String.class))
                .isEqualTo("PENDING_TRACKING");
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(carrier.requests).isEmpty();
    }

    @Test
    void serviceSchemaUsesOnlyServiceOwnedBusinessAndDeliveryTables() {
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname='public' AND tablename <> 'flyway_schema_history'
                ORDER BY tablename
                """, String.class))
                .containsExactly("cancelled_shipment_orders", "carrier_webhook_receipts", "outbox", "processed_events", "shipment_history", "shipments");
    }

    @Test
    void cancellationBeforeDelayedOrderPaidPreventsShipmentHistoryEventAndCarrierBooking() {
        UUID orderId = UUID.randomUUID();
        UUID cancellationId = UUID.randomUUID();
        var paid = event(orderId, true);
        statuses.cancelOrder(orderId, "Buyer requested cancellation", cancellationId, paid.traceId());

        receive(paid);
        receive(paid);

        assertThat(count("cancelled_shipment_orders")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_id FROM cancelled_shipment_orders WHERE order_id=?", UUID.class, orderId))
                .isEqualTo(cancellationId);
        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(carrier.requests).isEmpty();
    }

    @Test
    void manualCreationCannotResurrectOrderCancelledBeforeShipmentExisted() {
        UUID orderId = UUID.randomUUID();
        statuses.cancelOrder(orderId, "Buyer requested cancellation", UUID.randomUUID(),
                "4bf92f3577b34da6a3ce929d0e0e4736");

        assertManualFailure("cancelled-before-creation", new CreateShipmentRequest(orderId, null, null, null),
                "SHIPMENT_ORDER_NOT_PAID");

        assertThat(count("cancelled_shipment_orders")).isEqualTo(1);
        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("processed_events")).isZero();
        assertThat(carrier.requests).isEmpty();
        verifyNoInteractions(paidOrders);
    }

    @Test
    void manualCreationBeforeOrderPaidDeliveryCreatesTrackedShipmentAndOneCreationFact() {
        var event = event(UUID.randomUUID(), true);
        OrderPaidSnapshot snapshot = stubPaidOrder(event);
        CreateShipmentRequest request = new CreateShipmentRequest(snapshot.orderId(), null, null, null);

        var shipment = creation.createManually("new-manual", request);

        assertThat(shipment.getOrderId()).isEqualTo(snapshot.orderId());
        assertThat(shipment.getBuyerId()).isEqualTo(snapshot.buyerId());
        assertThat(shipment.getSellerId()).isEqualTo(snapshot.sellerId());
        assertThat(shipment.getShippingFee()).isEqualByComparingTo(snapshot.shippingFee());
        assertThat(shipment.getTrackingCode()).isEqualTo("FAKE-" + shipment.getId());
        assertThat(shipment.getIdempotencyKey()).isEqualTo("new-manual");
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isZero();
        assertThat(carrier.requests).containsExactly(shipment.getId());
        JsonNode creationEvent = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox", String.class));
        assertThat(creationEvent.path("payload").path("status").asString()).isEqualTo("PENDING_TRACKING");
        assertThat(creationEvent.path("payload").path("trackingCode").isNull()).isTrue();

        assertThat(creation.createManually("new-manual", request).getId()).isEqualTo(shipment.getId());
        verify(paidOrders, times(1)).load(snapshot.orderId());
        assertThat(carrier.requests).hasSize(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void laterOrderPaidRecognizesManuallyCreatedShipmentWithoutRepeatingBookingOrCreation() {
        var event = event(UUID.randomUUID(), true);
        var snapshot = stubPaidOrder(event);
        var shipment = creation.createManually("manual-first", new CreateShipmentRequest(snapshot.orderId(), null, null, "HEAVY"));

        receive(event);

        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(carrier.requests).containsExactly(shipment.getId());
        assertThat(jdbc.queryForObject("SELECT service_type FROM shipments", String.class)).isEqualTo("HEAVY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactDecodedShippingFeeIsPersistedAndMatchesTheLaterOrderPaid() throws Exception {
        BigDecimal fee = new BigDecimal("100000000000000.01");
        var original = event(UUID.randomUUID(), true);
        ObjectNode payload = ((ObjectNode) original.payload()).deepCopy();
        payload.put("shippingFee", fee.toPlainString());
        var paidEvent = new EventEnvelope<>(original.eventId(), original.eventType(), original.aggregateId(),
                original.occurredAt(), original.traceId(), (JsonNode) payload);
        OrderPaidSnapshot expected = OrderPaidSnapshot.parse(paidEvent.payload(), mapper);
        var upstream = new PaidOrderShippingResponse(expected.orderId(), expected.buyerId(), expected.orderVersion(),
                "PAID", OffsetDateTime.parse("2026-11-11T00:00:00Z"), expected.warehouseId(), fee, expected.shippingInfo(), expected.sellerId());
        byte[] body = mapper.writeValueAsBytes(ApiResponse.of(upstream));
        var request = Request.create(Request.HttpMethod.GET, "http://commerce-fixture/shipping-snapshot",
                Map.of(), null, StandardCharsets.UTF_8, null);
        ApiResponse<PaidOrderShippingResponse> decoded;
        try (var response = Response.builder().request(request).status(200).reason("OK")
                .headers(Map.of()).body(body).build()) {
            var returnType = CommerceOrderClient.class
                    .getMethod("getPaidOrder", UUID.class, String.class, Request.Options.class).getGenericReturnType();
            decoded = (ApiResponse<PaidOrderShippingResponse>) new CommerceResponseDecoder(mapper).decode(response, returnType);
        }
        var decodedSnapshot = new OrderPaidSnapshot(decoded.data().orderId(), decoded.data().buyerId(),
                decoded.data().warehouseId(), decoded.data().shippingFee(), decoded.data().shippingInfo(), decoded.data().orderVersion(), decoded.data().sellerId());
        when(paidOrders.load(expected.orderId())).thenReturn(decodedSnapshot);

        var shipment = creation.createManually("exact-shipping-fee",
                new CreateShipmentRequest(expected.orderId(), null, null, null));

        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM shipments", BigDecimal.class))
                .isEqualByComparingTo(fee);
        receive(paidEvent);

        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(carrier.requests).containsExactly(shipment.getId());
        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM shipments", BigDecimal.class))
                .isEqualByComparingTo(fee);
    }

    @Test
    void manualAndEventCreationRaceCreatesOneShipmentAndOneExternalIdentity() throws Exception {
        var event = event(UUID.randomUUID(), true);
        var snapshot = stubPaidOrder(event);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var manual = pool.submit(() -> {
                start.await();
                return creation.createManually("manual-race", new CreateShipmentRequest(snapshot.orderId(), null, null, null));
            });
            var consumed = pool.submit(() -> { start.await(); receive(event); return null; });
            start.countDown();
            var shipment = manual.get(20, TimeUnit.SECONDS);
            consumed.get(20, TimeUnit.SECONDS);
            // A simultaneous key claim may hold the row during the immediate SKIP LOCKED wakeup.
            creation.retryDueTracking();
            assertThat(count("shipments")).isEqualTo(1);
            assertThat(count("outbox")).isEqualTo(1);
            assertThat(count("processed_events")).isEqualTo(1);
            assertThat(carrier.requests).containsExactly(shipment.getId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manualCreationUsesConfiguredPickupWhenSelectingAnotherWarehouse() {
        var snapshot = stubPaidOrder(event(UUID.randomUUID(), true));
        UUID selected = UUID.fromString("00000000-0000-4000-8000-000000000099");

        var shipment = creation.createManually("dispatch-options",
                new CreateShipmentRequest(snapshot.orderId(), selected, "FAKE", "HEAVY"));

        assertThat(shipment.getWarehouseId()).isEqualTo(selected);
        assertThat(shipment.getServiceType()).isEqualTo("HEAVY");
        assertThat(mapper.valueToTree(shipment.getShippingInfo()).path("pickupAddress").path("line1").asString())
                .isEqualTo("Configured pickup warehouse");
        assertThat(shipment.getTrackingCode()).isNotBlank();
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void manualCreationRejectsUnknownCarrierUnsupportedServiceAndUnconfiguredWarehouseBeforeWrites() {
        var snapshot = stubPaidOrder(event(UUID.randomUUID(), true));
        assertManualFailure("unknown-carrier", new CreateShipmentRequest(snapshot.orderId(), null, "UNKNOWN", null),
                "SHIPMENT_INVALID_PARAMETER");
        assertManualFailure("unsupported-service", new CreateShipmentRequest(snapshot.orderId(), null, null, "EXPRESS"),
                "SHIPMENT_NO_SERVICE");
        assertManualFailure("unknown-warehouse", new CreateShipmentRequest(snapshot.orderId(), UUID.randomUUID(), null, null),
                "SHIPMENT_WAREHOUSE_NOT_FOUND");
        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(carrier.requests).isEmpty();
    }

    @Test
    void manualUpstreamFailuresNeverCreateShipmentHistoryOrOutbox() {
        UUID order = UUID.randomUUID();
        for (ShipmentErrorCode failure : List.of(ShipmentErrorCode.ORDER_NOT_PAID,
                ShipmentErrorCode.COMMERCE_UNAVAILABLE, ShipmentErrorCode.INVALID_ORDER_SNAPSHOT)) {
            doThrow(new DomainException(failure)).when(paidOrders).load(order);
            assertManualFailure("failure-" + failure.name(), new CreateShipmentRequest(order, null, null, null), failure.code());
        }
        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(carrier.requests).isEmpty();
    }

    @Test
    void manualOutboxFailureRollsBackBeforeAnyBookingThenRetryCanCreate() {
        var snapshot = stubPaidOrder(event(UUID.randomUUID(), true));
        var request = new CreateShipmentRequest(snapshot.orderId(), null, null, null);
        doThrow(new IllegalStateException("manual outbox failure")).doCallRealMethod()
                .when(outbox).write(eq("Shipment"), eq(Topics.SHIPMENT), any(EventEnvelope.class));

        assertThatThrownBy(() -> creation.createManually("manual-outbox", request)).isInstanceOf(IllegalStateException.class);
        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(carrier.requests).isEmpty();

        var shipment = creation.createManually("manual-outbox", request);
        assertThat(carrier.requests).containsExactly(shipment.getId());
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void manuallyCreatedPendingShipmentReceivesTrackingWhenCarrierRecovers() {
        var snapshot = stubPaidOrder(event(UUID.randomUUID(), true));
        carrier.down.set(true);
        var shipment = creation.createManually("manual-outage", new CreateShipmentRequest(snapshot.orderId(), null, null, null));
        assertThat(shipment.getTrackingCode()).isNull();
        assertThat(shipment.getShipmentStatus().name()).isEqualTo("PENDING_TRACKING");
        assertThat(count("processed_events")).isZero();

        carrier.down.set(false);
        jdbc.update("UPDATE shipments SET next_tracking_retry_at=now()-interval '1 second'");
        creation.retryDueTracking();

        assertThat(carrier.requests).containsExactly(shipment.getId(), shipment.getId());
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isEqualTo("FAKE-" + shipment.getId());
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(3);
    }

    @Test
    void manualLegacyRecipientWithoutLocalityCodesStaysPendingPreparation() {
        var event = event(UUID.randomUUID(), true);
        OrderPaidSnapshot original = OrderPaidSnapshot.parse(event.payload(), mapper);
        Map<String, Object> info = new LinkedHashMap<>(original.shippingInfo());
        info.put("recipient", Map.of("name", "Buyer", "phone", "0900000000", "address", Map.of("line1", "Legacy street")));
        OrderPaidSnapshot snapshot = new OrderPaidSnapshot(original.orderId(), original.buyerId(), original.warehouseId(),
                original.shippingFee(), info, original.orderVersion(), original.sellerId());
        when(paidOrders.load(snapshot.orderId())).thenReturn(snapshot);

        var shipment = creation.createManually("legacy-preparation", new CreateShipmentRequest(snapshot.orderId(), null, null, null));

        assertThat(shipment.getTrackingCode()).isNull();
        assertThat(shipment.getShipmentStatus().name()).isEqualTo("PENDING_TRACKING");
        assertThat(carrier.requests).isEmpty();
        assertThat(count("outbox")).isEqualTo(1);
    }

    private OrderPaidSnapshot stubPaidOrder(EventEnvelope<JsonNode> event) {
        OrderPaidSnapshot snapshot = OrderPaidSnapshot.parse(event.payload(), mapper);
        when(paidOrders.load(snapshot.orderId())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("commerce read must not run while a local DB transaction holds locks").isFalse();
            return snapshot;
        });
        return snapshot;
    }

    private void assertManualFailure(String key, CreateShipmentRequest request, String errorCode) {
        assertThatThrownBy(() -> creation.createManually(key, request)).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo(errorCode);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void receive(EventEnvelope<JsonNode> event) {
        consumer.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, event.aggregateId(), event));
    }

    private EventEnvelope<JsonNode> withId(EventEnvelope<JsonNode> event, UUID eventId) {
        return new EventEnvelope<>(eventId, event.eventType(), event.aggregateId(), event.occurredAt(), event.traceId(), event.payload());
    }

    private EventEnvelope<JsonNode> event(UUID orderId, boolean prepared) {
        Map<String, Object> address = Map.of("line1", "1 Test Street", "wardCode", "1003646", "provinceCode", "1000001");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("recipient", Map.of("name", "Buyer", "phone", "0900000000", "address", address));
        info.put("items", List.of(Map.of("skuId", UUID.randomUUID(), "productName", "Test Item", "quantity", 1)));
        info.put("parcel", prepared ? Map.of("weightGrams", 1000, "lengthCm", 20, "widthCm", 15, "heightCm", 10) : null);
        info.put("pickupAddress", prepared ? address : null);
        info.put("carrier", "FAKE");
        info.put("serviceType", "STANDARD");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("buyerId", UUID.randomUUID());
        payload.put("warehouseId", UUID.randomUUID());
        for (String field : List.of("orderGroupId", "sellerId", "sessionId", "instructionId", "transactionId")) {
            payload.put(field, UUID.randomUUID());
        }
        payload.put("orderNumber", "NX-TEST-" + orderId.toString().substring(0, 8));
        payload.put("orderType", "STANDARD");
        payload.put("referenceId", null);
        payload.put("paidAt", "2026-11-11T00:00:00Z");
        payload.put("paidAmount", "200000.00");
        payload.put("currency", "VND");
        payload.put("shippingFee", "30000.00");
        payload.put("orderVersion", 1);
        payload.put("shippingInfo", info);
        return new EventEnvelope<>(UUID.randomUUID(), "OrderPaid", orderId.toString(), Instant.now(),
                "4bf92f3577b34da6a3ce929d0e0e4736", mapper.valueToTree(payload));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CarrierFixture {
        @Bean TestCarrier fakeCarrierAdapter(JdbcTemplate jdbc) { return new TestCarrier(jdbc); }
    }

    static class TestCarrier extends FakeCarrierAdapter {
        private final JdbcTemplate jdbc;
        final List<UUID> requests = new CopyOnWriteArrayList<>();
        final AtomicBoolean down = new AtomicBoolean();

        TestCarrier(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        public String createShipment(CarrierShipmentRequest request, ShippingDeadline deadline) {
            // A separate connection cannot see writes from an uncommitted consumer transaction.
            // This guards the boundary that replaces the former creation-request staging table.
            try (var connection = jdbc.getDataSource().getConnection();
                 var query = connection.prepareStatement("""
                         SELECT (SELECT count(*) FROM shipments WHERE id=?),
                                (SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='ShipmentCreated'),
                                (SELECT count(*) FROM processed_events WHERE consumer=?)
                         """)) {
                query.setObject(1, request.requestId());
                query.setString(2, request.requestId().toString());
                query.setString(3, OrderPaidShipmentConsumer.CONSUMER);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).as("shipment committed before carrier booking").isEqualTo(1);
                    assertThat(result.getLong(2)).as("creation outbox committed before carrier booking").isEqualTo(1);
                    // Manual creation has no inbound event marker; consumer-specific tests assert theirs.
                }
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException("Cannot inspect committed creation state", error);
            }
            requests.add(request.requestId());
            if (down.get()) throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
            return super.createShipment(request, deadline);
        }
    }
}
