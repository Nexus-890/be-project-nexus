package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.client.InventoryReservationHttpClient;
import com.group5.commerceservice.order.client.OrderRemoteExecutor;
import com.group5.commerceservice.order.client.ShippingQuoteHttpClient;
import com.group5.commerceservice.order.client.config.OrderFeignConfiguration;
import com.group5.commerceservice.order.client.config.OrderHttpClient;
import com.group5.commerceservice.order.client.config.OrderResponseDecoder;
import com.group5.commerceservice.order.client.config.OrderRequestDeadline;
import com.group5.commerceservice.order.service.FeignInventoryClient;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gọi Feign qua HTTP thật, không Docker: kiểm payload, token, lỗi và deadline. */
class OrderReservationHttpTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID group = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID sku = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String bearer;
    private volatile String query;
    private volatile String reply;
    private volatile int status = 201;
    private volatile long delay;
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverThreads;
    private OrderHttpClient transport;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverThreads);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bearer = exchange.getRequestHeaders().getFirst("Authorization");
            query = exchange.getRequestURI().getRawQuery();
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", status < 300 ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        reply = success();
        transport = new OrderHttpClient(1800);
        var jwt = Jwt.withTokenValue("test-token").header("alg", "none").claim("sub", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void stop() {
        SecurityContextHolder.clearContext();
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
    }

    @Test
    void oneGroupRequestCarriesOrderOwnershipAndBearer() {
        var other = UUID.randomUUID();
        reply = success().replace("[\"", "[\"" + UUID.randomUUID() + "\",\"");
        var reservation = client(5).reserve(group, List.of(new InventoryClient.Item(order, sku, 2),
                new InventoryClient.Item(other, UUID.randomUUID(), 1)));
        assertThat(reservation.warehouseId()).isEqualTo(warehouse);
        assertThat(calls).hasValue(1);
        assertThat(bearer).isEqualTo("Bearer test-token");
        var body = mapper.readTree(bodies.getFirst());
        assertThat(body.path("orderGroupId").asString()).isEqualTo(group.toString());
        assertThat(body.path("items").size()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("orderId").asString()).isEqualTo(order.toString());
        assertThat(body.path("items").get(1).path("orderId").asString()).isEqualTo(other.toString());
    }

    @Test
    void insufficientStockPreservesOnlyVerifiedSkuAvailableAndRequested() {
        status = 409;
        reply = "{\"code\":\"INVENTORY_INSUFFICIENT_STOCK\",\"skuId\":\"" + sku
                + "\",\"available\":1,\"requested\":2,\"detail\":\"internal database detail\"}";
        assertThatThrownBy(() -> reserve(client(1))).isInstanceOfSatisfying(DomainException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo("COMMERCE_INSUFFICIENT_STOCK");
            assertThat(error.getProperties()).containsEntry("skuId", sku).containsEntry("available", 1)
                    .containsEntry("requested", 2);
            assertThat(error.getMessage()).doesNotContain("database");
        });
    }

    @Test
    void businessConflictDoesNotTripCircuit() {
        status = 409;
        reply = "{\"code\":\"INVENTORY_INSUFFICIENT_STOCK\",\"skuId\":\"" + sku + "\",\"available\":1}";
        var client = client(1);
        assertThatThrownBy(() -> reserve(client)).isInstanceOf(DomainException.class);
        reply = success();
        status = 201;
        assertThat(reserve(client).warehouseId()).isEqualTo(warehouse);
        assertThat(calls).hasValue(2);
    }

    @Test
    void availabilityFailureOpensCircuitAndNeverRetriesPost() {
        status = 503;
        reply = "{}";
        var client = client(1);
        assertUnavailable(client);
        reply = success();
        status = 201;
        assertUnavailable(client);
        assertThat(calls).hasValue(1);
    }

    @Test
    void timeoutReturns503WithinThreeSecondsWithOneHttpAttempt() {
        delay = 2500;
        long started = System.nanoTime();
        assertUnavailable(client(5));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2800);
        assertThat(calls).hasValue(1);
    }

    @Test
    void allSellerCallsShareOneDeadlineAndCleanupAllowsFollowingRequest() {
        // Khởi tạo kết nối trước khi đo; bài kiểm tra này đo ngân sách dùng chung, không đo JVM khởi động.
        assertThat(reserve(client(5)).warehouseId()).isEqualTo(warehouse);
        calls.set(0);
        long started = System.nanoTime();
        try (var ignored = OrderRequestDeadline.start(1000)) {
            delay = 200;
            assertThat(reserve(client(5)).warehouseId()).isEqualTo(warehouse);
            delay = 1000;
            assertUnavailable(client(5));
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1600);
        delay = 0;
        assertThat(reserve(client(5)).warehouseId()).isEqualTo(warehouse);
        assertThat(calls).hasValue(3);
    }

    @Test
    void unknownConflictOrMalformedInsufficiencyNeverPretendsToBeStockFailure() {
        status = 409;
        for (String body : List.of("{\"code\":\"INVENTORY_RESERVATION_CONFLICT\"}",
                "{\"code\":\"INVENTORY_INSUFFICIENT_STOCK\",\"skuId\":\"" + sku + "\",\"available\":-1}",
                "{\"code\":\"INVENTORY_INSUFFICIENT_STOCK\",\"skuId\":\"" + UUID.randomUUID() + "\",\"available\":0}")) {
            reply = body;
            assertUnavailable(client(5));
        }
    }

    @Test
    void eachOrderKeepsTheSellerWarehouseInventoryReturned() {
        var other = UUID.randomUUID();
        var otherWarehouse = UUID.randomUUID();
        String orders = ",\"orders\":[{\"orderId\":\"" + order + "\",\"warehouseId\":\"" + warehouse
                + "\"},{\"orderId\":\"" + other + "\",\"warehouseId\":\"" + otherWarehouse + "\"}]}}";
        reply = success().replace("[\"", "[\"" + UUID.randomUUID() + "\",\"").replaceFirst("}}$", orders);
        var items = List.of(new InventoryClient.Item(order, sku, 2), new InventoryClient.Item(other, UUID.randomUUID(), 1));
        var reservation = client(5).reserve(group, items);
        assertThat(reservation.warehouseFor(order)).isEqualTo(warehouse);
        assertThat(reservation.warehouseFor(other)).isEqualTo(otherWarehouse);

        reply = success().replaceFirst("}}$", ",\"orders\":[{\"orderId\":\"" + other + "\",\"warehouseId\":\""
                + otherWarehouse + "\"}]}}");
        assertUnavailable(client(5));
        reply = success().replaceFirst("}}$", ",\"orders\":[{\"orderId\":\"" + order + "\",\"warehouseId\":7}]}}");
        assertUnavailable(client(5));
    }

    @Test
    void invalidEnvelopeAndInvalidReservationNeverBecomeSuccess() {
        for (String body : List.of("{\"data\":{}}", success().replace("\"warehouseId\":\"" + warehouse + "\"", "\"warehouseId\":null"),
                success().replace(OffsetDateTime.now().plusMinutes(30).getYear() + "-", "2000-"),
                success().replace("\"code\":0", "\"code\":12"), success().replace("\"reservationIds\":[", "\"reservationIds\":[null,"))) {
            reply = body;
            assertUnavailable(client(5));
        }
    }

    @Test
    void shipmentDownIs503RatherThanFreeShipping() {
        status = 503;
        reply = "{\"code\":\"SHIPMENT_CARRIER_UNAVAILABLE\"}";
        var service = new OrderShippingService(feign(ShippingQuoteHttpClient.class), new OrderRemoteExecutor(5, 30000), mapper);
        var item = new com.group5.commerceservice.cart.dto.CatalogSkuResponse();
        item.setShippingParcel(new com.group5.commerceservice.cart.dto.CatalogSkuResponse.ShippingParcel(1, 1, 1, 1));
        assertThatThrownBy(() -> service.quote(new ShippingAddress("Buyer", "0901234567", "1 Main",
                "1003646", "1000001", "VN"), List.of(new OrderShippingService.Line(sku, 1, item))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_SHIPPING_UNAVAILABLE"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void sellerPickupIsReadWithTheBuyerTokenAndOnlyACompleteMatchingPickupIsAccepted() {
        UUID seller = UUID.randomUUID();
        List<UUID> skus = List.of(UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        status = 200;
        UUID sellerWarehouse = UUID.randomUUID();
        String valid = "{\"code\":0,\"message\":\"response success\",\"data\":{\"warehouseId\":\"" + sellerWarehouse
                + "\",\"sellerId\":\"" + seller + "\",\"provinceCode\":\"201\",\"wardCode\":\"20309\",\"line1\":\"12 Kim Mã\"}}";
        reply = valid;
        assertThat(client(5).pickup(seller, skus)).contains(new OrderShippingService.Pickup(sellerWarehouse, "201", "20309", "12 Kim Mã"));
        assertThat(bearer).isEqualTo("Bearer test-token");
        assertThat(query).isEqualTo("sellerId=" + seller + "&skuIds=" + skus.get(1) + "&skuIds=" + skus.get(0));
        reply = "{\"code\":0,\"message\":\"response success\",\"data\":null}";
        assertThat(client(5).pickup(seller, skus)).isEmpty();
        assertThat(client(5).pickup(null, skus)).isEmpty();
        assertThat(client(5).pickup(seller, List.of())).isEmpty();
        for (String body : List.of(valid.replace(seller.toString(), UUID.randomUUID().toString()),
                valid.replace("\"20309\"", "\"20A\""), valid.replace("\"12 Kim Mã\"", "\" \""),
                valid.replace("\"warehouseId\":\"" + sellerWarehouse + "\",", ""), valid.replace("\"code\":0", "\"code\":3"))) {
            reply = body;
            assertThatThrownBy(() -> client(5).pickup(seller, skus)).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_INVENTORY_UNAVAILABLE"));
        }
        status = 403;
        reply = "{\"code\":\"FORBIDDEN\"}";
        assertThatThrownBy(() -> client(5).pickup(seller, skus)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_FORBIDDEN"));
    }

    private FeignInventoryClient client(int threshold) {
        return new FeignInventoryClient(feign(InventoryReservationHttpClient.class), new OrderRemoteExecutor(threshold, 30000), mapper);
    }

    @Test
    void auctionLinkedReservationArrayUsesItsOwnDtoAndRelaysTheBuyer() {
        status=200;UUID reservation=UUID.randomUUID();
        reply="{\"code\":0,\"message\":\"response success\",\"data\":[{\"reservationId\":\""+reservation+
                "\",\"warehouseId\":\""+warehouse+"\",\"status\":\"HELD\"}],\"page\":{\"number\":0,\"size\":1,\"totalElements\":1,\"totalPages\":1}}";
        var result=feign(InventoryReservationHttpClient.class).linked(order);
        assertThat(result.data()).containsExactly(new InventoryReservationHttpClient.LinkedReservation(reservation,warehouse,"HELD"));
        assertThat(bearer).isEqualTo("Bearer test-token");
        reply="{\"code\":0,\"message\":\"response success\",\"data\":[]}";
        assertThat(feign(InventoryReservationHttpClient.class).linked(order).data()).isEmpty();
        reply="{\"code\":0,\"message\":\"response success\",\"data\":[{\"fee\":1,\"carrier\":\"GHN\",\"serviceType\":\"STANDARD\",\"estimatedDeliveryAt\":\"2031-01-01T10:00:00Z\"}]}";
        assertThatThrownBy(()->feign(InventoryReservationHttpClient.class).linked(order)).isInstanceOf(feign.codec.DecodeException.class);
    }

    @Test
    void catalogCostReadUsesCachedMachineTokenAndValidatesPrivatePayload() throws Exception {
        var tokenCalls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            byte[] json = "{\"access_token\":\"machine-token\",\"token_type\":\"Bearer\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,json.length); exchange.getResponseBody().write(json); exchange.close();
        });
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var tokens = new OrderFeignConfiguration.CatalogCostTokenProvider("http://127.0.0.1:"+server.getAddress().getPort()+"/token", "nexus-commerce-service", "test-machine-secret", 1000, beans.getBeanProvider(java.time.Clock.class));
        var http = Feign.builder().contract(new SpringMvcContract()).client(transport)
                .encoder((body,type,template)->template.body(mapper.writeValueAsString(body)))
                .decoder(new OrderResponseDecoder(mapper)).retryer(Retryer.NEVER_RETRY)
                .requestInterceptor(new OrderFeignConfiguration().orderBearerRelay(tokens))
                .target(com.group5.commerceservice.cart.dto.CatalogClient.class,"http://127.0.0.1:"+server.getAddress().getPort());
        var product=UUID.randomUUID(); var seller=UUID.randomUUID();
        status=200;
        reply="{\"code\":0,\"message\":\"response success\",\"data\":[{\"skuId\":\""+sku+"\",\"productId\":\""+product+"\",\"sellerId\":\""+seller+"\",\"unitCostPrice\":0}]}";
        var request=new com.group5.commerceservice.cart.dto.CatalogClient.CostRequest(List.of(sku));
        assertThat(http.getCosts(request).data().getFirst().unitCostPrice()).isZero();
        assertThat(bearer).isEqualTo("Bearer machine-token");
        reply=reply.replace("\"unitCostPrice\":0","\"unitCostPrice\":null");
        assertThat(http.getCosts(request).data().getFirst().unitCostPrice()).isNull();
        assertThat(tokenCalls).hasValue(1);
        reply=reply.replace(",\"unitCostPrice\":null","");
        assertThatThrownBy(()->http.getCosts(request)).isInstanceOf(feign.codec.DecodeException.class);
        reply="{\"code\":0,\"message\":\"response success\",\"data\":{\"id\":\""+sku+"\",\"price\":100}}";
        assertThat(http.getSkuById(sku).data().getId()).isEqualTo(sku);
        assertThat(bearer).isEqualTo("Bearer test-token");
        delay=1500;
        tokens = new OrderFeignConfiguration.CatalogCostTokenProvider("http://127.0.0.1:"+server.getAddress().getPort()+"/slow-token", "nexus-commerce-service", "test-machine-secret", 1000, beans.getBeanProvider(java.time.Clock.class));
        try(var budget=OrderRequestDeadline.start(50)) {
            assertThatThrownBy(tokens::accessToken).isInstanceOf(DomainException.class);
        }
    }

    private <T> T feign(Class<T> type) {
        return Feign.builder().contract(new SpringMvcContract()).client(transport)
                .encoder((body, bodyType, template) -> template.body(mapper.writeValueAsString(body)))
                .decoder(new OrderResponseDecoder(mapper)).retryer(Retryer.NEVER_RETRY)
                .requestInterceptor(new OrderFeignConfiguration().orderBearerRelay(org.mockito.Mockito.mock(OrderFeignConfiguration.CatalogCostTokenProvider.class)))
                .options(new Request.Options(1800, TimeUnit.MILLISECONDS, 1800, TimeUnit.MILLISECONDS, false))
                .target(type, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private InventoryClient.Reservation reserve(FeignInventoryClient client) {
        return client.reserve(group, List.of(new InventoryClient.Item(order, sku, 2)));
    }

    private void assertUnavailable(FeignInventoryClient client) {
        assertThatThrownBy(() -> reserve(client)).isInstanceOfSatisfying(DomainException.class, error -> {
            assertThat(error.getCode()).isEqualTo(503);
            assertThat(error.getErrorCode()).isEqualTo("COMMERCE_INVENTORY_UNAVAILABLE");
        });
    }

    private String success() {
        return "{\"code\":0,\"message\":\"response success\",\"data\":{\"reservationIds\":[\"" + UUID.randomUUID()
                + "\"],\"warehouseId\":\"" + warehouse + "\",\"expiresAt\":\"" + OffsetDateTime.now().plusMinutes(30) + "\"}}";
    }
}
