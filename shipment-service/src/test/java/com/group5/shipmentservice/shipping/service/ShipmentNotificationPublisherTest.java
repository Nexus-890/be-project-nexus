package com.group5.shipmentservice.shipping.service;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.event.ShipmentEventPublisher;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;
import com.group5.shipmentservice.shipping.repository.ShipmentHistoryRepository;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import com.group5.shipmentservice.shipping.repository.ShipmentStatusReceiptRepository;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contract chung với Notification: payload phải xuất phát từ publisher thật, không tự thêm tracking. */
class ShipmentNotificationPublisherTest {
    private static final UUID SHIPMENT = id(1);
    private static final UUID ORDER = id(2);
    private static final UUID BUYER = id(3);
    private static final UUID WAREHOUSE = id(4);
    private static final UUID SELLER = id(5);
    private static final UUID ACTOR = id(6);
    private static final UUID HISTORY = id(7);
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant CREATED_AT = Instant.parse("2026-11-16T00:00:00Z");
    private static final Instant PICKED_UP_AT = Instant.parse("2026-11-16T00:05:00Z");

    @Test
    void sharedNotificationSamplesMatchActualCreationTrackingAndPickupOutboxPayloads() throws Exception {
        JsonMapper mapper = new JsonMapper();
        JsonNode samples = mapper.readTree(Files.readString(Path.of(
                "../notification-service/src/test/resources/shipment-notification-publisher.json")));
        ShipmentRepository shipments = mock(ShipmentRepository.class);
        ShipmentHistoryRepository histories = mock(ShipmentHistoryRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());

        // Chỉ giả lập persistence/auditing; service, carrier giả lập và outbox serializer đều là code production.
        AtomicReference<Shipment> stored = new AtomicReference<>();
        when(shipments.findByOrderId(ORDER)).thenReturn(Optional.empty());
        when(shipments.saveAndFlush(any(Shipment.class))).thenAnswer(invocation -> {
            Shipment shipment = invocation.getArgument(0);
            ReflectionTestUtils.setField(shipment, "id", SHIPMENT);
            ReflectionTestUtils.setField(shipment, "createdAt", CREATED_AT.atOffset(ZoneOffset.UTC));
            stored.set(shipment);
            return shipment;
        });
        when(shipments.findDueTrackingForUpdate(eq(SHIPMENT), any())).thenAnswer(ignored ->
                Optional.ofNullable(stored.get()));
        when(shipments.findByIdForUpdate(SHIPMENT)).thenAnswer(ignored -> Optional.of(stored.get()));
        doAnswer(ignored -> {
            Shipment shipment = stored.get();
            ReflectionTestUtils.setField(shipment, "version", shipment.getVersion() + 1);
            return null;
        }).when(shipments).flush();
        when(histories.saveAndFlush(any(ShipmentHistory.class))).thenAnswer(invocation -> {
            ShipmentHistory history = invocation.getArgument(0);
            ReflectionTestUtils.setField(history, "id", HISTORY);
            ReflectionTestUtils.setField(history, "createdAt", PICKED_UP_AT.atOffset(ZoneOffset.UTC));
            return history;
        });

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE);
        EventEnvelopeFactory envelopes = new EventEnvelopeFactory(tracer, Clock.fixed(CREATED_AT, ZoneOffset.UTC));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<JsonNode> emitted = new ArrayList<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] arguments = (Object[]) invocation.getRawArguments()[1];
            assertThat(arguments[5]).isEqualTo(Topics.SHIPMENT);
            emitted.add(mapper.readTree((String) arguments[6]));
            return 1;
        });
        ShipmentEventPublisher events = new ShipmentEventPublisher(new OutboxWriter(jdbc, mapper, transactions), envelopes);
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 0, 100, new BigDecimal("1000000"), 5, 30000, "", WAREHOUSE, null, Map.of());
        CarrierRegistry carriers = new CarrierRegistry(List.of(new FakeCarrierAdapter(Clock.fixed(CREATED_AT, ZoneOffset.UTC))));
        CarrierExecutor executor = new CarrierExecutor(properties);
        ShipmentCreationService creation = new ShipmentCreationService(mapper, shipments, histories, carriers,
                executor, properties, events, mock(PaidOrderSnapshotService.class), transactions, 30000);
        ShipmentStatusService statuses = new ShipmentStatusService(shipments, histories, carriers, executor,
                events, mock(ShipmentReadService.class), mock(ShipmentStatusReceiptRepository.class), transactions, 60000);
        Map<String, Object> address = Map.of("line1", "Test address", "provinceCode", "1000001", "wardCode", "1003646");
        Map<String, Object> shippingInfo = Map.of("carrier", "FAKE", "serviceType", "STANDARD",
                "recipient", Map.of("name", "Buyer", "phone", "0900000000", "address", address),
                "pickupAddress", address,
                "parcel", Map.of("weightGrams", 500, "lengthCm", 10, "widthCm", 10, "heightCm", 10));
        OrderPaidSnapshot paid = new OrderPaidSnapshot(ORDER, BUYER, WAREHOUSE,
                new BigDecimal("30000"), shippingInfo, 1, SELLER);
        EventEnvelope<JsonNode> paidEvent = new EventEnvelope<>(id(8), "OrderPaid", ORDER.toString(),
                CREATED_AT, TRACE, mapper.valueToTree(paid));

        assertThat(creation.createPaid(paidEvent, paid).created()).isTrue();
        assertThat(emitted).hasSize(1);
        assertSample(emitted.getFirst(), samples.path("created"));
        assertThat(emitted.getFirst().path("payload").path("trackingCode").isNull()).isTrue();
        assertThat(emitted.getFirst().path("payload").path("status").asString()).isEqualTo("PENDING_TRACKING");

        creation.assignInitialTracking(SHIPMENT);
        assertThat(stored.get().getTrackingCode()).isEqualTo("FAKE-" + SHIPMENT);
        assertThat(emitted).hasSize(1);

        CurrentUser staff = new CurrentUser(ACTOR, Set.of("SUPPORT_STAFF"), Set.of("SHIPPING.UPDATE_STATUS"));
        statuses.update(staff, SHIPMENT, ShipmentStatus.SHIPPED, "Carrier picked up", false);
        assertThat(emitted).hasSize(2);
        assertSample(emitted.get(1), samples.path("pickedUp"));
        assertThat(emitted.get(1).path("payload").path("trackingCode").asString())
                .isEqualTo(stored.get().getTrackingCode());

        statuses.update(staff, SHIPMENT, ShipmentStatus.SHIPPED, "Carrier picked up", false);
        assertThat(emitted).hasSize(2);
    }

    private static void assertSample(JsonNode actual, JsonNode sample) {
        assertThat(actual.path("eventType")).isEqualTo(sample.path("eventType"));
        assertThat(actual.path("payload")).isEqualTo(sample.path("payload"));
        assertThat(actual.path("aggregateId").asString()).isEqualTo(SHIPMENT.toString());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("25900000-0000-4000-8000-%012d".formatted(suffix));
    }
}