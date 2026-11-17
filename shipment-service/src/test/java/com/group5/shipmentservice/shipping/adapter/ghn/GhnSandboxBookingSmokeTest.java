package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in only: creates a sandbox waybill, with no pickup, delivery or charge. */
@EnabledIfEnvironmentVariable(named = "GHN_SANDBOX_SMOKE", matches = "true")
class GhnSandboxBookingSmokeTest {
    @Test
    void sandboxBooksAndFindsTheSameWaybillOnRetry() {
        String shopId = requiredEnvironment("GHN_SHOP_ID");
        requiredEnvironment("GHN_TOKEN");
        assertThat(shopId.matches("[1-9][0-9]*")).as("GHN_SHOP_ID must be a positive integer").isTrue();
        var shipping = new ShippingProperties(ShippingProperties.CarrierMode.GHN, 20000, 1, 100,
                BigDecimal.valueOf(1_000_000), 5, 30000, "", null, null, Map.of());
        var properties = new GhnProperties(URI.create("https://dev-online-gateway.ghn.vn"), Integer.valueOf(shopId),
                1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, 8000, 300000);
        var adapter = new GhnAdapter(properties, shipping, new StandardEnvironment(), new ObjectMapper());
        // Public new-format example from https://developer.ghn.vn/en/docs/order/create.
        // Resolve its current codes from GHN; no private pickup/recipient address is read or transmitted.
        var province = adapter.getProvinces(deadline()).stream()
                .filter(locality -> List.of("Hồ Chí Minh", "Thành phố Hồ Chí Minh", "TP. Hồ Chí Minh").contains(locality.name()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Public sample province is absent from GHN master data"));
        var ward = adapter.getWards(province.code(), deadline()).stream()
                .filter(locality -> "Phường Sài Gòn".equals(locality.name()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Public sample ward is absent from GHN master data"));
        Map<String, Object> address = Map.of("line1", "72 Lê Thánh Tôn", "provinceCode", province.code(), "wardCode", ward.code());
        // Stable fixture identity makes re-running this smoke test reuse the original sandbox waybill.
        UUID requestId = UUID.fromString("25800000-0000-4000-8000-000000000001");
        var snapshot = new CarrierShipmentRequest(requestId,
                UUID.fromString("25800000-0000-4000-8000-000000000002"), UUID.fromString("25800000-0000-4000-8000-000000000003"),
                "STANDARD", BigDecimal.valueOf(30000), Map.of(
                        "recipient", Map.of("name", "Nexus Sandbox Test", "phone", "0987654321", "address", address),
                        "pickupAddress", address,
                        "parcel", Map.of("weightGrams", 500, "lengthCm", 20, "widthCm", 15, "heightCm", 10),
                        "items", List.of(Map.of("productName", "Nexus sandbox sample", "quantity", 1))));
        var booking = adapter.bookShipment(snapshot, deadline());
        assertThat(booking.trackingCode()).isNotBlank().doesNotStartWith("FAKE-");
        assertThat(adapter.bookShipment(snapshot, deadline()).trackingCode()).isEqualTo(booking.trackingCode());
        System.out.println("GHN sandbox booking smoke: trackingCode=" + booking.trackingCode());
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(20));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value != null && !value.isBlank()).as(name + " must be configured").isTrue();
        return value;
    }
}
