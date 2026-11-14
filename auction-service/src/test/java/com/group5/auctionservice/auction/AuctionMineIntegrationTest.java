package com.group5.auctionservice.auction;

import com.group5.auctionservice.TestPostgres;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** PostgreSQL/Flyway/controller/security/service are real; only JWT decoding is replaced. */
@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-mine-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.open-in-view=false", "management.tracing.export.zipkin.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs"
})
class AuctionMineIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    static { POSTGRES.start(); }
    private static final UUID SELLER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID(), RIVAL = UUID.randomUUID();
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc http;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @BeforeEach
    void setup() {
        http = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        when(decoder.decode("seller")).thenReturn(token("seller", SELLER, "SELLER", "AUCTION.LIST"));
        when(decoder.decode("other")).thenReturn(token("other", OTHER, "SELLER", "AUCTION.LIST"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER, "BUYER", "AUCTION.VIEW_BID_HISTORY"));
        when(decoder.decode("rival")).thenReturn(token("rival", RIVAL, "BUYER", "AUCTION.VIEW_BID_HISTORY"));
        when(decoder.decode("missing")).thenReturn(token("missing", SELLER, "SELLER"));
        when(decoder.decode("wrong-role")).thenReturn(token("wrong-role", BUYER, "SUPPORT", "AUCTION.LIST", "AUCTION.VIEW_BID_HISTORY"));
        jdbc.update("DELETE FROM outbox"); jdbc.update("DELETE FROM bids"); jdbc.update("DELETE FROM auctions");
    }
    @Test
    void sellerSeesOwnCreatedCancelledRestrictedButPublicListHidesThem() throws Exception {
        auction(1, SELLER, "CREATED", "PUBLIC");
        auction(2, SELLER, "CANCELLED", "PUBLIC");
        auction(3, SELLER, "ACTIVE", "RESTRICTED");
        auction(4, OTHER, "CREATED", "PUBLIC");
        auction(5, SELLER, "ACTIVE", "PUBLIC");
        jdbc.update("UPDATE auctions SET deleted_at=now() WHERE id=?", id(5));
        http.perform(get("/api/v1/auctions/mine").header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.contains(id(3).toString(), id(2).toString(), id(1).toString())));
        for (String state : List.of("CREATED", "CANCELLED")) {
            http.perform(get("/api/v1/auctions").param("status", state))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }
        http.perform(get("/api/v1/auctions/mine").header("Authorization", "Bearer other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id(4).toString()));
        http.perform(get("/api/v1/auctions")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test
    void sellerFiltersPagesAndCannotOverrideJwtOwner() throws Exception {
        auction(1, SELLER, "CREATED", "PUBLIC"); auction(2, SELLER, "CANCELLED", "PUBLIC");
        auction(3, SELLER, "CREATED", "PUBLIC"); auction(4, OTHER, "CREATED", "PUBLIC");
        http.perform(get("/api/v1/auctions/mine").header("Authorization", "Bearer seller")
                        .param("sellerId", OTHER.toString()).param("status", "CREATED").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id(1).toString()))
                .andExpect(jsonPath("$.page.number").value(1)).andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        http.perform(get("/api/v1/auctions/mine").header("Authorization", "Bearer seller").param("page", "9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.page.totalElements").value(3));
    }
    @Test
    void bidderGetsOneRowPerAuctionWithLatestAmountAndActualLeader() throws Exception {
        auction(1, SELLER, "ACTIVE", "PUBLIC"); auction(2, SELLER, "ACTIVE", "RESTRICTED");
        bid(11, 1, BUYER, 110, "PLACED", 1); bid(12, 1, BUYER, 130, "PLACED", 2);
        bid(13, 1, RIVAL, 140, "PLACED", 3); bid(14, 2, BUYER, 120, "PLACED", 4);
        leader(1, RIVAL, 140); leader(2, BUYER, 120);
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer").param("bidderId", RIVAL.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].auctionId").value(id(2).toString()))
                .andExpect(jsonPath("$.data[0].leading").value(true))
                .andExpect(jsonPath("$.data[1].auctionId").value(id(1).toString()))
                .andExpect(jsonPath("$.data[1].amount").value(130))
                .andExpect(jsonPath("$.data[1].placedAt").value("2026-11-12T00:00:02Z"))
                .andExpect(jsonPath("$.data[1].leading").value(false)).andExpect(jsonPath("$.data[1].won").value(false));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer rival"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(140)).andExpect(jsonPath("$.data[0].leading").value(true));
        leader(1, BUYER, 130);
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[1].leading").value(true));
    }
    @Test
    void groupingPrecedesPagingAndTimestampTiesUseBidId() throws Exception {
        auction(1, SELLER, "ACTIVE", "PUBLIC"); auction(2, SELLER, "ACTIVE", "PUBLIC");
        bid(11, 1, BUYER, 110, "PLACED", 1); bid(12, 1, BUYER, 120, "PLACED", 1);
        bid(13, 2, BUYER, 130, "PLACED", 1);
        for (int page = 0; page < 2; page++) {
            http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer")
                            .param("size", "1").param("page", String.valueOf(page)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2))
                    .andExpect(jsonPath("$.page.totalPages").value(2))
                    .andExpect(jsonPath("$.data[0].auctionId").value(id(page == 0 ? 2 : 1).toString()))
                    .andExpect(jsonPath("$.data[0].amount").value(page == 0 ? 130 : 120));
        }
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer").param("page", "10").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.page.totalElements").value(2));
    }
    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "SCHEDULED", "ACTIVE", "ENDED", "WON", "SETTLED", "PAYMENT_TIMEOUT", "CANCELLED", "FAILED"})
    void leadingAndWonFollowValidTopBidAndRecordedWinner(String state) throws Exception {
        auction(1, SELLER, state, "PUBLIC"); bid(11, 1, BUYER, 120, "PLACED", 1); leader(1, BUYER, 120);
        jdbc.update("UPDATE auctions SET winner_id=?,order_id=?,payment_due_at='2031-01-02T00:00:00Z',final_price=125.50 WHERE id=?",
                BUYER, id(80), id(1));
        boolean won = List.of("WON", "SETTLED", "PAYMENT_TIMEOUT").contains(state);
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].auctionStatus").value(state))
                .andExpect(jsonPath("$.data[0].leading").value(List.of("ACTIVE", "ENDED").contains(state)))
                .andExpect(jsonPath("$.data[0].won").value(won))
                .andExpect(jsonPath("$.data[0].orderId").value(won ? id(80).toString() : null))
                .andExpect(jsonPath("$.data[0].paymentDueAt").value(won ? "2031-01-02T00:00:00Z" : null))
                .andExpect(jsonPath("$.data[0].finalPrice").value(won ? 125.50 : null));
        jdbc.update("UPDATE auctions SET winner_id=? WHERE id=?", RIVAL, id(1));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].won").value(false))
                .andExpect(jsonPath("$.data[0].orderId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].paymentDueAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].finalPrice").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test
    void recordedWinnerWaitsForActualOrderProjectionAndReferencesStayPrivate() throws Exception {
        auction(1, SELLER, "WON", "PUBLIC");
        bid(11, 1, BUYER, 120, "PLACED", 1); bid(12, 1, RIVAL, 110, "PLACED", 2);
        jdbc.update("UPDATE auctions SET winner_id=?,payment_due_at='2031-01-02T00:00:00Z',final_price=120 WHERE id=?", BUYER, id(1));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].won").value(true))
                .andExpect(jsonPath("$.data[0].orderId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].finalPrice").value(120));

        // #172 stores this real OrderCreated reference; this read never fabricates one.
        jdbc.update("UPDATE auctions SET status='SETTLED',order_id=? WHERE id=?", id(80), id(1));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].orderId").value(id(80).toString()))
                .andExpect(jsonPath("$.data[0].paymentDueAt").value("2031-01-02T00:00:00Z"));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer rival").param("bidderId", BUYER.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].won").value(false))
                .andExpect(jsonPath("$.data[0].orderId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].paymentDueAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].finalPrice").value(org.hamcrest.Matchers.nullValue()));
        http.perform(get("/api/v1/auctions/" + id(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").doesNotExist())
                .andExpect(jsonPath("$.data.paymentDueAt").doesNotExist())
                .andExpect(jsonPath("$.data.finalPrice").doesNotExist());
        jdbc.update("UPDATE auctions SET winner_id=NULL WHERE id=?", id(1));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].won").value(false))
                .andExpect(jsonPath("$.data[0].orderId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].paymentDueAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].finalPrice").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }
    @Test
    void invalidatedBidRemainsHistoryButCannotMakeStaleLeaderTrue() throws Exception {
        auction(1, SELLER, "ACTIVE", "PUBLIC"); bid(11, 1, BUYER, 150, "CANCELLED", 1); leader(1, BUYER, 150);
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].amount").value(150))
                .andExpect(jsonPath("$.data[0].leading").value(false));
        jdbc.update("UPDATE auctions SET deleted_at=now() WHERE id=?", id(1));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.page.totalElements").value(0));
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auctions/mine", "/api/v1/bids/mine"})
    void bothRoutesRequireBearerRoleAndExactPrivilege(String path) throws Exception {
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String token : List.of("missing", "wrong-role", path.contains("bids") ? "seller" : "buyer")) {
            http.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        http.perform(get(path).header("Authorization", path.contains("bids") ? "Bearer buyer" : "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.page.totalPages").value(0));
    }
    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=oops"})
    void invalidPagingReturnsProblemDetailsOnBothRoutes(String query) throws Exception {
        for (String path : List.of("/api/v1/auctions/mine", "/api/v1/bids/mine")) {
            http.perform(get(path + "?" + query).header("Authorization", path.contains("bids") ? "Bearer buyer" : "Bearer seller"))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }
    @Test
    void actualBidAndIdempotencyFlowKeepsOneRowAndChangesLeading() throws Exception {
        auction(1, SELLER, "ACTIVE", "PUBLIC");
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER, "BUYER", "AUCTION.BID", "AUCTION.VIEW_BID_HISTORY"));
        when(decoder.decode("rival")).thenReturn(token("rival", RIVAL, "BUYER", "AUCTION.BID", "AUCTION.VIEW_BID_HISTORY"));
        for (int amount : List.of(110, 120, 130)) {
            String token = amount == 130 ? "rival" : "buyer";
            String key = UUID.randomUUID().toString();
            http.perform(post("/api/v1/auctions/" + id(1) + "/bids").header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                    .andExpect(status().isCreated());
            http.perform(post("/api/v1/auctions/" + id(1) + "/bids").header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                    .andExpect(status().isOk());
        }
        http.perform(get("/api/v1/bids/mine?size=1").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(120)).andExpect(jsonPath("$.data[0].leading").value(false));
        http.perform(post("/api/v1/auctions/" + id(1) + "/bids").header("Authorization", "Bearer buyer")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":140}"))
                .andExpect(status().isCreated());
        http.perform(get("/api/v1/bids/mine?size=1").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(140)).andExpect(jsonPath("$.data[0].leading").value(true));
        http.perform(get("/api/v1/bids/mine").header("Authorization", "Bearer rival"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].amount").value(130))
                .andExpect(jsonPath("$.data[0].leading").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type='BidPlaced'", Integer.class)).isEqualTo(4);
    }

    @Test
    void invalidStatusIsBindingErrorNotUuidRouteAndReadsDoNotWriteEvents() throws Exception {
        http.perform(get("/api/v1/auctions/mine?status=INVALID").header("Authorization", "Bearer seller"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }
    private void auction(int number, UUID seller, String status, String visibility) {
        jdbc.update("""
                INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,current_price,
                status,visibility,start_at,end_at,original_end_at,created_at)
                VALUES(?,gen_random_uuid(),gen_random_uuid(),?,?,100,10,100,?::auction_status,?::auction_visibility,
                now()-interval '1 hour',now()+interval '1 hour',now()+interval '1 hour','2026-11-12T00:00:00Z')
                """, id(number), seller, "Auction " + number, status, visibility);
    }
    private void bid(int number, int auction, UUID bidder, int amount, String status, int second) {
        jdbc.update("INSERT INTO bids(id,auction_id,bidder_id,amount,status,idempotency_key,placed_at) VALUES(?,?,?,?,?::bid_status,?,?::timestamptz)",
                id(number), id(auction), bidder, amount, status, id(number).toString(), "2026-11-12T00:00:%02dZ".formatted(second));
    }
    private void leader(int auction, UUID buyer, int amount) { jdbc.update("UPDATE auctions SET leader_bidder_id=?,current_price=? WHERE id=?", buyer, amount, id(auction)); }
    private static UUID id(int number) { return UUID.fromString("19800000-0000-4000-8000-%012d".formatted(number)); }
    private static Jwt token(String value, UUID subject, String role, String... privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(subject.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privileges)).build();
    }
}
