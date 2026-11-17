package com.group5.auctionservice.reputation;

import com.group5.common_core.exception.DomainException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteEligibilityClientTest {
    private static final UUID USER = UUID.fromString("68000000-0000-4000-8000-000000000003");
    private HttpServer server;
    private ExecutorService httpThreads;
    private ExecutorService breakerThreads;
    private String base;
    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicInteger decisions = new AtomicInteger();
    private final AtomicReference<Reply> tokenReply = new AtomicReference<>();
    private final AtomicReference<Reply> decisionReply = new AtomicReference<>();
    private final AtomicReference<String> tokenForm = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private CircuitBreakerRegistry registry;
    private MutableClock clock;
    private RatingMachineTokenProvider provider;
    private RemoteEligibilityClient client;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newVirtualThreadPerTaskExecutor();
        breakerThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(httpThreads);
        server.createContext("/token", exchange -> {
            tokens.incrementAndGet();
            tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, tokenReply.get());
        });
        server.createContext("/internal/v1/eligibility/", exchange -> {
            decisions.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, decisionReply.get());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        clock = new MutableClock();
        tokenReply.set(new Reply(200, token("machine-token", 300), 0));
        decisionReply.set(new Reply(200, valid(), 0));
        provider = new RatingMachineTokenProvider(base + "/token", "nexus-auction-service", "test-secret &+", 150, clock);
        rebuildClient();
    }

    private void rebuildClient() {
        var http = Feign.builder().contract(new SpringMvcContract())
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(100, TimeUnit.MILLISECONDS, 150, TimeUnit.MILLISECONDS, false))
                .target(RatingEligibilityHttpClient.class, base);
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(5).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30)).build());
        var timers = TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(2)).build());
        var factory = new Resilience4JCircuitBreakerFactory(registry, timers, null);
        factory.configureExecutorService(breakerThreads);
        client = new RemoteEligibilityClient(http, provider, factory);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        httpThreads.shutdownNow();
        breakerThreads.shutdownNow();
    }

    @Test
    void actualFeignSendsMachineTokenActionAndEncodedCredentialsAndCachesTheToken() {
        assertThat(client.check(USER, EligibilityAction.BID).eligible()).isTrue();
        assertThat(client.check(USER, EligibilityAction.BID).score()).isEqualTo(50);
        assertThat(tokens).hasValue(1);
        assertThat(decisions).hasValue(2);
        assertThat(authorization).hasValue("Bearer machine-token");
        assertThat(query).hasValue("action=BID");
        assertThat(tokenForm.get()).contains("grant_type=client_credentials", "client_id=nexus-auction-service",
                "client_secret=test-secret+%26%2B");
    }

    @Test
    void aValidDenyIsADecisionAndDoesNotCountAsAnUpstreamFailure() {
        decisionReply.set(new Reply(200, valid().replace("\"eligible\":true", "\"eligible\":false")
                .replace("\"score\":50", "\"score\":39"), 0));
        for (int call = 0; call < 6; call++) {
            assertThat(client.check(USER, EligibilityAction.BID).eligible()).isFalse();
        }
        assertThat(registry.circuitBreaker("auctionReputation").getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(decisions).hasValue(6);
    }

    @Test
    void malformedFieldsMissingEnvelopeAndInconsistentDecisionsFailClosed() {
        List<String> invalid = List.of("{}", "[]", "null", "{", valid().replace("\"code\":0,", ""),
                valid().replace("\"code\":0", "\"code\":\"0\""),
                valid().replace("\"code\":0", "\"code\":1"),
                valid().replace("\"message\":\"ok\",", ""),
                valid().replace("\"message\":\"ok\"", "\"message\":null"),
                valid().replace("\"eligible\":true", "\"eligible\":\"true\""),
                valid().replace("\"eligible\":true,", ""),
                valid().replace("\"score\":50", "\"score\":50.0"),
                valid().replace("\"score\":50", "\"score\":101"),
                valid().replace("\"score\":50", "\"score\":39"),
                valid().replace("\"eligible\":true", "\"eligible\":false"),
                valid().replace(USER.toString(), UUID.randomUUID().toString()),
                valid().replace(USER.toString(), "68000000-0-4000-8000-3"),
                valid().replace("\"action\":\"BID\"", "\"action\":\"SELL\""),
                valid().replace("\"trustLevel\":\"BASIC\"", "\"trustLevel\":\"UNKNOWN\""),
                valid().replace("\"minimumScore\":40", "\"minimumScore\":-1"),
                valid().replace("\"reason\":\"meets minimum\"", "\"reason\":\"\""),
                valid().replace("\"policyVersion\":\"v1\"", "\"policyVersion\":null"));
        for (String body : invalid) {
            registry.circuitBreaker("auctionReputation").reset();
            decisionReply.set(new Reply(200, body, 0));
            unavailable();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void upstreamErrorsFailClosedWithNoAutomaticRetry(int status) {
        decisionReply.set(new Reply(status, "{}", 0));
        unavailable();
        assertThat(decisions).hasValue(1);
        assertThat(tokens).hasValue(1);
    }

    @Test
    void actualFeignUnauthorizedInvalidatesCachedTokenForTheNextIndependentAttempt() {
        client.check(USER, EligibilityAction.BID);
        decisionReply.set(new Reply(401, "{}", 0));
        unavailable();
        assertThat(tokens).hasValue(1);
        tokenReply.set(new Reply(200, token("replacement-token", 300), 0));
        decisionReply.set(new Reply(200, valid(), 0));
        client.check(USER, EligibilityAction.BID);
        assertThat(tokens).hasValue(2);
        assertThat(authorization).hasValue("Bearer replacement-token");
    }

    @Test
    void ratingAndTokenTimeoutsAreBoundedAndHaveNoRetryOrAllowFallback() {
        decisionReply.set(new Reply(200, valid(), 600));
        long start = System.nanoTime();
        unavailable();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(decisions).hasValue(1);
        provider.invalidate("machine-token");
        tokenReply.set(new Reply(200, token("late-token", 300), 600));
        registry.circuitBreaker("auctionReputation").reset();
        unavailable();
        assertThat(tokens).hasValue(2);
        assertThat(decisions).hasValue(1);
    }

    @Test
    void aCircuitOpensAfterRepeatedFailuresAndShortCircuitsTokenAndRatingCalls() {
        decisionReply.set(new Reply(503, "{}", 0));
        for (int call = 0; call < 5; call++) unavailable();
        assertThat(registry.circuitBreaker("auctionReputation").getState().name()).isEqualTo("OPEN");
        provider.invalidate("machine-token");
        unavailable();
        assertThat(decisions).hasValue(5);
        assertThat(tokens).hasValue(1);
    }

    @Test
    void tokenExpiresAndMalformedOrRejectedTokenResponsesNeverReachRating() {
        client.check(USER, EligibilityAction.BID);
        clock.advance(Duration.ofSeconds(301));
        client.check(USER, EligibilityAction.BID);
        assertThat(tokens).hasValue(2);
        int previousDecisions = decisions.get();
        for (String body : List.of("{}", "null", "{\"access_token\":\"x\",\"expires_in\":300}",
                token("", 300), token("x", 0), token("x", 300).replace("300", "\"300\""),
                token("x", 300).replace("Bearer", "Basic"))) {
            provider.invalidate("machine-token");
            registry.circuitBreaker("auctionReputation").reset();
            tokenReply.set(new Reply(200, body, 0));
            unavailable();
        }
        tokenReply.set(new Reply(401, "{}", 0));
        registry.circuitBreaker("auctionReputation").reset();
        unavailable();
        assertThat(decisions).hasValue(previousDecisions);
    }

    @Test
    void missingCredentialsFailClosedWithoutAnyNetworkRequest() {
        provider = new RatingMachineTokenProvider(base + "/token", "nexus-auction-service", "", 150, clock);
        rebuildClient();
        unavailable();
        assertThat(tokens).hasValue(0);
        assertThat(decisions).hasValue(0);
    }

    private void unavailable() {
        assertThatThrownBy(() -> client.check(USER, EligibilityAction.BID)).isInstanceOfSatisfying(DomainException.class,
                failure -> {
                    assertThat(failure.getCode()).isEqualTo(503);
                    assertThat(failure.getErrorCode()).isEqualTo("AUCTION_REPUTATION_UNAVAILABLE");
                    assertThat(failure.getMessage()).doesNotContain("test-secret", "machine-token");
                });
    }

    private static String valid() {
        return "{\"code\":0,\"message\":\"ok\",\"data\":{\"userId\":\"" + USER
                + "\",\"action\":\"BID\",\"eligible\":true,\"score\":50,\"trustLevel\":\"BASIC\","
                + "\"minimumScore\":40,\"reason\":\"meets minimum\",\"policyVersion\":\"v1\"}}";
    }

    private static String token(String value, int expires) {
        return "{\"access_token\":\"" + value + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expires + "}";
    }

    private static void respond(HttpExchange exchange, Reply reply) throws IOException {
        try {
            if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private record Reply(int status, String body, int delayMillis) { }

    private static class MutableClock extends Clock {
        private Instant value = Instant.parse("2026-11-16T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return value; }
        synchronized void advance(Duration duration) { value = value.plus(duration); }
    }
}
