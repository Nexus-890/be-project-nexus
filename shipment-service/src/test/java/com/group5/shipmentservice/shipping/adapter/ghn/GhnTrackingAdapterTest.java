package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.adapter.model.CarrierTrackingUpdate;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GhnTrackingAdapterTest {
    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private Function<Request, String> response;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        response = request -> "{}";
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"ready_to_pick,CREATED", "picking,PICKING", "money_collect_picking,PICKING", "picked,SHIPPED",
            "storing,IN_TRANSIT", "sorting,IN_TRANSIT", "transporting,IN_TRANSIT", "delivering,OUT_FOR_DELIVERY",
            "money_collect_delivering,OUT_FOR_DELIVERY", "delivered,DELIVERED", "delivery_fail,DELIVERY_FAILED",
            "waiting_to_return,RETURNING", "return,RETURNING", "return_transporting,RETURNING", "return_sorting,RETURNING",
            "returning,RETURNING", "return_fail,RETURNING", "returned,RETURNED", "cancel,CANCELLED",
            "exception,DELIVERY_FAILED", "lost,DELIVERY_FAILED", "damage,DELIVERY_FAILED", "scrap,DELIVERY_FAILED"})
    void mapsEveryPublishedGhnStatusToOneInternalStatus(String external, ShipmentStatus internal) {
        assertThat(GhnStatusMapper.map(external)).isEqualTo(internal);
    }

    @Test
    void pollingValidatesOrderIdentityAndPreservesStableProviderTime() {
        response = request -> detail("LADFYR", "transporting");
        GhnAdapter adapter = adapter("callback-secret");

        CarrierTrackingUpdate first = adapter.track("LADFYR", deadline());
        CarrierTrackingUpdate second = adapter.track("LADFYR", deadline());

        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(first.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-11-11T01:00:00Z"));
        assertThat(first.carrierEventId()).startsWith("GHN:").hasSizeLessThanOrEqualTo(160);
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/shiip/public-api/v2/shipping-order/detail?order_code=LADFYR");
            assertThat(request.token()).isEqualTo("api-token");
            assertThat(request.shopId()).isEqualTo("12345");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"order_code\":\"WRONG\",\"status\":\"picked\",\"updated_date\":\"2026-11-11T01:00:00Z\"}",
            "{\"order_code\":\"LADFYR\",\"status\":\"picked\",\"updated_date\":\"bad-date\"}", "[]"})
    void rejectsInvalidTrackingResponse(String data) {
        response = request -> data;
        assertCode(() -> adapter("secret").track("LADFYR", deadline()), "SHIPMENT_INVALID_CARRIER_RESPONSE");
    }

    @Test
    void unknownStatusIsExplicitAndDoesNotInventAnInternalValue() {
        response = request -> detail("LADFYR", "new-provider-state");
        assertCode(() -> adapter("secret").track("LADFYR", deadline()), "SHIPMENT_INVALID_CARRIER_STATUS");
    }

    @Test
    void cancellationChecksPerOrderResultAndSendsOnlyTheRequestedOrder() {
        response = request -> "[{\"order_code\":\"LADFYR\",\"result\":true,\"message\":\"OK\"}]";
        adapter("secret").cancelShipment("LADFYR", UUID.randomUUID(), deadline());

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/shiip/public-api/v2/switch-status/cancel");
            var body = new ObjectMapper().readTree(request.body());
            assertThat(body.path("order_codes").size()).isEqualTo(1);
            assertThat(body.path("order_codes").get(0).asString()).isEqualTo("LADFYR");
            assertThat(body.path("reason_code").asString()).isEqualTo("GHN-CANCEL-OTHER");
        });
    }

    @Test
    void replayAfterCarrierAlreadyCancelledReconcilesToSuccess() {
        response = request -> request.method().equals("POST")
                ? "[{\"order_code\":\"LADFYR\",\"result\":false}]" : detail("LADFYR", "cancel");
        adapter("secret").cancelShipment("LADFYR", UUID.randomUUID(), deadline());
        assertThat(requests).hasSize(2);
    }

    @Test
    void rejectedCancellationDoesNotPretendTheCarrierCancelled() {
        response = request -> request.method().equals("POST")
                ? "[{\"order_code\":\"LADFYR\",\"result\":false}]" : detail("LADFYR", "picked");
        assertCode(() -> adapter("secret").cancelShipment("LADFYR", UUID.randomUUID(), deadline()),
                "SHIPMENT_CARRIER_CANCELLATION_REJECTED");
    }

    @Test
    void signatureVerificationUsesConfiguredCustomHeaderAndFailsClosed() {
        GhnAdapter configured = adapter("callback-secret");
        configured.verifyWebhook(body(), "callback-secret");
        assertCode(() -> configured.verifyWebhook(body(), "wrong"), "SHIPMENT_INVALID_SIGNATURE");
        assertCode(() -> configured.verifyWebhook(body(), null), "SHIPMENT_INVALID_SIGNATURE");
        assertCode(() -> adapter(null).verifyWebhook(body(), "callback-secret"), "SHIPMENT_INVALID_SIGNATURE");
        assertThat(requests).isEmpty();
    }

    @Test
    void webhookIdentityIgnoresJsonFormattingAndSanitizesAuditDescription() {
        GhnAdapter configured = adapter("callback-secret");
        CarrierTrackingUpdate first = configured.parseWebhook(body());
        CarrierTrackingUpdate second = configured.parseWebhook(new String(body(), StandardCharsets.UTF_8)
                .replace("{", "{\n ").getBytes(StandardCharsets.UTF_8));
        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(first.description()).isEqualTo("Picked up");
    }

    @Test
    void differentStatusesAtTheSameProviderTimeHaveDifferentIdentities() {
        GhnAdapter configured = adapter("callback-secret");
        CarrierTrackingUpdate first = configured.parseWebhook(body());
        byte[] later = new String(body(), StandardCharsets.UTF_8).replace("\"picked\"", "\"transporting\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(configured.parseWebhook(later).carrierEventId()).isNotEqualTo(first.carrierEventId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "bad-json",
            "{\"ShopID\":54321,\"OrderCode\":\"LADFYR\",\"Type\":\"switch_status\",\"Status\":\"picked\",\"Time\":\"2026-11-11T01:00:00Z\"}"})
    void invalidWebhookCannotUseAnotherShopOrMalformedPayload(String json) {
        assertCode(() -> adapter("secret").parseWebhook(json.getBytes(StandardCharsets.UTF_8)), "SHIPMENT_INVALID_WEBHOOK");
    }

    private byte[] body() {
        return ("{\"ShopID\":12345,\"OrderCode\":\"LADFYR\",\"Type\":\"switch_status\","
                + "\"Status\":\"picked\",\"Time\":\"2026-11-11T01:00:00Z\",\"Description\":\"Picked up\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private GhnAdapter adapter(String secret) {
        var environment = new MockEnvironment().withProperty("GHN_TOKEN", "api-token");
        if (secret != null) environment.withProperty("GHN_WEBHOOK_SECRET", secret);
        return new GhnAdapter(new GhnProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                12345, 1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, 1500, 0),
                new ShippingProperties(ShippingProperties.CarrierMode.GHN, 3000, 0, 10,
                        BigDecimal.valueOf(1_000_000), 5, 30000, "http://catalog-service", null, null, Map.of()),
                environment, new ObjectMapper());
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(3));
    }

    private String detail(String code, String status) {
        return "{\"order_code\":\"" + code + "\",\"status\":\"" + status
                + "\",\"updated_date\":\"2026-11-11T01:00:00Z\"}";
    }

    private void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo(code);
    }

    private void handle(HttpExchange exchange) throws IOException {
        var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Token"), exchange.getRequestHeaders().getFirst("ShopId"));
        requests.add(request);
        byte[] bytes = ("{\"code\":200,\"data\":" + response.apply(request) + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Request(String method, String path, String body, String token, String shopId) {
    }
}
