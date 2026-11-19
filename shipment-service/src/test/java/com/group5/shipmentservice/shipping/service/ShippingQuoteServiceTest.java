package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.dto.request.ParcelRequest;
import com.group5.shipmentservice.shipping.dto.request.ShippingAddressRequest;
import com.group5.shipmentservice.shipping.dto.request.ShippingItemRequest;
import com.group5.shipmentservice.shipping.dto.request.ShippingQuoteRequest;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShippingQuoteServiceTest {
    private static final UUID WAREHOUSE = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final CarrierRegistry carriers = mock(CarrierRegistry.class);
    private final ShippingAddressService addresses = mock(ShippingAddressService.class);
    private final ShippingCatalogService catalog = mock(ShippingCatalogService.class);
    private final CarrierExecutor executor = mock(CarrierExecutor.class);

    @Test
    void missingDefaultWarehouseFailsBeforeCallingAnyDependency() {
        ShippingQuoteService service = service(null, Map.of());

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CARRIER_CONFIGURATION_ERROR"));
        verifyNoInteractions(carriers, addresses, catalog, executor);
    }

    @Test
    void defaultWarehouseWithoutPickupMappingDoesNotUseFabricatedAddress() {
        ShippingQuoteService service = service(WAREHOUSE, Map.of());

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CARRIER_CONFIGURATION_ERROR"));
        verifyNoInteractions(carriers, addresses, catalog, executor);
    }

    @Test
    void malformedPickupMappingFailsBeforeCallingCarrier() {
        ShippingQuoteService service = service(WAREHOUSE,
                Map.of(WAREHOUSE, new PickupAddress("1000001", "abc", "Địa chỉ lấy hàng kiểm thử")));

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CARRIER_CONFIGURATION_ERROR"));
        verifyNoInteractions(carriers, addresses, catalog, executor);
    }

    @Test
    void defaultPickupConfigurationCanResolveTheDefaultWarehouse() {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 100, new BigDecimal("1000000"), 5, 30000,
                "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1003646", "Địa chỉ lấy hàng kiểm thử"), Map.of());
        CarrierExecutor realExecutor = new CarrierExecutor(properties);
        CarrierRegistry registry = new CarrierRegistry(List.of(new FakeCarrierAdapter()));
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("500000"));
        ShippingQuoteService service = new ShippingQuoteService(properties, registry,
                new ShippingAddressService(registry, realExecutor), catalog, realExecutor);

        assertThat(service.quote(request())).singleElement().satisfies(quote -> {
            assertThat(quote.carrier()).isEqualTo("FAKE");
            assertThat(quote.fee()).isEqualByComparingTo("30000");
        });
        verify(catalog).getSubtotal(anyList(), any());
    }

    @Test
    void carrierReceivesResolvedTwoLevelAddressNamesAndRecipientDetails() {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 100, new BigDecimal("1000000"), 5, 30000,
                "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1003646", "Địa chỉ lấy hàng kiểm thử"), Map.of());
        CarrierExecutor realExecutor = new CarrierExecutor(properties);
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierRegistry registry = new CarrierRegistry(List.of(adapter));
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("500000"));
        ShippingQuoteService service = new ShippingQuoteService(properties, registry,
                new ShippingAddressService(registry, realExecutor), catalog, realExecutor);

        service.quote(request());

        ArgumentCaptor<CarrierQuoteRequest> captured = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(adapter).quote(captured.capture(), any());
        CarrierQuoteRequest carrierRequest = captured.getValue();
        assertThat(carrierRequest.pickup().provinceName()).isEqualTo("Hồ Chí Minh");
        assertThat(carrierRequest.pickup().wardName()).isEqualTo("Phường Vũng Tàu");
        assertThat(carrierRequest.pickup().addressLine()).isEqualTo("Địa chỉ lấy hàng kiểm thử");
        assertThat(carrierRequest.toAddress().provinceName()).isEqualTo("Hà Nội");
        assertThat(carrierRequest.toAddress().wardName()).isEqualTo("Phường Ba Đình");
        assertThat(carrierRequest.toAddress().addressLine()).isEqualTo("Địa chỉ kiểm thử");
        assertThat(carrierRequest.recipientName()).isEqualTo("Người nhận kiểm thử");
        assertThat(carrierRequest.recipientPhone()).isEqualTo("0901234567");
        assertThat(carrierRequest.insuranceValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(carrierRequest.content()).isEqualTo("Đơn hàng gồm 1 sản phẩm thuộc 1 SKU, đóng trong 1 kiện");
    }

    @Test
    void auctionCanRequestRawCarrierFeeEvenWhenCurrentCatalogPriceQualifiesForFreeShipping() {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 100, new BigDecimal("1000000"), 5, 30000,
                "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1003646", "Địa chỉ lấy hàng kiểm thử"), Map.of());
        CarrierExecutor realExecutor = new CarrierExecutor(properties);
        CarrierRegistry registry = new CarrierRegistry(List.of(new FakeCarrierAdapter()));
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("1500000"));
        ShippingQuoteService service = new ShippingQuoteService(properties, registry,
                new ShippingAddressService(registry, realExecutor), catalog, realExecutor);
        ShippingQuoteRequest baseline = request();
        ShippingQuoteRequest auctionQuote = new ShippingQuoteRequest(baseline.toAddress(), baseline.items(),
                baseline.parcel(), baseline.warehouseId(), baseline.insuranceValue(), false);

        assertThat(service.quote(auctionQuote)).singleElement()
                .satisfies(quote -> assertThat(quote.fee()).isEqualByComparingTo("30000"));
        // Không thay hành vi các caller cũ: giá Catalog đủ ngưỡng vẫn được miễn phí.
        assertThat(service.quote(baseline)).singleElement()
                .satisfies(quote -> assertThat(quote.fee()).isEqualByComparingTo(BigDecimal.ZERO));
        ShippingQuoteRequest nullFlag = new ShippingQuoteRequest(baseline.toAddress(), baseline.items(),
                baseline.parcel(), baseline.warehouseId(), baseline.insuranceValue(), null);
        assertThat(nullFlag.applyFreeShipping()).isTrue();
        assertThat(service.quote(nullFlag)).singleElement()
                .satisfies(quote -> assertThat(quote.fee()).isEqualByComparingTo(BigDecimal.ZERO));
        var oldJson = tools.jackson.databind.json.JsonMapper.shared().valueToTree(baseline).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) oldJson).remove("applyFreeShipping");
        ShippingQuoteRequest oldClientRequest = tools.jackson.databind.json.JsonMapper.shared()
                .treeToValue(oldJson, ShippingQuoteRequest.class);
        assertThat(oldClientRequest.applyFreeShipping()).isTrue();
        assertThat(service.quote(oldClientRequest)).singleElement()
                .satisfies(quote -> assertThat(quote.fee()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void declaredInsuranceAndActualItemCountsArePreparedIndependentlyOfCatalogSubtotal() {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 100, new BigDecimal("1000000"), 5, 30000,
                "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1003646", "Địa chỉ lấy hàng kiểm thử"), Map.of());
        CarrierExecutor realExecutor = new CarrierExecutor(properties);
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierRegistry registry = new CarrierRegistry(List.of(adapter));
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("9000000.25"));
        ShippingQuoteService service = new ShippingQuoteService(properties, registry,
                new ShippingAddressService(registry, realExecutor), catalog, realExecutor);
        ShippingQuoteRequest baseline = request();
        UUID firstSku = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID secondSku = UUID.fromString("33333333-3333-4333-8333-333333333333");
        ShippingQuoteRequest quoteRequest = new ShippingQuoteRequest(baseline.toAddress(), List.of(
                new ShippingItemRequest(firstSku, 2), new ShippingItemRequest(firstSku, 3),
                new ShippingItemRequest(secondSku, 4)), baseline.parcel(), null, new BigDecimal("123456"));

        assertThat(service.quote(quoteRequest)).singleElement()
                .satisfies(quote -> assertThat(quote.fee()).isEqualByComparingTo(BigDecimal.ZERO));

        ArgumentCaptor<CarrierQuoteRequest> captured = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(adapter).quote(captured.capture(), any());
        assertThat(captured.getValue().insuranceValue()).isEqualByComparingTo("123456");
        assertThat(captured.getValue().content())
                .isEqualTo("Đơn hàng gồm 9 sản phẩm thuộc 2 SKU, đóng trong 1 kiện");
    }

    @Test
    void pickupWardFromAnotherProvinceIsAConfigurationFailure() {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 100, new BigDecimal("1000000"), 5, 30000,
                "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1000003", "Địa chỉ lấy hàng kiểm thử"), Map.of());
        CarrierExecutor realExecutor = new CarrierExecutor(properties);
        CarrierRegistry registry = new CarrierRegistry(List.of(new FakeCarrierAdapter()));
        ShippingQuoteService service = new ShippingQuoteService(properties, registry,
                new ShippingAddressService(registry, realExecutor), catalog, realExecutor);

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CARRIER_CONFIGURATION_ERROR"));
        verifyNoInteractions(catalog);
    }

    @Test
    void openCarrierBreakerRejectsTheNextQuoteBeforeCatalogLookup() {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 0, 0, new BigDecimal("1000000"), 1, 30000,
                "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1003646", "Địa chỉ lấy hàng kiểm thử"), Map.of());
        CarrierExecutor realExecutor = new CarrierExecutor(properties);
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierRegistry registry = new CarrierRegistry(List.of(adapter));
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("500000"));
        doThrow(new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE)).when(adapter).quote(any(), any());
        ShippingQuoteService service = new ShippingQuoteService(properties, registry,
                new ShippingAddressService(registry, realExecutor), catalog, realExecutor);

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE"));
        clearInvocations(catalog, adapter);

        assertThatThrownBy(() -> service.quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CIRCUIT_OPEN"));
        verifyNoInteractions(catalog);
        verify(adapter, never()).quote(any(), any());
    }

    @Test
    void everyOfferedCarrierIsQuotedAndTheCheapestIsRecommendedByDefault() {
        var setup = multiCarrier(ShippingProperties.CarrierStrategy.CHEAPEST);

        List<ShippingQuoteResponse> quotes = setup.service().quote(request());

        assertThat(quotes).extracting(ShippingQuoteResponse::carrier).containsExactly("FAKE", "GHN");
        assertThat(quotes).extracting(ShippingQuoteResponse::recommended).containsExactly(true, false);
        assertThat(quotes).extracting(ShippingQuoteResponse::carrierName)
                .containsExactly("Nhà vận chuyển giả lập", "Giao Hàng Nhanh");
        assertThat(quotes.get(1).fee()).isEqualByComparingTo("45000");
        verify(catalog).getSubtotal(anyList(), any());
    }

    @Test
    void fastestStrategyRecommendsTheEarliestArrival() {
        var setup = multiCarrier(ShippingProperties.CarrierStrategy.FASTEST);

        List<ShippingQuoteResponse> quotes = setup.service().quote(request());

        assertThat(quotes).extracting(ShippingQuoteResponse::carrier).containsExactly("GHN", "FAKE");
        assertThat(quotes.getFirst().recommended()).isTrue();
    }

    @Test
    void aFailingCarrierIsLeftOutAndOnlyAllFailingFailsTheQuote() {
        var setup = multiCarrier(ShippingProperties.CarrierStrategy.FASTEST);
        when(setup.ghn().quote(any(), any())).thenThrow(new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE));

        assertThat(setup.service().quote(request())).singleElement().satisfies(quote -> {
            assertThat(quote.carrier()).isEqualTo("FAKE");
            assertThat(quote.recommended()).isTrue();
        });

        doThrow(new DomainException(ShipmentErrorCode.NO_SERVICE)).when(setup.fake()).quote(any(), any());
        assertThatThrownBy(() -> setup.service().quote(request()))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE"));
    }

    private record MultiCarrier(ShippingQuoteService service, CarrierAdapter ghn, FakeCarrierAdapter fake) { }

    private MultiCarrier multiCarrier(ShippingProperties.CarrierStrategy strategy) {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.NONE,
                3000, 0, 0, new BigDecimal("1000000"), 5, 30000, "http://catalog-service", WAREHOUSE,
                new PickupAddress("1000001", "1003646", "Địa chỉ lấy hàng kiểm thử"), Map.of(),
                ShippingProperties.CarrierMode.GHN, ShippingProperties.CarrierMode.GHN,
                List.of(ShippingProperties.CarrierMode.FAKE), strategy);
        CarrierAdapter ghn = mock(CarrierAdapter.class);
        when(ghn.code()).thenReturn("GHN");
        when(ghn.name()).thenReturn("Giao Hàng Nhanh");
        when(ghn.quote(any(), any())).thenReturn(List.of(new ShippingQuoteResponse("GHN", ShippingServiceType.STANDARD,
                new BigDecimal("45000"), OffsetDateTime.now().plusDays(1))));
        FakeCarrierAdapter fake = spy(new FakeCarrierAdapter());
        CarrierRegistry registry = new CarrierRegistry(List.of(ghn, fake), properties);
        when(addresses.resolveAddress(any(), any(), any(), any(), any()))
                .thenReturn(new CarrierAddress("Hồ Chí Minh", "Phường Vũng Tàu", "Địa chỉ kiểm thử"));
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("500000"));
        return new MultiCarrier(new ShippingQuoteService(properties, registry, addresses, catalog,
                new CarrierExecutor(properties)), ghn, fake);
    }

    private ShippingQuoteService service(UUID defaultWarehouse, Map<UUID, PickupAddress> pickups) {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 100, new BigDecimal("1000000"), 5, 30000,
                "http://catalog-service", defaultWarehouse, null, pickups);
        return new ShippingQuoteService(properties, carriers, addresses, catalog, executor);
    }

    private ShippingQuoteRequest request() {
        return new ShippingQuoteRequest(new ShippingAddressRequest("1000002", "1000003", "Địa chỉ kiểm thử",
                "Người nhận kiểm thử", "0901234567"),
                List.of(new ShippingItemRequest(UUID.fromString("22222222-2222-4222-8222-222222222222"), 1)),
                new ParcelRequest(1200, 30, 20, 15), null, null);
    }
}
