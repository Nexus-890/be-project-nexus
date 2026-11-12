package com.group5.shipmentservice.shipping.adapter;

import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FakeCarrierAdapterTest {
    @Test
    void bookingReusesTheTrackingCodeAcrossAdapterRestartsAndRetries() {
        CarrierShipmentRequest request = new CarrierShipmentRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "STANDARD", new BigDecimal("30000.00"), Map.of("items", java.util.List.of()));
        String first = new FakeCarrierAdapter().createShipment(request, deadline());

        assertThat(new FakeCarrierAdapter().createShipment(request, deadline())).isEqualTo(first);
        assertThat(first).isEqualTo("FAKE-" + request.requestId()).hasSizeLessThanOrEqualTo(80);
        CarrierShipmentRequest other = new CarrierShipmentRequest(UUID.randomUUID(), request.orderId(),
                request.warehouseId(), request.serviceType(), request.shippingFee(), request.shippingInfo());
        assertThat(new FakeCarrierAdapter().createShipment(other, deadline())).isNotEqualTo(first);
    }

    @Test
    void bookingSnapshotPreservesNullablePreparationFields() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("parcel", null);
        snapshot.put("pickupAddress", null);
        CarrierShipmentRequest request = new CarrierShipmentRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, BigDecimal.ZERO, snapshot);

        snapshot.put("parcel", "changed");

        assertThat(request.shippingInfo()).containsEntry("parcel", null).containsEntry("pickupAddress", null);
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(1));
    }
}
