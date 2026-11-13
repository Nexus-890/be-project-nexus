package com.group5.shipmentservice.shipping.adapter;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeCarrierTrackingTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void fakePollingAndCancellationRemainOfflineAndIdempotent() {
        var adapter = new FakeCarrierAdapter(clock, "fake-secret");
        String code = "FAKE-" + UUID.randomUUID();
        assertThat(adapter.track(code, deadline()).status()).isEqualTo(ShipmentStatus.CREATED);
        adapter.setTrackingStatus(code, ShipmentStatus.IN_TRANSIT);
        assertThat(adapter.track(code, deadline()).status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        UUID cancellation = UUID.randomUUID();
        adapter.cancelShipment(code, cancellation, deadline());
        var first = adapter.track(code, deadline());
        adapter.cancelShipment(code, cancellation, deadline());
        assertThat(adapter.track(code, deadline())).isEqualTo(first);
        assertThat(first.status()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void fakeWebhookHasExplicitCredentialsAndStableIdentity() {
        var adapter = new FakeCarrierAdapter(clock, "fake-secret");
        byte[] body = "{\"OrderCode\":\"FAKE-001\",\"Status\":\"IN_TRANSIT\",\"Type\":\"switch_status\",\"Time\":\"2026-11-11T01:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8);
        adapter.verifyWebhook(body, "fake-secret");
        assertThat(adapter.parseWebhook(body)).isEqualTo(adapter.parseWebhook(body));
        assertThat(adapter.parseWebhook(body).status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        byte[] differentStatus = new String(body, StandardCharsets.UTF_8).replace("IN_TRANSIT", "DELIVERED")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.parseWebhook(differentStatus).carrierEventId())
                .isNotEqualTo(adapter.parseWebhook(body).carrierEventId());
        assertThatThrownBy(() -> new FakeCarrierAdapter(clock).verifyWebhook(body, "fake-secret"))
                .isInstanceOf(DomainException.class).extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("SHIPMENT_INVALID_SIGNATURE");
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(1));
    }
}
