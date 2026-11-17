package com.group5.shipmentservice.shipping.adapter.ghn;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #258 T1: actual HTTP requests, provider failures and uncertain booking outcomes. */
class GhnBookingWireMockTest {
    private static final String LOOKUP = "/shiip/public-api/v2/shipping-order/detail-by-client-code";
    private static final String CREATE = "/shiip/public-api/v2/shipping-order/create";
    private static final UUID REQUEST_ID = UUID.fromString("25800000-0000-4000-8000-000000000101");
    private static final String FULL_ADDRESS = "12 Lê Lợi, Phường Sài Gòn, Hồ Chí Minh";
    private static final String AREA_ADDRESS = "Phường Sài Gòn, Hồ Chí Minh";
    private final ObjectMapper mapper = new ObjectMapper();
    private WireMockServer ghn;

    @BeforeEach
    void startProvider() {
        ghn = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        ghn.start();
        ghn.stubFor(get(urlPathEqualTo("/shiip/public-api/v3/master-data/province/all"))
                .willReturn(okJson("""
                        {"code":200,"data":[{"_id":1000001,"name":"Hồ Chí Minh","type":"province","parent_id":1,"status":1}]}
                        """)));
        ghn.stubFor(get(urlPathEqualTo("/shiip/public-api/v3/master-data/ward/all-by-province-id"))
                .withQueryParam("province_id", equalTo("1000001"))
                .willReturn(okJson("""
                        {"code":200,"data":[{"_id":1003646,"name":"Phường Sài Gòn","type":"ward","parent_id":1000001,"status":1}]}
                        """)));
        ghn.stubFor(get(urlPathEqualTo(LOOKUP)).atPriority(10)
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":400,\"code_message\":\"ORDER_NOT_FOUND\",\"data\":null}")));
    }

    @AfterEach
    void stopProvider() {
        ghn.stop();
    }

    @Test
    void sendsPaidSnapshotBodyAndConfiguredCredentialsAndReturnsOrderCodeAndEta() {
        ghn.stubFor(post(urlPathEqualTo(CREATE)).willReturn(okJson(created())));
        var shipping = shipping(3000, 1);
        var result = new CarrierExecutor(shipping).execute("GHN", deadline ->
                adapter(shipping, 1500).bookShipment(snapshot(), deadline));

        assertThat(result.trackingCode()).isEqualTo("GHN-WM-258");
        assertThat(result.estimatedDeliveryAt().toInstant()).isEqualTo(Instant.parse("2026-11-20T16:59:59Z"));
        assertThat(result.addressFallback()).isFalse();
        ghn.verify(1, getRequestedFor(urlPathEqualTo(LOOKUP))
                .withQueryParam("client_order_code", equalTo(REQUEST_ID.toString())));
        ghn.verify(1, postRequestedFor(urlPathEqualTo(CREATE))
                .withHeader("Token", equalTo("wiremock-test-token"))
                .withHeader("ShopId", equalTo("12345"))
                .withRequestBody(matchingJsonPath("$.client_order_code", equalTo(REQUEST_ID.toString())))
                .withRequestBody(matchingJsonPath("$.to_name", equalTo("Nguyễn Văn An")))
                .withRequestBody(matchingJsonPath("$.to_phone", equalTo("0901234567")))
                .withRequestBody(matchingJsonPath("$.to_address", equalTo(FULL_ADDRESS)))
                .withRequestBody(matchingJsonPath("$.to_ward_name", equalTo("Phường Sài Gòn")))
                .withRequestBody(matchingJsonPath("$.to_province_name", equalTo("Hồ Chí Minh")))
                .withRequestBody(matchingJsonPath("$.is_new_to_address", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.is_new_from_address", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.from_address", equalTo("20 Nguyễn Huệ, Phường Sài Gòn, Hồ Chí Minh")))
                .withRequestBody(matchingJsonPath("$.weight", equalTo("1200")))
                .withRequestBody(matchingJsonPath("$.length", equalTo("30")))
                .withRequestBody(matchingJsonPath("$.width", equalTo("20")))
                .withRequestBody(matchingJsonPath("$.height", equalTo("15")))
                .withRequestBody(matchingJsonPath("$.service_type_id", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.payment_type_id", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.required_note", equalTo("KHONGCHOXEMHANG")))
                .withRequestBody(matchingJsonPath("$.cod_amount", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.items[0].name", equalTo("Áo thun")))
                .withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("2")))
                .withRequestBody(notMatching(".*to_district_id.*")));
    }

    @Test
    void timeoutAfterProviderCommitLooksUpSameClientCodeAndNeverCreatesTwice() {
        ghn.stubFor(post(urlPathEqualTo(CREATE)).inScenario("uncertain-booking")
                .whenScenarioStateIs(STARTED).willSetStateTo("BOOKED")
                .willReturn(okJson(created()).withFixedDelay(1200)));
        ghn.stubFor(get(urlPathEqualTo(LOOKUP)).atPriority(1).inScenario("uncertain-booking")
                .whenScenarioStateIs("BOOKED")
                .withQueryParam("client_order_code", equalTo(REQUEST_ID.toString()))
                .willReturn(okJson(existing(null))));
        var adapter = adapter(shipping(4000, 2), 400);
        // Warm master-data requests so the intentionally lost create response is the timeout under test.
        adapter.getProvinces(deadline());
        adapter.getWards("1000001", deadline());
        ghn.resetRequests();

        var result = adapter.bookShipment(snapshot(), deadline());

        assertThat(result.trackingCode()).isEqualTo("GHN-WM-258");
        ghn.verify(1, postRequestedFor(urlPathEqualTo(CREATE))
                .withRequestBody(matchingJsonPath("$.client_order_code", equalTo(REQUEST_ID.toString()))));
        ghn.verify(2, getRequestedFor(urlPathEqualTo(LOOKUP))
                .withQueryParam("client_order_code", equalTo(REQUEST_ID.toString())));
        assertThat(ghn.getAllServeEvents().getFirst().getRequest().getUrl()).startsWith(LOOKUP);
    }

    @Test
    void newAdapterFindsExistingWaybillAndRecoversFallbackWithoutAnotherCreate() {
        ghn.stubFor(get(urlPathEqualTo(LOOKUP)).atPriority(1)
                .willReturn(okJson(existing(GhnAdapter.ADDRESS_FALLBACK_NOTE))));

        var result = adapter(shipping(3000, 1), 1500).bookShipment(snapshot(), deadline());

        assertThat(result.trackingCode()).isEqualTo("GHN-WM-258");
        assertThat(result.addressFallback()).isTrue();
        ghn.verify(0, postRequestedFor(urlPathEqualTo(CREATE)));
        assertThat(ghn.getAllServeEvents()).hasSize(1);
    }

    @Test
    void addressConflictRetriesOnceWithAreaAddressAndSameClientCode() {
        ghn.stubFor(post(urlPathEqualTo(CREATE)).inScenario("address-conflict")
                .whenScenarioStateIs(STARTED).willSetStateTo("USE_AREA")
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(conflict())));
        ghn.stubFor(post(urlPathEqualTo(CREATE)).inScenario("address-conflict")
                .whenScenarioStateIs("USE_AREA")
                .withRequestBody(matchingJsonPath("$.to_address", equalTo(AREA_ADDRESS)))
                .willReturn(okJson(created())));

        var result = adapter(shipping(3000, 2), 1500).bookShipment(snapshot(), deadline());

        assertThat(result.addressFallback()).isTrue();
        ghn.verify(2, postRequestedFor(urlPathEqualTo(CREATE))
                .withRequestBody(matchingJsonPath("$.client_order_code", equalTo(REQUEST_ID.toString()))));
        ghn.verify(1, postRequestedFor(urlPathEqualTo(CREATE))
                .withRequestBody(matchingJsonPath("$.to_address", equalTo(FULL_ADDRESS))));
        ghn.verify(1, postRequestedFor(urlPathEqualTo(CREATE))
                .withRequestBody(matchingJsonPath("$.to_address", equalTo(AREA_ADDRESS)))
                .withRequestBody(matchingJsonPath("$.note", equalTo(GhnAdapter.ADDRESS_FALLBACK_NOTE))));
    }

    @Test
    void anotherAddressConflictDoesNotTriggerAnUnboundedFallbackLoop() {
        ghn.stubFor(post(urlPathEqualTo(CREATE)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(conflict())));

        assertThatThrownBy(() -> adapter(shipping(3000, 3), 1500).bookShipment(snapshot(), deadline()))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("SHIPMENT_INVALID_ADDRESS");
        ghn.verify(2, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    @Test
    void fiveHundredsMapToCarrierCodeWithoutExposingProviderMessage() {
        ghn.stubFor(post(urlPathEqualTo(CREATE)).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":503,\"message\":\"private-provider-detail\"}")));

        assertThatThrownBy(() -> adapter(shipping(3000, 1), 1500).bookShipment(snapshot(), deadline()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE"))
                .hasMessageNotContaining("private-provider-detail");
        ghn.verify(2, postRequestedFor(urlPathEqualTo(CREATE))
                .withRequestBody(matchingJsonPath("$.client_order_code", equalTo(REQUEST_ID.toString()))));
        ghn.verify(2, getRequestedFor(urlPathEqualTo(LOOKUP)));
    }

    @Test
    void carrierExecutorDeadlineLimitsAStalledLookupAndPreventsCreatingAfterExpiry() {
        ghn.stubFor(get(urlPathEqualTo(LOOKUP)).atPriority(1)
                .willReturn(okJson(existing(null)).withFixedDelay(1500)));
        var shipping = shipping(250, 2);
        var adapter = adapter(shipping, 5000);
        long started = System.nanoTime();

        assertThatThrownBy(() -> new CarrierExecutor(shipping).execute("GHN", deadline ->
                adapter.bookShipment(snapshot(), deadline)))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        ghn.verify(0, postRequestedFor(urlPathEqualTo(CREATE)));
    }

    private GhnAdapter adapter(ShippingProperties shipping, int httpTimeoutMs) {
        return new GhnAdapter(new GhnProperties(URI.create("http://127.0.0.1:" + ghn.port()), 12345,
                1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, httpTimeoutMs, 300000), shipping,
                new MockEnvironment().withProperty("GHN_TOKEN", "wiremock-test-token"), mapper);
    }

    private ShippingProperties shipping(int timeoutMs, int retries) {
        return new ShippingProperties(ShippingProperties.CarrierMode.GHN, timeoutMs, retries, 10,
                BigDecimal.valueOf(1_000_000), 5, 30000, "", null, null, Map.of());
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(4));
    }

    private CarrierShipmentRequest snapshot() {
        return new CarrierShipmentRequest(REQUEST_ID, UUID.fromString("25800000-0000-4000-8000-000000000102"),
                UUID.fromString("25800000-0000-4000-8000-000000000103"), "STANDARD", new BigDecimal("27500"),
                Map.of("recipient", Map.of("name", "Nguyễn Văn An", "phone", "0901234567", "address",
                                Map.of("line1", "12 Lê Lợi", "provinceCode", "1000001", "wardCode", "1003646")),
                        "pickupAddress", Map.of("line1", "20 Nguyễn Huệ", "provinceCode", "1000001", "wardCode", "1003646"),
                        "parcel", Map.of("weightGrams", 1200, "lengthCm", 30, "widthCm", 20, "heightCm", 15),
                        "items", List.of(Map.of("skuId", UUID.randomUUID().toString(), "productName", "Áo thun", "quantity", 2))));
    }

    private String created() {
        return "{\"code\":200,\"data\":{\"order_code\":\"GHN-WM-258\",\"expected_delivery_time\":\"2026-11-20T16:59:59Z\"}}";
    }

    private String existing(String note) {
        return "{\"code\":200,\"data\":{\"order_code\":\"GHN-WM-258\",\"client_order_code\":\"" + REQUEST_ID
                + "\",\"leadtime\":\"2026-11-20T16:59:59Z\"" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}}";
    }

    private String conflict() {
        return "{\"code\":400,\"code_message\":\"TO_ADDRESS_CONFLICT\",\"message\":\"Address conflict\",\"data\":null}";
    }
}
