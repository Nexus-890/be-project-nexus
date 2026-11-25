package com.group5.inventoryservice.shared.shipment;

import com.group5.common_core.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentPickupAddressesTest {
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String reply = "{\"code\":0,\"data\":{\"carrier\":\"GHN\"}}";
    private HttpServer server;
    private ShipmentPickupAddresses addresses;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/shipping/pickup-address-checks", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            byte[] bytes = reply.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, content, execution) ->
                execution.execute(new HttpRequestWrapper(request) {
                    @Override
                    public URI getURI() {
                        return UriComponentsBuilder.fromUri(request.getURI()).host("127.0.0.1").port(port)
                                .build(true).toUri();
                    }
                }, content));
        addresses = new ShipmentPickupAddresses(builder);
        MockHttpServletRequest caller = new MockHttpServletRequest();
        caller.addHeader("Authorization", "Bearer seller-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(caller));
    }

    @AfterEach
    void stop() {
        RequestContextHolder.resetRequestAttributes();
        server.stop(0);
    }

    @Test
    void anAcceptedPickupForwardsTheSellerTokenAndTheExactLine() {
        addresses.verify("1000002", "1003445", "1 Trang Tien");

        assertThat(authorization.get()).isEqualTo("Bearer seller-token");
        assertThat(body.get()).contains("\"provinceCode\":\"1000002\"", "\"wardCode\":\"1003445\"",
                "\"line1\":\"1 Trang Tien\"");
    }

    @ParameterizedTest
    @CsvSource({
            "400, SHIPMENT_PICKUP_ADDRESS_CONFLICT, INVENTORY_INVALID_PICKUP_ADDRESS, house number and street",
            "400, SHIPMENT_INVALID_ADDRESS, INVENTORY_INVALID_PICKUP_ADDRESS, does not belong",
            "400, SHIPMENT_VALIDATION_FAILED, INVENTORY_INVALID_PICKUP_ADDRESS, does not belong",
            "422, SHIPMENT_NO_SERVICE, INVENTORY_INVALID_PICKUP_ADDRESS, does not collect",
            "403, SHIPMENT_FORBIDDEN, INVENTORY_PICKUP_CHECK_UNAVAILABLE, nothing was saved",
            "429, SHIPMENT_RATE_LIMITED, INVENTORY_PICKUP_CHECK_UNAVAILABLE, nothing was saved",
            "503, SHIPMENT_CARRIER_UNAVAILABLE, INVENTORY_PICKUP_CHECK_UNAVAILABLE, nothing was saved"
    })
    void carrierAnswersBecomeInventoryErrors(int answer, String shipmentCode, String inventoryCode, String detail) {
        status = answer;
        reply = "{\"status\":" + answer + ",\"code\":\"" + shipmentCode + "\"}";

        assertThatThrownBy(() -> addresses.verify("1000002", "1003445", "25 Hang Bai"))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(inventoryCode);
                    assertThat(error.getMessage()).contains(detail);
                });
    }

    @Test
    void anUnreadableRejectionIsTreatedAsUnavailable() {
        status = 400;
        reply = "not json";

        assertThatThrownBy(() -> addresses.verify("1000002", "1003445", "1 Trang Tien"))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("INVENTORY_PICKUP_CHECK_UNAVAILABLE"));
    }

    @Test
    void withoutACallerTokenNothingIsSent() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> addresses.verify("1000002", "1003445", "1 Trang Tien"))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("INVENTORY_PICKUP_CHECK_UNAVAILABLE"));
        assertThat(calls).hasValue(0);
    }
}
