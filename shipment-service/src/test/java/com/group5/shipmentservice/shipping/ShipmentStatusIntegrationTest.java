package com.group5.shipmentservice.shipping;

import com.group5.shipmentservice.shipping.event.OrderCancelledConsumer;
import com.group5.shipmentservice.shipping.service.ShipmentStatusService;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.ShipmentServiceApplication;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierTrackingUpdate;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = {ShipmentServiceApplication.class, ShipmentStatusIntegrationTest.CarrierFixture.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=shipment-status-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "nexus.shipping.carrier=NONE", "nexus.shipping.breaker-failure-threshold=100",
        "nexus.shipping.order-paid-consumer-enabled=false", "nexus.shipping.order-cancelled-consumer-enabled=false",
        "nexus.outbox.relay.enabled=false", "nexus.shipping.status-poll-enabled=false",
        "nexus.shipping.status-poll-interval-ms=3600000",
        "nexus.shipping.tracking-retry-poll-ms=3600000", "management.tracing.export.zipkin.enabled=false"
})
class ShipmentStatusIntegrationTest {
    private static final UUID BUYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID STAFF = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

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
    @Autowired ShipmentStatusService statuses;
    @Autowired OrderCancelledConsumer cancellations;
    @Autowired TestCarrier carrier;
    @MockitoSpyBean OutboxWriter outbox;
    @MockitoBean EventEnvelopeFactory envelopes;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE carrier_webhook_receipts, cancelled_shipment_orders, shipment_history, shipments, outbox, processed_events");
        carrier.cancelled.clear();
        carrier.unavailable.set(false);
        carrier.polledStatus = ShipmentStatus.IN_TRANSIT;
        reset(outbox, envelopes);
        when(envelopes.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                new EventEnvelope<>(UUID.randomUUID(), invocation.getArgument(0), invocation.getArgument(1),
                        Instant.now(), TRACE, invocation.getArgument(2)));
    }

    @Test
    void repeatedWebhookCommitsOneHistoryAndOneCorrelatedOutboxEvent() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        byte[] body = webhook(shipment, ShipmentStatus.SHIPPED, "ghn-delivery-1");

        statuses.webhook("FAKE", body, "valid-signature");
        statuses.webhook("FAKE", body, "valid-signature");

        assertThat(status(shipment)).isEqualTo("SHIPPED");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("carrier_webhook_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT handed_over_at FROM shipments WHERE id=?", Object.class, shipment))
                .isNotNull();
        JsonNode event = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox", String.class));
        assertThat(event.path("eventType").asString()).isEqualTo("ShipmentPickedUp");
        assertThat(event.path("aggregateId").asString()).isEqualTo(shipment.toString());
        assertThat(event.path("payload").path("status").asString()).isEqualTo("SHIPPED");
        assertThat(event.path("payload").path("source").asString()).isEqualTo("CARRIER_WEBHOOK");
        assertThat(event.path("payload").path("historyId").asString())
                .isEqualTo(jdbc.queryForObject("SELECT id::text FROM shipment_history", String.class));
        assertThat(event.path("payload").path("shipmentVersion").asLong())
                .isEqualTo(jdbc.queryForObject("SELECT version FROM shipments WHERE id=?", Long.class, shipment));
        assertThat(jdbc.queryForObject("SELECT topic FROM outbox", String.class)).isEqualTo(Topics.SHIPMENT);
    }

    @Test
    void concurrentDuplicateWebhookStillCommitsOneTransition() throws Exception {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        byte[] body = webhook(shipment, ShipmentStatus.SHIPPED, "ghn-concurrent-1");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); statuses.webhook("FAKE", body, "valid-signature"); return true; });
            var second = executor.submit(() -> { start.await(); statuses.webhook("FAKE", body, "valid-signature"); return true; });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        assertThat(status(shipment)).isEqualTo("SHIPPED");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void invalidSignatureChangesNothingEvenForMalformedBody() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);

        assertThatThrownBy(() -> statuses.webhook("FAKE", "invalid-json".getBytes(StandardCharsets.UTF_8), "invalid"))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_INVALID_SIGNATURE"));

        assertThat(status(shipment)).isEqualTo("READY_TO_SHIP");
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("carrier_webhook_receipts")).isZero();
    }

    @Test
    void completedShipmentRejectsLateBackwardWebhook() {
        UUID shipment = shipment(ShipmentStatus.DELIVERED);

        assertThatThrownBy(() -> statuses.webhook("FAKE", webhook(shipment, ShipmentStatus.IN_TRANSIT, "late-event"),
                "valid-signature"))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_INVALID_TRANSITION"));

        assertThat(status(shipment)).isEqualTo("DELIVERED");
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("carrier_webhook_receipts")).isZero();
    }

    @Test
    void duplicateNoOpFromAnotherCarrierEventDoesNotCreateAnotherFact() {
        UUID shipment = shipment(ShipmentStatus.SHIPPED);

        statuses.webhook("FAKE", webhook(shipment, ShipmentStatus.IN_TRANSIT, "first"), "valid-signature");
        statuses.webhook("FAKE", webhook(shipment, ShipmentStatus.IN_TRANSIT, "second"), "valid-signature");

        assertThat(status(shipment)).isEqualTo("IN_TRANSIT");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void failedOutboxRollsBackStatusAndTrackingBeforeWebhookRetry() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        byte[] body = webhook(shipment, ShipmentStatus.SHIPPED, "retry-event");
        doThrow(new IllegalStateException("outbox failure")).doCallRealMethod()
                .when(outbox).write(eq("Shipment"), eq(Topics.SHIPMENT), any(EventEnvelope.class));

        assertThatThrownBy(() -> statuses.webhook("FAKE", body, "valid-signature"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status(shipment)).isEqualTo("READY_TO_SHIP");
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("carrier_webhook_receipts")).isZero();

        statuses.webhook("FAKE", body, "valid-signature");

        assertThat(status(shipment)).isEqualTo("SHIPPED");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void manualChangeRecordsAuthenticatedActorAndReasonOnce() {
        UUID shipment = shipment(ShipmentStatus.IN_TRANSIT);

        statuses.update(staff(), shipment, ShipmentStatus.DELIVERED, "Carrier proof confirmed", true);
        statuses.update(staff(), shipment, ShipmentStatus.DELIVERED, "Carrier proof confirmed", true);

        assertThat(status(shipment)).isEqualTo("DELIVERED");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM shipment_history", String.class)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT changed_by FROM shipment_history", UUID.class)).isEqualTo(STAFF);
        assertThat(jdbc.queryForObject("SELECT description FROM shipment_history", String.class))
                .isEqualTo("Carrier proof confirmed");
    }

    @Test
    void overrideReasonDoesNotAllowDeliveredShipmentToMoveBackwards() {
        UUID shipment = shipment(ShipmentStatus.DELIVERED);

        assertThatThrownBy(() -> statuses.update(staff(), shipment, ShipmentStatus.IN_TRANSIT,
                "Support correction", true))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_INVALID_TRANSITION"));

        assertThat(status(shipment)).isEqualTo("DELIVERED");
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"PACKED,false", "PACKED,true", "SHIPPED,false", "SHIPPED,true"})
    void manualAdvanceWithoutTrackingCannotBypassBookingOrLoseRetryEligibility(ShipmentStatus target, boolean override) {
        UUID shipment = shipment(ShipmentStatus.PENDING_TRACKING);
        jdbc.update("UPDATE shipments SET tracking_code=NULL,next_tracking_retry_at=now() WHERE id=?", shipment);
        Object retryAt = jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments WHERE id=?", Object.class, shipment);

        assertThatThrownBy(() -> statuses.update(staff(), shipment, target, "Support requested advancement", override))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_INVALID_TRANSITION"));

        assertThat(status(shipment)).isEqualTo("PENDING_TRACKING");
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments WHERE id=?", String.class, shipment)).isNull();
        assertThat(jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments WHERE id=?", Object.class, shipment))
                .isEqualTo(retryAt);
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(carrier.cancelled).isEmpty();
    }

    @Test
    void manualNoOpWithoutTrackingPreservesPendingBookingRetry() {
        UUID shipment = shipment(ShipmentStatus.PENDING_TRACKING);
        jdbc.update("UPDATE shipments SET tracking_code=NULL,next_tracking_retry_at=now() WHERE id=?", shipment);

        statuses.update(staff(), shipment, ShipmentStatus.PENDING_TRACKING, null, false);

        assertThat(status(shipment)).isEqualTo("PENDING_TRACKING");
        assertThat(jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments WHERE id=?", Object.class, shipment))
                .isNotNull();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void manualCancellationWithoutTrackingRemainsAllowed() {
        UUID shipment = shipment(ShipmentStatus.PENDING_TRACKING);
        jdbc.update("UPDATE shipments SET tracking_code=NULL,next_tracking_retry_at=now() WHERE id=?", shipment);

        statuses.update(staff(), shipment, ShipmentStatus.CANCELLED, "Cancelled before booking", false);

        assertThat(status(shipment)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments WHERE id=?", Object.class, shipment))
                .isNull();
        assertThat(carrier.cancelled).isEmpty();
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void trackingHistoryIsPagedAndHiddenFromAnotherBuyer() {
        UUID shipment = shipment(ShipmentStatus.SHIPPED);
        statuses.webhook("FAKE", webhook(shipment, ShipmentStatus.IN_TRANSIT, "in-transit"), "valid-signature");
        statuses.webhook("FAKE", webhook(shipment, ShipmentStatus.DELIVERED, "delivered"), "valid-signature");

        var tracking = statuses.tracking(new CurrentUser(BUYER, Set.of("BUYER"), Set.of("SHIPPING.VIEW_SHIPMENT")),
                shipment, 0, 1);

        assertThat(tracking.getContent()).hasSize(1);
        assertThat(tracking.getTotalElements()).isEqualTo(2);
        assertThat(tracking.getTotalPages()).isEqualTo(2);
        assertThatThrownBy(() -> statuses.tracking(new CurrentUser(UUID.randomUUID(), Set.of("BUYER"),
                        Set.of("SHIPPING.VIEW_SHIPMENT")), shipment, 0, 20))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_NOT_FOUND"));
    }

    @Test
    void pollingAndWebhookShareOneTransition() {
        UUID shipment = shipment(ShipmentStatus.SHIPPED);

        statuses.poll();
        statuses.webhook("FAKE", webhook(shipment, ShipmentStatus.IN_TRANSIT, "carrier-after-poll"), "valid-signature");

        assertThat(status(shipment)).isEqualTo("IN_TRANSIT");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM shipment_history", String.class)).isEqualTo("POLLER");
    }

    @Test
    void pollingOutageSchedulesRetryWithoutStatusOrHistoryChanges() {
        UUID shipment = shipment(ShipmentStatus.SHIPPED);
        carrier.unavailable.set(true);

        statuses.poll();

        assertThat(status(shipment)).isEqualTo("SHIPPED");
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT status_poll_failures FROM shipments WHERE id=?", Integer.class, shipment))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_status_poll_at > now() FROM shipments WHERE id=?", Boolean.class, shipment))
                .isTrue();

        carrier.unavailable.set(false);
        jdbc.update("UPDATE shipments SET next_status_poll_at=now()-interval '1 second' WHERE id=?", shipment);
        statuses.poll();

        assertThat(status(shipment)).isEqualTo("IN_TRANSIT");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status_poll_failures FROM shipments WHERE id=?", Integer.class, shipment))
                .isZero();
    }

    @Test
    void cancellationBeforePickupCallsCarrierOnceAndCommitsOneHistory() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        UUID order = order(shipment);
        var event = cancelled(order, UUID.randomUUID());

        receive(event);
        receive(event);
        receive(cancelled(order, UUID.randomUUID()));

        assertThat(status(shipment)).isEqualTo("CANCELLED");
        assertThat(carrier.cancelled).containsExactly(shipment);
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox", String.class)).isEqualTo("ShipmentCancelled");
        assertThat(count("processed_events")).isEqualTo(2);
    }

    @Test
    void cancellationAcceptsFullContractReasonAndTruncatesHistoryBeforeCarrierCall() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        String reason = "x".repeat(500);
        var event = cancelled(order(shipment), UUID.randomUUID(), reason);

        receive(event);
        receive(event);

        assertThat(status(shipment)).isEqualTo("CANCELLED");
        assertThat(carrier.cancelled).containsExactly(shipment);
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM shipment_history", String.class)).isEqualTo("x".repeat(255));
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'reason' FROM outbox", String.class))
                .isEqualTo("x".repeat(255));
    }

    @Test
    void cancellationRejectsReasonBeyondContractLimitBeforeCallingCarrier() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        var event = cancelled(order(shipment), UUID.randomUUID(), "x".repeat(501));

        assertThatThrownBy(() -> receive(event)).isInstanceOf(IllegalArgumentException.class);

        assertThat(status(shipment)).isEqualTo("READY_TO_SHIP");
        assertThat(carrier.cancelled).isEmpty();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("processed_events")).isZero();
        assertThat(count("cancelled_shipment_orders")).isZero();
    }

    @Test
    void cancellationBeforeTrackingAssignmentStopsBookingRetriesWithoutCarrierCall() {
        UUID shipment = shipment(ShipmentStatus.PENDING_TRACKING);
        jdbc.update("UPDATE shipments SET tracking_code=NULL,next_tracking_retry_at=now() WHERE id=?", shipment);

        receive(cancelled(order(shipment), UUID.randomUUID()));

        assertThat(status(shipment)).isEqualTo("CANCELLED");
        assertThat(carrier.cancelled).isEmpty();
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments WHERE id=?", Object.class, shipment))
                .isNull();
    }

    @Test
    void cancellationAfterPickupLogsOneSupportWarningWithoutChanges(CapturedOutput output) {
        UUID shipment = shipment(ShipmentStatus.SHIPPED);
        var event = cancelled(order(shipment), UUID.randomUUID());

        receive(event);
        receive(event);

        assertThat(status(shipment)).isEqualTo("SHIPPED");
        assertThat(carrier.cancelled).isEmpty();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(output.getAll().lines().filter(line -> line.contains("WARN")
                && line.contains(shipment.toString())).count()).isEqualTo(1);
    }

    @Test
    void cancellationWithoutShipmentCompletesAndDeduplicates() {
        var event = cancelled(UUID.randomUUID(), UUID.randomUUID());

        receive(event);
        receive(event);

        assertThat(count("shipments")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(carrier.cancelled).isEmpty();
    }

    @Test
    void handoverEvidencePreventsCancellationEvenWhenCurrentStatusLooksUnpicked(CapturedOutput output) {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        jdbc.update("UPDATE shipments SET handed_over_at=now() WHERE id=?", shipment);

        receive(cancelled(order(shipment), UUID.randomUUID()));

        assertThat(status(shipment)).isEqualTo("READY_TO_SHIP");
        assertThat(carrier.cancelled).isEmpty();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(output.getAll()).contains("Support action required");
    }

    @Test
    void mismatchedCancellationKafkaKeyCannotCancelShipmentOrClaimMarker() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        var event = cancelled(order(shipment), UUID.randomUUID());

        assertThatThrownBy(() -> cancellations.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0,
                UUID.randomUUID().toString(), event))).isInstanceOf(IllegalArgumentException.class);

        assertThat(status(shipment)).isEqualTo("READY_TO_SHIP");
        assertThat(carrier.cancelled).isEmpty();
        assertThat(count("processed_events")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void carrierCancellationFailureRollsBackMarkerAndAllowsRetryWithStableRequestId() {
        UUID shipment = shipment(ShipmentStatus.READY_TO_SHIP);
        var event = cancelled(order(shipment), UUID.randomUUID());
        carrier.unavailable.set(true);

        assertThatThrownBy(() -> receive(event)).isInstanceOf(DomainException.class);
        assertThat(status(shipment)).isEqualTo("READY_TO_SHIP");
        assertThat(count("processed_events")).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();

        carrier.unavailable.set(false);
        receive(event);

        assertThat(status(shipment)).isEqualTo("CANCELLED");
        assertThat(carrier.cancelled).containsExactly(shipment, shipment);
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void newerNoOpFencesOlderDeliveryRetryWithoutCreatingABusinessFact() {
        UUID shipment = shipment(ShipmentStatus.DELIVERY_FAILED);
        jdbc.update("UPDATE shipments SET last_carrier_update_at='2026-11-11T11:00:00Z' WHERE id=?", shipment);
        byte[] observation = timedWebhook(shipment, ShipmentStatus.DELIVERY_FAILED, "failed-13", "13:00");
        statuses.webhook("FAKE", observation, "valid-signature");
        statuses.webhook("FAKE", observation, "valid-signature");
        assertThat(jdbc.queryForObject("SELECT version FROM shipments WHERE id=?", Long.class, shipment)).isZero();
        assertThat(count("shipment_history")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_carrier_update_at FROM shipments WHERE id=?",
                OffsetDateTime.class, shipment)).isEqualTo(OffsetDateTime.parse("2026-11-11T13:00:00Z"));
        assertThatThrownBy(() -> statuses.webhook("FAKE",
                timedWebhook(shipment, ShipmentStatus.OUT_FOR_DELIVERY, "retry-12", "12:00"), "valid-signature"))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_INVALID_TRANSITION"));
        assertThat(status(shipment)).isEqualTo("DELIVERY_FAILED");
        assertThat(count("carrier_webhook_receipts")).isEqualTo(1);
        statuses.webhook("FAKE", timedWebhook(shipment, ShipmentStatus.OUT_FOR_DELIVERY, "retry-14", "14:00"),
                "valid-signature");
        assertThat(status(shipment)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(count("shipment_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"SHIPPED,", "DELIVERED,", "SHIPPED,'   '"})
    void optionalManualReasonStillPublishesConsumableAudit(ShipmentStatus target, String reason) {
        UUID shipment = shipment(ShipmentStatus.CREATED);
        statuses.update(staff(), shipment, target, reason, false);
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'reason' FROM outbox", String.class))
                .isEqualTo("Manual shipment status update");
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'source' FROM outbox", String.class))
                .isEqualTo("MANUAL");
    }

    @Test
    void sellerReadsOwnTrackingButOtherSellerAndForgedJsonCannotGrantOwnership() {
        UUID seller = UUID.randomUUID();
        UUID shipment = shipment(ShipmentStatus.CREATED);
        jdbc.update("UPDATE shipments SET seller_id=? WHERE id=?", seller, shipment);
        CurrentUser owner = new CurrentUser(seller, Set.of("SELLER"), Set.of("SHIPPING.VIEW_SHIPMENT"));
        assertThat(statuses.tracking(owner, shipment, 0, 20).getContent()).isEmpty();
        CurrentUser other = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of("SHIPPING.VIEW_SHIPMENT"));
        jdbc.update("UPDATE shipments SET shipping_info=jsonb_build_object('sellerId', ?::text) WHERE id=?",
                other.id().toString(), shipment);
        assertThatThrownBy(() -> statuses.tracking(other, shipment, 0, 20))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_NOT_FOUND"));
        assertThatThrownBy(() -> statuses.tracking(new CurrentUser(seller, Set.of("BUYER"), Set.of()), shipment, 0, 20))
                .isInstanceOf(DomainException.class);
    }

    private byte[] timedWebhook(UUID shipment, ShipmentStatus status, String eventId, String time) {
        return mapper.writeValueAsBytes(Map.of("trackingCode", "TRACK-" + shipment, "status", status.name(),
                "eventId", eventId, "observedAt", "2026-11-11T" + time + ":00Z"));
    }

    private UUID shipment(ShipmentStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shipments(id,order_id,buyer_id,warehouse_id,carrier,tracking_code,shipment_status,shipping_info)
                VALUES (?,?,?,?,?,?,?::shipment_status,'{}'::jsonb)
                """, id, UUID.randomUUID(), BUYER, UUID.randomUUID(), "FAKE", "TRACK-" + id, status.name());
        return id;
    }

    private UUID order(UUID shipment) {
        return jdbc.queryForObject("SELECT order_id FROM shipments WHERE id=?", UUID.class, shipment);
    }

    private String status(UUID shipment) {
        return jdbc.queryForObject("SELECT shipment_status::text FROM shipments WHERE id=?", String.class, shipment);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private byte[] webhook(UUID shipment, ShipmentStatus status, String eventId) {
        return mapper.writeValueAsBytes(Map.of("trackingCode", "TRACK-" + shipment,
                "status", status.name(), "eventId", eventId));
    }

    private EventEnvelope<JsonNode> cancelled(UUID order, UUID eventId) {
        return cancelled(order, eventId, "Buyer requested cancellation");
    }

    private EventEnvelope<JsonNode> cancelled(UUID order, UUID eventId, String reason) {
        return new EventEnvelope<>(eventId, "OrderCancelled", order.toString(), Instant.now(), TRACE,
                mapper.valueToTree(Map.of("orderId", order.toString(), "reason", reason,
                        "actorId", STAFF.toString(), "cancelledAt", "2026-11-11T00:00:00Z", "orderVersion", 2)));
    }

    private void receive(EventEnvelope<JsonNode> event) {
        cancellations.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, event.aggregateId(), event));
    }

    private static CurrentUser staff() {
        return new CurrentUser(STAFF, Set.of("SUPPORT_STAFF"),
                Set.of("SHIPPING.UPDATE_STATUS", "SHIPPING.MANUAL_OVERRIDE", "SHIPPING.VIEW_SHIPMENT"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CarrierFixture {
        @Bean
        TestCarrier carrier() {
            return new TestCarrier();
        }
    }

    static class TestCarrier extends FakeCarrierAdapter {
        final List<UUID> cancelled = new CopyOnWriteArrayList<>();
        final AtomicBoolean unavailable = new AtomicBoolean();
        ShipmentStatus polledStatus = ShipmentStatus.IN_TRANSIT;

        @Override
        public void verifyWebhook(byte[] body, String signature) {
            if (!"valid-signature".equals(signature)) {
                throw new DomainException(ShipmentErrorCode.INVALID_SIGNATURE);
            }
        }

        @Override
        public CarrierTrackingUpdate parseWebhook(byte[] body) {
            JsonNode node = JsonMapper.shared().readTree(body);
            return new CarrierTrackingUpdate(node.path("trackingCode").asString(),
                    ShipmentStatus.valueOf(node.path("status").asString()), node.path("eventId").asString(),
                    OffsetDateTime.parse(node.path("observedAt").asString("2026-11-11T00:00:00Z")), "Carrier update");
        }

        @Override
        public CarrierTrackingUpdate track(String trackingCode, ShippingDeadline deadline) {
            if (unavailable.get()) {
                throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
            }
            return new CarrierTrackingUpdate(trackingCode, polledStatus, "poll-" + trackingCode + "-" + polledStatus,
                    OffsetDateTime.parse("2026-11-11T00:00:00Z"), "Carrier poll");
        }

        @Override
        public void cancelShipment(String trackingCode, UUID requestId, ShippingDeadline deadline) {
            cancelled.add(requestId);
            if (unavailable.get()) {
                throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
            }
        }
    }
}
