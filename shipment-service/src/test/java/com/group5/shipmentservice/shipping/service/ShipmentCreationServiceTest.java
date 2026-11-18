package com.group5.shipmentservice.shipping.service;

import com.group5.common_events.EventEnvelope;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties.CarrierMode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentResult;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import com.group5.shipmentservice.shipping.event.ShipmentEventPublisher;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;
import com.group5.shipmentservice.shipping.repository.ShipmentHistoryRepository;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShipmentCreationServiceTest {
    private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-4000-8000-000000000099");
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShipmentHistoryRepository histories = mock(ShipmentHistoryRepository.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({"GHN,19999,STANDARD", "GHN,20000,HEAVY", "FAKE,19999,STANDARD", "FAKE,20000,HEAVY"})
    void commercePaidPayloadWithoutDispatchFieldsBooksUsingTheMatchingWarehouse(CarrierMode booking,
                                                                                int weightGrams, String serviceType) {
        CarrierAdapter adapter = booking == CarrierMode.FAKE ? spy(new FakeCarrierAdapter()) : mock(CarrierAdapter.class);
        if (booking == CarrierMode.GHN) {
            when(adapter.code()).thenReturn("GHN");
            when(adapter.name()).thenReturn("GHN");
            when(adapter.bookShipment(any(), any())).thenReturn(new CarrierShipmentResult("GHN258", null, false));
        }
        var pickup = new PickupAddress("1000001", "1003646", "Configured warehouse street");
        var properties = properties(booking, WAREHOUSE, pickup, Map.of());
        var service = service(properties, List.of(adapter));
        Map<String, Object> info = commerceInfo();
        info.put("parcel", Map.of("weightGrams", weightGrams, "lengthCm", 20, "widthCm", 15, "heightCm", 10));
        Shipment shipment = create(service, WAREHOUSE, info);

        service.assignInitialTracking(shipment.getId());

        assertThat(shipment.getTrackingCode()).isEqualTo(booking == CarrierMode.FAKE ? "FAKE-" + shipment.getId() : "GHN258");
        assertThat(shipment.getServiceType()).isEqualTo(serviceType);
        assertThat(shipment.getShippingFee()).isEqualByComparingTo("45678");
        assertThat(shipment.getShippingInfo().get("pickupAddress")).isEqualTo(Map.of("line1", pickup.addressLine(),
                "provinceCode", pickup.provinceCode(), "wardCode", pickup.wardCode()));
        assertThat(info.get("pickupAddress")).isNull();
        assertThat(info).doesNotContainKeys("serviceType", "carrier");
    }

    @Test
    void automaticBookingUsesWarehouseSpecificPickupAndNeverAnotherWarehousesDefault() {
        FakeCarrierAdapter fake = spy(new FakeCarrierAdapter());
        var pickup = new PickupAddress("1000002", "1000003", "Specific warehouse street");
        var properties = properties(CarrierMode.FAKE, UUID.randomUUID(),
                new PickupAddress("1000001", "1003646", "Different warehouse street"), Map.of(WAREHOUSE, pickup));
        var service = service(properties, List.of(fake));
        Shipment shipment = create(service, WAREHOUSE, commerceInfo());

        service.assignInitialTracking(shipment.getId());

        assertThat(shipment.getTrackingCode()).isEqualTo("FAKE-" + shipment.getId());
        assertThat(shipment.getShippingInfo().get("pickupAddress")).isEqualTo(Map.of("line1", pickup.addressLine(),
                "provinceCode", pickup.provinceCode(), "wardCode", pickup.wardCode()));
    }

    @Test
    void unconfiguredWarehouseStaysPendingWithoutBorrowingDefaultPickup() {
        FakeCarrierAdapter fake = spy(new FakeCarrierAdapter());
        var properties = properties(CarrierMode.FAKE, UUID.randomUUID(),
                new PickupAddress("1000001", "1003646", "Different warehouse street"), Map.of());
        var service = service(properties, List.of(fake));
        Shipment shipment = create(service, WAREHOUSE, commerceInfo());

        service.assignInitialTracking(shipment.getId());

        assertThat(shipment.getTrackingCode()).isNull();
        assertThat(shipment.getShippingInfo().get("pickupAddress")).isNull();
        verify(fake, never()).createShipment(any(), any());
    }

    @Test
    void ghnQuoteUsesFakeBookingAndKeepsPaidFeeAndSnapshot() {
        CarrierAdapter quote = mock(CarrierAdapter.class);
        when(quote.code()).thenReturn("GHN");
        when(quote.name()).thenReturn("GHN");
        FakeCarrierAdapter fake = spy(new FakeCarrierAdapter());
        var service = service(CarrierMode.FAKE, List.of(quote, fake));
        Shipment shipment = create(service);

        service.assignInitialTracking(shipment.getId());

        assertThat(shipment.getCarrier()).isEqualTo("FAKE");
        assertThat(shipment.getTrackingCode()).isEqualTo("FAKE-" + shipment.getId());
        assertThat(shipment.getShippingFee()).isEqualByComparingTo("45678");
        assertThat(shipment.getShippingInfo().get("carrier")).isEqualTo("GHN");
        verify(quote, never()).bookShipment(any(), any());
        verify(fake).createShipment(argThat(request -> request.requestId().equals(shipment.getId())), any());
    }

    @Test
    void bookingNoneDoesNotUseQuotedGhnOrScheduleEndlessTrackingRetries() {
        CarrierAdapter quote = mock(CarrierAdapter.class);
        when(quote.code()).thenReturn("GHN");
        when(quote.name()).thenReturn("GHN");
        var service = service(CarrierMode.NONE, List.of(quote));
        Shipment shipment = create(service);

        service.assignInitialTracking(shipment.getId());

        assertThat(shipment.getCarrier()).isEqualTo("NONE");
        assertThat(shipment.getTrackingCode()).isNull();
        assertThat(shipment.getNextTrackingRetryAt()).isNull();
        assertThat(shipment.getTrackingRetryCount()).isZero();
        verify(quote, never()).bookShipment(any(), any());
        verify(histories, times(1)).save(any());
    }

    @Test
    void bookingPersistsEtaAndOneFallbackHistoryEvenWhenWorkerRunsAgain() {
        CarrierAdapter ghn = mock(CarrierAdapter.class);
        when(ghn.code()).thenReturn("GHN");
        when(ghn.name()).thenReturn("GHN");
        var expected = OffsetDateTime.parse("2026-11-20T10:00:00Z");
        when(ghn.bookShipment(any(), any())).thenReturn(new CarrierShipmentResult("GHN258", expected, true));
        var service = service(CarrierMode.GHN, List.of(ghn));
        Shipment shipment = create(service);

        service.assignInitialTracking(shipment.getId());
        when(shipments.findDueTrackingForUpdate(eq(shipment.getId()), any())).thenReturn(Optional.empty());
        service.assignTracking(shipment.getId());

        assertThat(shipment.getTrackingCode()).isEqualTo("GHN258");
        assertThat(shipment.getEstimatedDeliveryAt()).isEqualTo(expected);
        var history = ArgumentCaptor.forClass(ShipmentHistory.class);
        verify(histories, times(3)).save(history.capture());
        assertThat(history.getAllValues().stream().map(ShipmentHistory::getDescription))
                .containsExactly("Shipment created",
                        "GHN address fallback: ward and province used after TO_ADDRESS_CONFLICT", "Carrier tracking assigned");
        verify(ghn, times(1)).bookShipment(any(), any());
    }

    private ShipmentCreationService service(CarrierMode booking, List<CarrierAdapter> adapters) {
        return service(properties(booking, null, null, Map.of()), adapters);
    }

    private ShippingProperties properties(CarrierMode booking, UUID warehouse, PickupAddress pickup,
                                          Map<UUID, PickupAddress> pickups) {
        return new ShippingProperties(CarrierMode.NONE, 3000, 0, 0,
                new BigDecimal("1000000"), 5, 30000, "", warehouse, pickup, pickups, CarrierMode.GHN, booking);
    }

    private ShipmentCreationService service(ShippingProperties properties, List<CarrierAdapter> adapters) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        return new ShipmentCreationService(mapper, shipments, histories, new CarrierRegistry(adapters, properties),
                new CarrierExecutor(properties), properties, mock(ShipmentEventPublisher.class),
                mock(PaidOrderSnapshotService.class), manager, 30000);
    }

    private Shipment create(ShipmentCreationService service) {
        Map<String, Object> address = Map.of("line1", "123 Main Street", "provinceCode", "1000001", "wardCode", "1003646");
        Map<String, Object> info = Map.of("carrier", "GHN", "serviceType", "STANDARD",
                "recipient", Map.of("name", "Buyer", "phone", "0900000000", "address", address),
                "pickupAddress", address, "items", List.of(Map.of("productName", "Item", "quantity", 1)),
                "parcel", Map.of("weightGrams", 1000, "lengthCm", 20, "widthCm", 15, "heightCm", 10));
        return create(service, UUID.randomUUID(), info);
    }

    private Map<String, Object> commerceInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("recipient", Map.of("name", "Buyer", "phone", "0900000000", "address",
                Map.of("line1", "123 Main Street", "provinceCode", "1000001", "wardCode", "1003646")));
        info.put("items", List.of(Map.of("skuId", UUID.randomUUID(), "productName", "Item", "quantity", 1)));
        info.put("parcel", Map.of("weightGrams", 1000, "lengthCm", 20, "widthCm", 15, "heightCm", 10));
        info.put("pickupAddress", null);
        return info;
    }

    private Shipment create(ShipmentCreationService service, UUID warehouse, Map<String, Object> info) {
        UUID orderId = UUID.randomUUID();
        var snapshot = new OrderPaidSnapshot(orderId, UUID.randomUUID(), warehouse,
                new BigDecimal("45678"), info, 1, UUID.randomUUID());
        var event = new EventEnvelope<tools.jackson.databind.JsonNode>(UUID.randomUUID(), "OrderPaid", orderId.toString(), Instant.now(),
                "test", mapper.valueToTree(Map.of()));
        var result = service.createPaid(event, snapshot);
        var saved = ArgumentCaptor.forClass(Shipment.class);
        verify(shipments).saveAndFlush(saved.capture());
        Shipment shipment = saved.getValue();
        when(shipments.findDueTrackingForUpdate(eq(result.id()), any())).thenReturn(Optional.of(shipment));
        return shipment;
    }
}
