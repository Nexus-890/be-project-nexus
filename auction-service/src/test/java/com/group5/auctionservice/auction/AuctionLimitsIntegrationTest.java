package com.group5.auctionservice.auction;

import com.group5.auctionservice.TestPostgres;
import com.group5.auctionservice.reputation.EligibilityAction;
import com.group5.auctionservice.reputation.EligibilityClient;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.model.ApiResponse;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-limits-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "nexus.auction.settlement.enabled=false",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs"
})
class AuctionLimitsIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    static { POSTGRES.start(); }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private AuctionProperties properties;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private AuctionCatalog catalog;
    @MockitoBean private AuctionInventoryClient inventory;
    @MockitoBean private EligibilityClient eligibility;
    private final Map<UUID, UUID> productIdsBySku = new ConcurrentHashMap<>();
    private final Map<UUID, CatalogClient.Product> products = new ConcurrentHashMap<>();
    private MockMvc client;
    private UUID seller;
    private UUID otherSeller;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        properties.setReputationEnforcementEnabled(false);
        client = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        seller = UUID.randomUUID();
        otherSeller = UUID.randomUUID();
        when(decoder.decode("seller")).thenReturn(token("seller", seller));
        when(decoder.decode("other")).thenReturn(token("other", otherSeller));
        when(catalog.requireProduct(any())).thenAnswer(call -> products.get(call.getArgument(0)));
        when(inventory.available(any())).thenAnswer(call ->
                ApiResponse.of(new AuctionInventoryClient.Stock(call.getArgument(0), 5)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "SCHEDULED", "ACTIVE"})
    void secondCreateForLiveSkuReturns409AndLeavesOneRow(String liveStatus) throws Exception {
        UUID sku = UUID.randomUUID();
        if (liveStatus.equals("CREATED")) {
            create("seller", sku).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CREATED"));
        } else {
            seed(seller, sku, liveStatus, Instant.now().plusSeconds(3600), false);
        }

        conflict(create("seller", sku), "AUCTION_SKU_ALREADY_LIVE");
        assertThat(countForSku(sku)).isEqualTo(1);
    }

    @Test
    void concurrentCreatesForTheSameSkuLeaveOneRow() throws Exception {
        UUID sku = UUID.randomUUID();
        race(() -> create("seller", sku), () -> create("seller", sku), "AUCTION_SKU_ALREADY_LIVE");
        assertThat(countForSku(sku)).isEqualTo(1);
    }

    @Test
    void sixthLiveAuctionReturns409AndDoesNotAffectOtherSellers() throws Exception {
        Instant end = Instant.now().plusSeconds(3600);
        seed(seller, UUID.randomUUID(), "ENDED", end, false);
        UUID deletedSku = UUID.randomUUID();
        seed(seller, deletedSku, "CREATED", end, true);
        seed(seller, UUID.randomUUID(), "SCHEDULED", end, false);
        seed(seller, UUID.randomUUID(), "ACTIVE", end, false);
        create("seller", deletedSku).andExpect(status().isCreated());
        create("seller", UUID.randomUUID()).andExpect(status().isCreated());
        create("seller", UUID.randomUUID()).andExpect(status().isCreated());

        UUID nextSku = UUID.randomUUID();
        conflict(create("seller", nextSku), "AUCTION_SELLER_LIMIT_REACHED")
                .andExpect(jsonPath("$.detail").value("The seller has reached the live auction limit of 5"));
        assertThat(countForSku(nextSku)).isZero();
        assertThat(countLiveForSeller()).isEqualTo(5);
        create("other", UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void concurrentCreatesForDifferentSkusCannotBothTakeTheLastSellerSlot() throws Exception {
        for (int existing = 0; existing < 4; existing++) {
            create("seller", UUID.randomUUID()).andExpect(status().isCreated());
        }

        UUID firstSku = UUID.randomUUID();
        UUID secondSku = UUID.randomUUID();
        race(() -> create("seller", firstSku), () -> create("seller", secondSku), "AUCTION_SELLER_LIMIT_REACHED");
        assertThat(countLiveForSeller()).isEqualTo(5);
        assertThat(countForSku(firstSku) + countForSku(secondSku)).isEqualTo(1);
    }

    @Test
    void schedulerEndReleasesBothSkuAndSellerSlot() throws Exception {
        Instant end = Instant.now().plusSeconds(60);
        UUID sku = UUID.randomUUID();
        UUID original = seed(seller, sku, "ACTIVE", end, false);
        for (int existing = 0; existing < 4; existing++) {
            create("seller", UUID.randomUUID()).andExpect(status().isCreated());
        }
        assertThat(countLiveForSeller()).isEqualTo(5);
        jdbc.update("UPDATE auctions SET end_at = now() - interval '1 second' WHERE id = ?", original);

        await().atMost(Duration.ofSeconds(18)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status::text FROM auctions WHERE id = ?", String.class, original))
                        .isEqualTo("ENDED"));

        create("seller", sku).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CREATED"));
        assertThat(countForSku(sku)).isEqualTo(2);
        assertThat(countLiveForSeller()).isEqualTo(5);
    }

    private ResultActions create(String token, UUID sku) throws Exception {
        UUID productId = productIdsBySku.computeIfAbsent(sku, ignored -> UUID.randomUUID());
        UUID owner = token.equals("seller") ? seller : otherSeller;
        products.putIfAbsent(productId, new CatalogClient.Product(owner, "Catalog auction",
                List.of(new CatalogClient.Sku(sku)), List.of(new CatalogClient.Image("https://example.com/auction.png"))));
        Instant start = Instant.now().plusSeconds(3600);
        String body = """
                {"productId":"%s","skuId":"%s","title":"Auction limits","startingPrice":100000,
                 "startAt":"%s","endAt":"%s"}
                """.formatted(productId, sku, start, start.plusSeconds(7200));
        return client.perform(post("/api/v1/auctions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void lowReputationSellerCannotCreateAuctionOrPublishAuctionCreated() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        when(eligibility.check(seller, EligibilityAction.SELL)).thenReturn(
                new EligibilityClient.Decision(seller, EligibilityAction.SELL, false, 49, "BASIC", 50,
                        "Reputation score 49 is below 50 required for SELL", "reputation-policy-1"));
        UUID sku = UUID.randomUUID();
        create("seller", sku).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUCTION_REPUTATION_TOO_LOW"))
                .andExpect(jsonPath("$.detail").value("Reputation score 49 is below 50 required for SELL"));
        assertNoSellerWrites();
        doReturn(sellerEligible()).when(eligibility).check(seller, EligibilityAction.SELL);
        create("seller", sku).andExpect(status().isCreated());
        assertThat(countForSku(sku)).isEqualTo(1);
    }

    @Test
    void ratingOutagePreventsCreationWithoutAnyWriteAndAllowsRetryAfterRecovery() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        when(eligibility.check(seller, EligibilityAction.SELL)).thenThrow(new IllegalStateException("Rating unavailable"));
        UUID sku = UUID.randomUUID();
        create("seller", sku).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUCTION_REPUTATION_UNAVAILABLE"));
        assertNoSellerWrites();
        doReturn(sellerEligible()).when(eligibility).check(seller, EligibilityAction.SELL);
        create("seller", sku).andExpect(status().isCreated());
    }

    @Test
    void mismatchedDecisionCannotCreateAnAuction() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        when(eligibility.check(seller, EligibilityAction.SELL)).thenReturn(
                new EligibilityClient.Decision(seller, EligibilityAction.BID, true, 50, "NEW", 40,
                        "Wrong action", "reputation-policy-1"));
        create("seller", UUID.randomUUID()).andExpect(status().isServiceUnavailable());
        assertNoSellerWrites();
    }

    @Test
    void flagOffCreatesWithoutCallingEligibility() throws Exception {
        when(eligibility.check(any(), any())).thenThrow(new IllegalStateException("Must be skipped"));
        create("seller", UUID.randomUUID()).andExpect(status().isCreated());
        verify(eligibility, never()).check(any(), any());
    }

    private EligibilityClient.Decision sellerEligible() {
        return new EligibilityClient.Decision(seller, EligibilityAction.SELL, true, 50, "NEW", 50,
                "Reputation score meets the SELL minimum", "reputation-policy-1");
    }

    private void assertNoSellerWrites() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auctions WHERE seller_id=?", Long.class, seller)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload->'payload'->>'sellerId'=?",
                Long.class, seller.toString())).isZero();
    }

    private static ResultActions conflict(ResultActions result, String code) throws Exception {
        return result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static void race(Callable<ResultActions> first, Callable<ResultActions> second, String code) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(() -> startTogether(first, ready, start));
            var secondResult = executor.submit(() -> startTogether(second, ready, start));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            ResultActions one = firstResult.get(15, TimeUnit.SECONDS);
            ResultActions two = secondResult.get(15, TimeUnit.SECONDS);
            assertThat(List.of(one.andReturn().getResponse().getStatus(), two.andReturn().getResponse().getStatus()))
                    .containsExactlyInAnyOrder(201, 409);
            conflict(one.andReturn().getResponse().getStatus() == 409 ? one : two, code);
        }
    }

    private static ResultActions startTogether(Callable<ResultActions> request, CountDownLatch ready,
                                               CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return request.call();
    }

    private UUID seed(UUID owner, UUID sku, String status, Instant end, boolean deleted) {
        Instant start = status.equals("SCHEDULED") ? end.minusSeconds(60) : end.minusSeconds(7200);
        return jdbc.queryForObject("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, starting_price, bid_increment,
                    current_price, status, start_at, end_at, original_end_at, deleted_at)
                VALUES (gen_random_uuid(), ?, ?, 'Limits fixture', 100000, 10, 100000, ?::auction_status,
                    ?::timestamptz, ?::timestamptz, ?::timestamptz, CASE WHEN ? THEN now() END)
                RETURNING id
                """, UUID.class, sku, owner, status, start.toString(), end.toString(), end.toString(), deleted);
    }

    private long countForSku(UUID sku) {
        return jdbc.queryForObject("SELECT count(*) FROM auctions WHERE sku_id = ?", Long.class, sku);
    }

    private long countLiveForSeller() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM auctions
                WHERE seller_id = ? AND deleted_at IS NULL AND status IN ('CREATED', 'SCHEDULED', 'ACTIVE')
                """, Long.class, seller);
    }

    private static Jwt token(String value, UUID subject) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(subject.toString())
                .claim("roles", List.of("SELLER")).claim("privileges", List.of("AUCTION.CREATE")).build();
    }
}
