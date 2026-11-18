package com.group5.acceptance;

import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties.CarrierMode;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.ghn.GhnAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Commerce producer and Shipment consumer/worker; only GHN's network is simulated. */
class PaidOrderShipmentAcceptanceTest {
    @Test
    void actualPaymentOutboxWithoutServiceOrPickupBooksGhnWithinOneWorkerCycle() throws Exception {
        try (var commerce = new CommercePaidShipmentFixture(); var ghn = new GhnStub();
             var shipment = new ShipmentPaidFixture(CarrierMode.GHN, List.of(ghn.adapter()))) {
            var event = commerce.pay(ShipmentPaidFixture.WAREHOUSE, "12 Recipient Street");
            ghn.failFirstLookup.set(true);
            shipment.receive(event);
            assertThat(shipment.status()).isEqualTo("PENDING_TRACKING");
            assertThat(shipment.tracking()).isNull();
            assertThat(ghn.creates).isEmpty();

            shipment.runDueWorker();
            shipment.receive(event);
            shipment.runDueWorker();

            assertThat(shipment.status()).isEqualTo("CREATED");
            assertThat(shipment.tracking()).isEqualTo("GHN258ACCEPT");
            assertThat(shipment.count("shipments")).isEqualTo(1);
            assertThat(shipment.count("outbox")).isEqualTo(1);
            assertThat(ghn.creates).singleElement().satisfies(body -> {
                assertThat(body.path("client_order_code").asString()).isEqualTo(shipment.id().toString());
                assertThat(body.path("from_address").asString()).isEqualTo("20 Sample Warehouse Street, Phường Sài Gòn, Hồ Chí Minh");
                assertThat(body.path("to_address").asString()).isEqualTo("12 Recipient Street, Phường Sài Gòn, Hồ Chí Minh");
                assertThat(body.path("service_type_id").asInt()).isEqualTo(2);
                assertThat(body.path("weight").asInt()).isEqualTo(500);
                assertThat(body.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
            });
            assertThat(shipment.snapshot().path("pickupAddress").path("line1").asString()).isEqualTo("20 Sample Warehouse Street");
            assertThat(shipment.jdbc.queryForObject("SELECT estimated_delivery_at FROM shipments", OffsetDateTime.class))
                    .isEqualTo(OffsetDateTime.parse("2026-11-20T10:00:00Z"));
            assertThat(shipment.jdbc.queryForObject("SELECT shipping_fee FROM shipments", java.math.BigDecimal.class))
                    .isEqualByComparingTo("45678.00");
        }
    }

    @Test
    void realPaymentWithGhnQuoteKeepsItsFeeWhenBookingFakeOrNone() throws Exception {
        for (var mode : List.of(CarrierMode.FAKE, CarrierMode.NONE)) {
            try (var commerce = new CommercePaidShipmentFixture(); var ghn = new GhnStub();
                 var shipment = new ShipmentPaidFixture(mode, List.of(ghn.adapter(), new FakeCarrierAdapter()))) {
                var event = commerce.pay(ShipmentPaidFixture.WAREHOUSE, "12 Recipient Street");
                shipment.receive(event);
                shipment.receive(event);
                shipment.runDueWorker();

                assertThat(shipment.jdbc.queryForObject("SELECT shipping_fee FROM shipments", java.math.BigDecimal.class))
                        .isEqualByComparingTo("45678.00");
                assertThat(shipment.jdbc.queryForObject("SELECT carrier FROM shipments", String.class)).isEqualTo(mode.name());
                assertThat(shipment.count("shipments")).isEqualTo(1);
                assertThat(ghn.requests).as("GHN quote snapshot must not trigger GHN booking in this mode").isEmpty();
                if (mode == CarrierMode.FAKE) {
                    assertThat(shipment.tracking()).isEqualTo("FAKE-" + shipment.id());
                    assertThat(shipment.status()).isEqualTo("CREATED");
                } else {
                    assertThat(shipment.tracking()).isNull();
                    assertThat(shipment.jdbc.queryForObject("SELECT next_tracking_retry_at FROM shipments", Object.class)).isNull();
                }
            }
        }
    }

    @Test
    void fallbackThenCreateTimeoutRecoversOneRemoteOrderAndOnePersistedFallbackHistory() throws Exception {
        try (var commerce = new CommercePaidShipmentFixture(); var ghn = new GhnStub();
             var shipment = new ShipmentPaidFixture(CarrierMode.GHN, List.of(ghn.adapter()))) {
            var event = commerce.pay(ShipmentPaidFixture.WAREHOUSE, "12 Another Ward Street");
            ghn.addressConflict.set(true);
            ghn.timeoutSuccessfulCreate.set(true);
            shipment.receive(event);
            assertThat(shipment.status()).isEqualTo("PENDING_TRACKING");
            assertThat(ghn.remoteOrders).hasSize(1);
            assertThat(ghn.creates).hasSize(2);
            assertThat(ghn.creates.get(0).path("to_address").asString()).startsWith("12 Another Ward Street,");
            assertThat(ghn.creates.get(1).path("to_address").asString()).isEqualTo("Phường Sài Gòn, Hồ Chí Minh");

            shipment.runDueWorker();
            shipment.receive(event);
            shipment.runDueWorker();

            assertThat(shipment.tracking()).isEqualTo("GHN258ACCEPT");
            assertThat(ghn.remoteOrders).hasSize(1);
            assertThat(ghn.creates).hasSize(2);
            assertThat(shipment.jdbc.queryForObject("""
                    SELECT count(*) FROM shipment_history
                    WHERE description='GHN address fallback: ward and province used after TO_ADDRESS_CONFLICT'
                    """, Long.class)).isEqualTo(1);
            assertThat(shipment.jdbc.queryForObject("SELECT service_type FROM shipments", String.class)).isEqualTo("STANDARD");
            assertThat(shipment.snapshot().path("recipient").path("address").path("line1").asString())
                    .isEqualTo("12 Another Ward Street");
        }
    }

    private static final class GhnStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final JsonMapper mapper = JsonMapper.shared();
        final AtomicBoolean failFirstLookup = new AtomicBoolean();
        final AtomicBoolean addressConflict = new AtomicBoolean();
        final AtomicBoolean timeoutSuccessfulCreate = new AtomicBoolean();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<JsonNode> creates = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, Object>> remoteOrders = new ConcurrentHashMap<>();

        GhnStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor); server.createContext("/", this::handle); server.start();
        }

        GhnAdapter adapter() {
            return new GhnAdapter(new GhnProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    258, 1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, 750, 300000),
                    ShipmentPaidFixture.properties(CarrierMode.GHN), new MockEnvironment().withProperty("GHN_TOKEN", "public-test-token"), mapper);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            try {
                if (path.endsWith("/province/all")) {
                    success(exchange, List.of(Map.of("_id", 1000001, "name", "Hồ Chí Minh", "type", "province", "parent_id", 1, "status", 1)));
                } else if (path.endsWith("/ward/all-by-province-id")) {
                    success(exchange, List.of(Map.of("_id", 1000002, "name", "Phường Sài Gòn", "type", "ward", "parent_id", 1000001, "status", 1)));
                } else if (path.endsWith("/detail-by-client-code")) {
                    if (failFirstLookup.getAndSet(false)) {
                        reply(exchange, 503, Map.of("code", 503, "message", "Temporary carrier outage"));
                        return;
                    }
                    String code = exchange.getRequestURI().getQuery().substring("client_order_code=".length());
                    var found = remoteOrders.get(code);
                    if (found == null) reply(exchange, 404, Map.of("code", 404, "code_message", "ORDER_NOT_FOUND", "message", "Order not found"));
                    else success(exchange, found);
                } else if (path.endsWith("/create")) {
                    JsonNode body = mapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    creates.add(body);
                    if (addressConflict.getAndSet(false)) {
                        reply(exchange, 400, Map.of("code", 400, "code_message", "TO_ADDRESS_CONFLICT", "message", "Address conflict"));
                        return;
                    }
                    var order = new LinkedHashMap<String, Object>();
                    order.put("client_order_code", body.path("client_order_code").asString());
                    order.put("order_code", "GHN258ACCEPT");
                    order.put("expected_delivery_time", "2026-11-20T10:00:00Z");
                    order.put("note", body.path("note").asString(""));
                    remoteOrders.put(body.path("client_order_code").asString(), order);
                    if (timeoutSuccessfulCreate.getAndSet(false)) Thread.sleep(1200);
                    success(exchange, order);
                } else reply(exchange, 404, Map.of("code", 404, "message", "Unexpected test route"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnectedAfterTimeout) {
                // The timed-out POST may disconnect after the provider has persisted the order.
            } finally { exchange.close(); }
        }

        private void success(HttpExchange exchange, Object data) throws IOException {
            reply(exchange, 200, Map.of("code", 200, "data", data));
        }

        private void reply(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] encoded = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, encoded.length); exchange.getResponseBody().write(encoded);
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
