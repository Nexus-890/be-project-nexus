package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.dto.response.CarrierResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class CarrierServiceTest {
    @Test
    void configuredCarrierIsDefaultAndInitiallyAvailableWithoutProbingCarrier() {
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierExecutor executor = executor();
        CarrierService service = new CarrierService(new CarrierRegistry(List.of(adapter)), executor);

        assertThat(service.getCarriers()).containsExactly(
                new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, true));

        verifyNoProbe(adapter);
    }

    @Test
    void openCarrierBreakerChangesAvailabilityWithoutRemovingDefaultCarrier() {
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierExecutor executor = executor();
        CarrierService service = new CarrierService(new CarrierRegistry(List.of(adapter)), executor);
        assertThatThrownBy(() -> executor.execute("FAKE", deadline -> {
            throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
        })).isInstanceOf(DomainException.class);

        assertThat(service.getCarriers()).containsExactly(
                new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, false));
        assertThat(service.getCarriers()).containsExactly(
                new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, false));

        verifyNoProbe(adapter);
    }

    @Test
    void catalogBreakerDoesNotChangeCarrierAvailability() {
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierExecutor executor = executor();
        CarrierService service = new CarrierService(new CarrierRegistry(List.of(adapter)), executor);
        assertThatThrownBy(() -> executor.execute("CATALOG", deadline -> {
            throw new DomainException(ShipmentErrorCode.CATALOG_UNAVAILABLE);
        })).isInstanceOf(DomainException.class);

        assertThat(service.getCarriers()).containsExactly(
                new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, true));

        verifyNoProbe(adapter);
    }

    @Test
    void carrierBecomesAvailableForRecoveryAfterTheOpenWindowWithoutAProbe() throws Exception {
        FakeCarrierAdapter adapter = spy(new FakeCarrierAdapter());
        CarrierExecutor executor = executor(30);
        CarrierService service = new CarrierService(new CarrierRegistry(List.of(adapter)), executor);
        assertThatThrownBy(() -> executor.execute("FAKE", deadline -> {
            throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
        })).isInstanceOf(DomainException.class);
        long stopWaitingAt = System.nanoTime() + Duration.ofSeconds(2).toNanos();

        while (!service.getCarriers().getFirst().available() && System.nanoTime() < stopWaitingAt) {
            Thread.sleep(10);
        }

        assertThat(service.getCarriers()).containsExactly(
                new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, true));
        verifyNoProbe(adapter);
        assertThat(executor.<String>execute("FAKE", deadline -> "phục hồi")).isEqualTo("phục hồi");
    }

    @Test
    void emptyRegistryReturnsAnEmptyList() {
        CarrierService service = new CarrierService(new CarrierRegistry(List.of()), executor());

        assertThat(service.getCarriers()).isEmpty();
    }

    private CarrierExecutor executor() {
        return executor(30000);
    }

    private CarrierExecutor executor(int openMs) {
        return new CarrierExecutor(new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 0, 0, BigDecimal.valueOf(1_000_000), 1, openMs,
                "http://catalog-service", null, null, Map.of()));
    }

    private void verifyNoProbe(FakeCarrierAdapter adapter) {
        verify(adapter, never()).getProvinces(any());
        verify(adapter, never()).getWards(any(), any());
        verify(adapter, never()).quote(any(), any());
    }
}
