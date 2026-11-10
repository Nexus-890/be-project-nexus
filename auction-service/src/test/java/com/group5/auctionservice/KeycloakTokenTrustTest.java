package com.group5.auctionservice;

import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.bidding.Bid;
import com.group5.auctionservice.bidding.BidRepository;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Loads the real service application and its classpath security auto-configuration.
 * Only unrelated database/Kafka infrastructure is isolated; JWT verification is real.
 */
@SpringBootTest(classes = AuctionServiceApplication.class, properties = {
        "spring.config.name=jwt-resource-server-test",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                + "com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.audience=nexus-services",
        "nexus.auction.default-bid-increment=27.50"
})
@Import(KeycloakTokenTrustTest.Probe.class)
class KeycloakTokenTrustTest {
    private static final String ISSUER = "http://localhost:18080/realms/nexus";
    private static final String SUBJECT = "68000000-0000-4000-8000-000000000002";
    private static final String AUCTIONS = "/api/v1/auctions";
    private static final RSAKey KEY = key();
    private static final RSAKey LEGACY_KEY = key();
    private static final HttpServer JWKS = jwks();
    @Autowired private WebApplicationContext context;
    @MockitoBean private AuctionRepository auctions;
    @MockitoBean private BidRepository bids;
    @MockitoBean private OutboxWriter outbox;
    @MockitoBean private Clock clock;
    @MockitoBean private PlatformTransactionManager transactionManager;
    private MockMvc client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
    }

    @BeforeEach
    void client() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-05T10:00:00Z"));
        client = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void stop() { JWKS.stop(0); }

    @Test
    void directProtectedRequestRequiresBearerEvenWithForgedIdentityHeaders() throws Exception {
        client.perform(get("/test/security").header("X-User-Id", SUBJECT).header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keycloakTokenPreservesFlatRolesPrivilegesAndSubject() throws Exception {
        client.perform(get("/test/security").header("Authorization", "Bearer " + token(KEY, ISSUER, List.of("nexus-services"))))
                .andExpect(status().isOk()).andExpect(content().string(SUBJECT));
    }

    @Test
    void wrongIssuerMissingOrWrongAudienceAndLegacySignatureAreRejected() throws Exception {
        for (String invalid : List.of(token(KEY, "user-service", List.of("nexus-services")),
                token(KEY, ISSUER, null), token(KEY, ISSUER, List.of("other-service")),
                token(LEGACY_KEY, ISSUER, List.of("nexus-services")))) {
            client.perform(get("/test/security").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void sellerCreatesAuctionWithConfiguredIncrementAndJwtSubject() throws Exception {
        when(auctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        client.perform(post(AUCTIONS).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("SELLER", List.of("AUCTION.CREATE")))
                        .content(auctionBody("2026-11-05T12:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.sellerId").value(SUBJECT))
                .andExpect(jsonPath("$.data.startingPrice").value(125.25))
                .andExpect(jsonPath("$.data.currentPrice").value(125.25))
                .andExpect(jsonPath("$.data.bidIncrement").value(27.5))
                .andExpect(jsonPath("$.data.originalEndAt").value("2026-11-05T12:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-05T10:00:00Z, AUCTION_INVALID_TIME_WINDOW, endAt must be after startAt",
            "2026-11-05T10:30:00Z, AUCTION_DURATION_TOO_SHORT, Auction duration must be at least 60 minutes",
            "2026-11-13T18:00:00Z, AUCTION_DURATION_TOO_LONG, Auction duration must be at most 168 hours"
    })
    void invalidDurationReturnsProblemAndDoesNotSave(String endAt, String code, String detail) throws Exception {
        client.perform(post(AUCTIONS).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("SELLER", List.of("AUCTION.CREATE")))
                        .content(auctionBody(endAt)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail));
        verifyNoInteractions(auctions);
    }

    @ParameterizedTest
    @ValueSource(longs = {60, 10080})
    void exactDurationBoundsRemainAccepted(long minutes) throws Exception {
        when(auctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String endAt = Instant.parse("2026-11-05T10:00:00Z").plusSeconds(minutes * 60).toString();

        client.perform(post(AUCTIONS).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("SELLER", List.of("AUCTION.CREATE")))
                        .content(auctionBody(endAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.endAt").value(endAt))
                .andExpect(jsonPath("$.data.originalEndAt").value(endAt));
    }

    @Test
    void createRequiresBothSellerRoleAndPrivilege() throws Exception {
        for (String token : List.of(auctionToken("BUYER", List.of()),
                auctionToken("ADMIN", List.of("AUCTION.CREATE")), auctionToken("SELLER", List.of()))) {
            client.perform(post(AUCTIONS).contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(auctionBody("2026-11-05T12:00:00Z")))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        verifyNoInteractions(auctions);
    }

    @Test
    void missingBidKeyReturnsAuctionProblemWithoutDatabaseAccess() throws Exception {
        client.perform(post(AUCTIONS + "/" + UUID.randomUUID() + "/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("BUYER", List.of("AUCTION.BID")))
                        .content("{\"amount\":1050000}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(auctions, bids, outbox);
    }

    @ParameterizedTest
    @CsvSource({"' ', AUCTION_IDEMPOTENCY_KEY_REQUIRED", "not-a-uuid, AUCTION_INVALID_IDEMPOTENCY_KEY",
            "1-1-1-1-1, AUCTION_INVALID_IDEMPOTENCY_KEY"})
    void invalidBidKeyReturnsAuctionProblemWithoutDatabaseAccess(String key, String code) throws Exception {
        client.perform(post(AUCTIONS + "/" + UUID.randomUUID() + "/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("BUYER", List.of("AUCTION.BID")))
                        .header("Idempotency-Key", key).content("{\"amount\":1050000}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
        verifyNoInteractions(auctions, bids, outbox);
    }

    @Test
    void firstBidReturns201AndReplayReturns200WithIdenticalEnvelope() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Bid accepted = mock(Bid.class);
        when(accepted.getId()).thenReturn(bidId);
        when(accepted.getAuctionId()).thenReturn(auctionId);
        when(accepted.getBidderId()).thenReturn(UUID.fromString(SUBJECT));
        when(accepted.getAmount()).thenReturn(new BigDecimal("1050000.00"));
        when(accepted.getPlacedAt()).thenReturn(Instant.parse("2026-11-05T10:00:00Z"));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(bids.findByIdempotencyKey(key)).thenReturn(Optional.empty(), Optional.of(accepted));
        when(auctions.acceptBid(auctionId, UUID.fromString(SUBJECT), new BigDecimal("1050000.00"))).thenReturn(1);
        when(bids.saveAndFlush(any())).thenReturn(accepted);
        String token = auctionToken("BUYER", List.of("AUCTION.BID"));

        String first = client.perform(post(AUCTIONS + "/" + auctionId + "/bids")
                        .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).content("{\"amount\":1050000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.bidId").value(bidId.toString()))
                .andReturn().getResponse().getContentAsString();
        String replay = client.perform(post(AUCTIONS + "/" + auctionId + "/bids")
                        .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).content("{\"amount\":1100000}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        verify(auctions, times(1)).acceptBid(any(), any(), any());
        verify(bids, times(1)).saveAndFlush(any());
        verify(outbox, times(1)).write(any(), any(), any());
    }

    @Test
    void bidRequiresBothBuyerRoleAndPrivilege() throws Exception {
        for (String token : List.of(auctionToken("SELLER", List.of("AUCTION.BID")),
                auctionToken("ADMIN", List.of("AUCTION.BID")), auctionToken("BUYER", List.of()))) {
            client.perform(post(AUCTIONS + "/" + UUID.randomUUID() + "/bids")
                            .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", UUID.randomUUID()).content("{\"amount\":1050000}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(auctions, bids, outbox);
    }

    @ParameterizedTest
    @CsvSource({"-86400, 400", "-6, 400", "-5, 201", "-3, 201", "0, 201", "60, 201"})
    void startTimeAllowsOnlyFiveSecondsOfClockSkew(long offsetSeconds, int expectedStatus) throws Exception {
        Instant start = Instant.parse("2026-11-05T10:00:00Z").plusSeconds(offsetSeconds);
        when(auctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = client.perform(post(AUCTIONS).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("SELLER", List.of("AUCTION.CREATE")))
                        .content(auctionBody(start.toString(), start.plusSeconds(7200).toString())))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 400) {
            result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUCTION_START_IN_PAST"));
            verifyNoInteractions(auctions);
        } else {
            result.andExpect(jsonPath("$.data.status").value("CREATED"))
                    .andExpect(jsonPath("$.data.startAt").value(start.toString()));
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 400", "-0.01, 400", "0.01, 201"})
    void startingPriceMustBeStrictlyPositive(String price, int expectedStatus) throws Exception {
        when(auctions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = client.perform(post(AUCTIONS).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + auctionToken("SELLER", List.of("AUCTION.CREATE")))
                        .content(auctionBody("2026-11-05T12:00:00Z").replace("125.25", price)))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 400) {
            result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("startingPrice"));
            verifyNoInteractions(auctions);
        } else {
            result.andExpect(jsonPath("$.data.startingPrice").value(0.01))
                    .andExpect(jsonPath("$.data.currentPrice").value(0.01));
        }
    }

    private static String auctionBody(String endAt) {
        return auctionBody("2026-11-05T10:00:00Z", endAt);
    }

    private static String auctionBody(String startAt, String endAt) {
        return """
                {
                  "productId": "00000002-1111-4111-8111-111111111111",
                  "skuId": "00000003-1111-4111-8111-111111111111",
                  "sellerId": "68000000-0000-4000-8000-000000000001",
                  "title": "Vintage film camera",
                  "startingPrice": 125.25,
                  "startAt": "%s",
                  "endAt": "%s"
                }
                """.formatted(startAt, endAt);
    }

    private static String auctionToken(String role, List<String> privileges) throws Exception {
        return token(KEY, ISSUER, List.of("nexus-services"), role, privileges);
    }

    private static String token(RSAKey key, String issuer, List<String> audience) throws Exception {
        return token(key, issuer, audience, "SELLER", List.of("PRODUCT.CREATE"));
    }

    private static String token(RSAKey key, String issuer, List<String> audience,
                                String role, List<String> privileges) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("realm-key").build(),
                new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(SUBJECT)
                        .claim("azp", "nexus-web").claim("roles", List.of(role))
                        .claim("privileges", privileges)
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("realm-key").generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static HttpServer jwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @RestController
    static class Probe {
        @GetMapping("/test/security")
        @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.CREATE')")
        public String protectedEndpoint(CurrentUser user) { return user.id().toString(); }
    }
}
