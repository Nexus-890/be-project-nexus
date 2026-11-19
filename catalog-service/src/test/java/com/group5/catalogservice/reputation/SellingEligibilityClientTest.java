package com.group5.catalogservice.reputation;

import com.group5.common_core.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellingEligibilityClientTest {
    private final UUID user = UUID.randomUUID();
    private final RatingMachineTokenProvider tokens = mock(RatingMachineTokenProvider.class);
    private HttpServer server;
    private ExecutorService threads;
    private SellingEligibilityClient client;
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private int status = 200;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(threads);
        server.createContext("/internal/v1/eligibility/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("action=SELL");
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        when(tokens.accessToken()).thenReturn("catalog-machine-token");
        reply.set(allowed());
        client = new SellingEligibilityClient(tokens, true, "http://127.0.0.1:" + server.getAddress().getPort(), 1000, 2000);
    }

    @AfterEach
    void stop() { server.stop(0); threads.shutdownNow(); }

    @Test
    void machineAuthenticatedSellDecisionAllowsPublishing() {
        client.requireEligible(user);
        assertThat(authorization).hasValue("Bearer catalog-machine-token");
    }

    @Test
    void restrictionDeniesHighScoreAndReportsExpiry() {
        reply.set(allowed().replace("\"eligible\":true", "\"eligible\":false")
                .replace("\"policyVersion\":\"v1\"", "\"policyVersion\":\"v1\",\"restrictedUntil\":\"2099-01-01T00:00:00Z\""));
        assertThatThrownBy(() -> client.requireEligible(user)).isInstanceOfSatisfying(DomainException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo("CATALOG_REPUTATION_RESTRICTED");
            assertThat(failure.getCode()).isEqualTo(403);
            assertThat(failure.getProperties()).containsKey("restrictedUntil").containsEntry("action", "SELL");
        });
    }

    @Test
    void lowScoreDeniedAndMalformedOrUnavailableDecisionsFailClosed() {
        reply.set(allowed().replace("\"eligible\":true", "\"eligible\":false").replace("\"score\":90", "\"score\":20"));
        assertThatThrownBy(() -> client.requireEligible(user)).isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("CATALOG_REPUTATION_TOO_LOW");
        reply.set(allowed().replace("\"eligible\":true", "\"eligible\":false"));
        assertThatThrownBy(() -> client.requireEligible(user)).isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("CATALOG_REPUTATION_UNAVAILABLE");
        status = 401;
        assertThatThrownBy(() -> client.requireEligible(user)).isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("CATALOG_REPUTATION_UNAVAILABLE");
        verify(tokens).invalidate("catalog-machine-token");
    }

    @Test
    void disablingEnforcementSkipsMachineCalls() {
        clearInvocations(tokens);
        new SellingEligibilityClient(tokens, false, "http://127.0.0.1", 1000, 2000).requireEligible(user);
        verifyNoInteractions(tokens);
    }

    private String allowed() {
        return "{\"code\":0,\"message\":\"ok\",\"data\":{\"userId\":\"" + user
                + "\",\"action\":\"SELL\",\"eligible\":true,\"score\":90,\"minimumScore\":50,\"trustLevel\":\"TOP\","
                + "\"reason\":\"Allowed\",\"policyVersion\":\"v1\"}}";
    }
}
