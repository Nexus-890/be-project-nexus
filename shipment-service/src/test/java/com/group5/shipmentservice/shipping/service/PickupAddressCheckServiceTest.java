package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.dto.request.PickupAddressRequest;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PickupAddressCheckServiceTest {
    private final CarrierAdapter adapter = ghn();
    private final ShippingAddressService addresses = mock(ShippingAddressService.class);
    private final PickupAddressCheckService service = new PickupAddressCheckService(
            new CarrierRegistry(List.of(adapter)), new CarrierExecutor(properties()), addresses);

    private static CarrierAdapter ghn() {
        CarrierAdapter adapter = mock(CarrierAdapter.class);
        when(adapter.code()).thenReturn("GHN");
        when(adapter.name()).thenReturn("Giao Hang Nhanh");
        return adapter;
    }

    @Test
    void quotesFromTheFullPickupLineToItsOwnWardAndReturnsCanonicalNames() {
        when(addresses.resolveAddress(eq(adapter), eq("1000001"), eq("1003504"), eq("12 Le Loi"), any()))
                .thenReturn(new CarrierAddress("Hồ Chí Minh", "Phường Bến Thành", "12 Le Loi"));
        when(adapter.quote(any(), any())).thenReturn(List.of(new ShippingQuoteResponse("GHN",
                ShippingServiceType.STANDARD, new BigDecimal("16500"), OffsetDateTime.parse("2026-11-27T10:00:00Z"))));

        var result = service.check(new PickupAddressRequest("1000001", "1003504", "  12 Le Loi "));

        assertThat(result.carrier()).isEqualTo("GHN");
        assertThat(result.provinceName()).isEqualTo("Hồ Chí Minh");
        assertThat(result.wardName()).isEqualTo("Phường Bến Thành");
        assertThat(result.line1()).isEqualTo("12 Le Loi");
        ArgumentCaptor<CarrierQuoteRequest> quote = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(adapter).quote(quote.capture(), any());
        assertThat(quote.getValue().pickup().addressLine()).isEqualTo("12 Le Loi");
        assertThat(quote.getValue().toAddress().wardName()).isEqualTo("Phường Bến Thành");
        assertThat(quote.getValue().toAddress().provinceName()).isEqualTo("Hồ Chí Minh");
    }

    @Test
    void aWardOutsideTheProvinceStopsBeforeTheCarrierQuote() {
        when(addresses.resolveAddress(eq(adapter), any(), any(), any(), any()))
                .thenThrow(new DomainException(ShipmentErrorCode.INVALID_ADDRESS));

        assertCode(() -> service.check(new PickupAddressRequest("1000001", "1003445", "1 Trang Tien")),
                "SHIPMENT_INVALID_ADDRESS");
        verify(adapter, never()).quote(any(), any());
    }

    @Test
    void aCarrierConflictBetweenStreetAndWardIsReported() {
        when(addresses.resolveAddress(eq(adapter), any(), any(), any(), any()))
                .thenReturn(new CarrierAddress("Hà Nội", "Phường Hoàn Kiếm", "25 Hang Bai"));
        when(adapter.quote(any(), any())).thenThrow(new DomainException(ShipmentErrorCode.PICKUP_ADDRESS_CONFLICT));

        assertCode(() -> service.check(new PickupAddressRequest("1000002", "1003445", "25 Hang Bai")),
                "SHIPMENT_PICKUP_ADDRESS_CONFLICT");
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private static ShippingProperties properties() {
        return new ShippingProperties(ShippingProperties.CarrierMode.FAKE, 3000, 0, 0, new BigDecimal("1000000"),
                5, 30000, "http://catalog-service", null,
                new PickupAddress("1000001", "1003504", "12 Le Loi"), Map.of());
    }
}
