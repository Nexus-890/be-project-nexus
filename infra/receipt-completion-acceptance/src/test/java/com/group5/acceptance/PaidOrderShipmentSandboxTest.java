package com.group5.acceptance;

import com.group5.common_events.EventEnvelope;
import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties.CarrierMode;
import com.group5.shipmentservice.shipping.adapter.ghn.GhnAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: actual payment-confirmation/outbox/worker path against GHN sandbox, without browser/dashboard. */
@EnabledIfEnvironmentVariable(named = "GHN_SANDBOX_SMOKE", matches = "true")
class PaidOrderShipmentSandboxTest {
    @Test
    @SuppressWarnings("unchecked")
    void paidOrderOutboxBooksOnTheWorkerAndReusesItsGhnSandboxWaybill() {
        String token = requiredEnvironment("GHN_TOKEN");
        String shop = requiredEnvironment("GHN_SHOP_ID");
        assertThat(shop.matches("[1-9][0-9]*")).as("GHN_SHOP_ID must be a positive integer").isTrue();
        var environment = new MockEnvironment().withProperty("GHN_TOKEN", token);
        var api = new GhnProperties(URI.create("https://dev-online-gateway.ghn.vn"), Integer.valueOf(shop),
                1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, 8000, 300000);
        var discovery = new GhnAdapter(api, shipping(null), environment, JsonMapper.shared());
        // Public new-address example from https://developer.ghn.vn/en/docs/order/create.
        // Resolve current codes; no private pickup or recipient configuration is read.
        var province = discovery.getProvinces(deadline()).stream()
                .filter(locality -> List.of("Hồ Chí Minh", "Thành phố Hồ Chí Minh", "TP. Hồ Chí Minh").contains(locality.name()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Public sample province absent from GHN master data"));
        var ward = discovery.getWards(province.code(), deadline()).stream()
                .filter(locality -> "Phường Sài Gòn".equals(locality.name()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Public sample ward absent from GHN master data"));
        var properties = shipping(new PickupAddress(province.code(), ward.code(), "72 Lê Thánh Tôn"));
        var adapter = new GhnAdapter(api, properties, environment, JsonMapper.shared());
        try (var commerce = new CommercePaidShipmentFixture();
             var shipment = new ShipmentPaidFixture(properties, List.of(adapter))) {
            var paid = commerce.pay(ShipmentPaidFixture.WAREHOUSE, "72 Lê Thánh Tôn", province.code(), ward.code());
            shipment.commitBeforeInitialBooking(paid);
            assertThat(shipment.status()).isEqualTo("PENDING_TRACKING");
            assertThat(shipment.tracking()).isNull();

            shipment.runDueWorker();

            String code = shipment.tracking();
            assertThat(code).isNotBlank().doesNotStartWith("FAKE-");
            assertThat(shipment.status()).isEqualTo("CREATED");
            assertThat(shipment.jdbc.queryForObject("SELECT order_id FROM shipments", UUID.class))
                    .isEqualTo(UUID.fromString(paid.aggregateId()));
            assertThat(shipment.jdbc.queryForObject("SELECT carrier FROM shipments", String.class)).isEqualTo("GHN");
            assertThat(shipment.jdbc.queryForObject("SELECT service_type FROM shipments", String.class)).isEqualTo("STANDARD");
            assertThat(shipment.jdbc.queryForObject("SELECT shipping_fee FROM shipments", BigDecimal.class))
                    .isEqualByComparingTo(paid.payload().path("shippingFee").asString());
            assertThat(shipment.snapshot().path("recipient")).isEqualTo(paid.payload().path("shippingInfo").path("recipient"));
            assertThat(shipment.count("shipments")).isEqualTo(1);
            long historyCount = shipment.count("shipment_history");
            long outboxCount = shipment.count("outbox");

            shipment.receive(paid);
            shipment.receive(new EventEnvelope<>(UUID.randomUUID(), paid.eventType(), paid.aggregateId(),
                    paid.occurredAt(), paid.traceId(), paid.payload()));
            shipment.runDueWorker();
            var replay = adapter.bookShipment(new CarrierShipmentRequest(shipment.id(),
                    UUID.fromString(paid.aggregateId()), ShipmentPaidFixture.WAREHOUSE, "STANDARD",
                    new BigDecimal(paid.payload().path("shippingFee").asString()),
                    JsonMapper.shared().convertValue(shipment.snapshot(), Map.class)), deadline());

            assertThat(replay.trackingCode()).isEqualTo(code);
            assertThat(shipment.tracking()).isEqualTo(code);
            assertThat(shipment.count("shipment_history")).isEqualTo(historyCount);
            assertThat(shipment.count("outbox")).isEqualTo(outboxCount);
            assertThat(shipment.count("shipments")).isEqualTo(1);
            System.out.println("Paid-order GHN sandbox acceptance: trackingCode=" + code);
        }
    }

    private ShippingProperties shipping(PickupAddress pickup) {
        return new ShippingProperties(CarrierMode.NONE, 20000, 1, 100, BigDecimal.valueOf(1_000_000),
                5, 30000, "", ShipmentPaidFixture.WAREHOUSE, pickup, Map.of(), CarrierMode.GHN, CarrierMode.GHN);
    }

    private ShippingDeadline deadline() { return ShippingDeadline.after(Duration.ofSeconds(20)); }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value != null && !value.isBlank()).as(name + " must be configured").isTrue();
        return value;
    }
}
