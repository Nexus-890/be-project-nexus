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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-manage-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.open-in-view=false", "management.tracing.export.zipkin.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs"
})
class AuctionManageIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    static { POSTGRES.start(); }
    private static final UUID SELLER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final String MANAGE = "/api/v1/auctions/manage";
    private static final List<String> READ_PRIVILEGES = List.of("AUCTION.LIST", "AUCTION.VIEW", "AUCTION.VIEW_BID_HISTORY");
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc http;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        http = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        when(decoder.decode("admin")).thenReturn(token("admin", "ADMIN", READ_PRIVILEGES.toArray(String[]::new)));
        when(decoder.decode("support")).thenReturn(token("support", "SUPPORT_STAFF", READ_PRIVILEGES.toArray(String[]::new)));
        when(decoder.decode("seller")).thenReturn(token("seller", "SELLER", READ_PRIVILEGES.toArray(String[]::new)));
        when(decoder.decode("buyer")).thenReturn(token("buyer", "BUYER", READ_PRIVILEGES.toArray(String[]::new)));
        when(decoder.decode("support-no-privilege")).thenReturn(token("support-no-privilege", "SUPPORT_STAFF"));
        jdbc.update("DELETE FROM outbox"); jdbc.update("DELETE FROM bids"); jdbc.update("DELETE FROM auctions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "support"})
    void backOfficeListsEveryStatusAndVisibilityNewestFirstWithoutSoftDeletedRows(String token) throws Exception {
        auction(1, SELLER, "CREATED", "PUBLIC");
        auction(2, OTHER, "CANCELLED", "PUBLIC");
        auction(3, SELLER, "ACTIVE", "RESTRICTED");
        auction(4, OTHER, "SETTLED", "PUBLIC");
        auction(5, SELLER, "ACTIVE", "PUBLIC");
        jdbc.update("UPDATE auctions SET deleted_at=now() WHERE id=?", id(5));
        http.perform(get(MANAGE).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.data[*].id").value(contains(id(4).toString(), id(3).toString(), id(2).toString(), id(1).toString())));
        for (String state : List.of("CREATED", "CANCELLED")) {
            http.perform(get(MANAGE).header("Authorization", "Bearer " + token).param("status", state))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].status").value(state));
            http.perform(get("/api/v1/auctions").param("status", state))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        }
        http.perform(get(MANAGE).header("Authorization", "Bearer " + token).param("status", "ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id(3).toString()));
        http.perform(get("/api/v1/auctions").param("status", "ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void backOfficePagingKeepsTotalsAndRejectsInvalidQueries() throws Exception {
        for (int number = 1; number <= 3; number++) auction(number, SELLER, "CREATED", "PUBLIC");
        http.perform(get(MANAGE).header("Authorization", "Bearer admin").param("status", "CREATED").param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id(1).toString()))
                .andExpect(jsonPath("$.page.number").value(1)).andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3)).andExpect(jsonPath("$.page.totalPages").value(2));
        for (String query : List.of("status=DRAFT", "page=-1", "size=0", "size=101")) {
            http.perform(get(MANAGE + "?" + query).header("Authorization", "Bearer admin"))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "support"})
    void backOfficeReadsDetailStateAndMaskedBidsOfRestrictedAuctionsThatStayHiddenPublicly(String token) throws Exception {
        auction(1, SELLER, "ACTIVE", "RESTRICTED");
        bid(11, 1, 110, 1); bid(12, 1, 130, 2);
        jdbc.update("UPDATE auctions SET current_price=130,bid_count=2,leader_bidder_name='N***n' WHERE id=?", id(1));
        String path = MANAGE + "/" + id(1);
        http.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id(1).toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE")).andExpect(jsonPath("$.data.bidCount").value(2));
        http.perform(get(path + "/state").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.auctionId").value(id(1).toString()))
                .andExpect(jsonPath("$.data.currentPrice").value(130)).andExpect(jsonPath("$.data.bidCount").value(2))
                .andExpect(jsonPath("$.data.leaderBidderName").value("N***n")).andExpect(jsonPath("$.data.serverTime").isString());
        http.perform(get(path + "/bids").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].amount").value(130)).andExpect(jsonPath("$.data[1].amount").value(110))
                .andExpect(jsonPath("$.data[0].bidderName").value("B***r"))
                .andExpect(jsonPath("$.data[0].bidderId").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(2));
        for (String publicPath : List.of("/api/v1/auctions/" + id(1), "/api/v1/auctions/" + id(1) + "/state", "/api/v1/auctions/" + id(1) + "/bids")) {
            http.perform(get(publicPath)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
        }
    }

    @Test
    void backOfficeReadsReturnNotFoundForMissingAndSoftDeletedAuctions() throws Exception {
        auction(1, SELLER, "CANCELLED", "PUBLIC");
        jdbc.update("UPDATE auctions SET deleted_at=now() WHERE id=?", id(1));
        for (UUID auction : List.of(id(1), id(99))) {
            for (String suffix : List.of("", "/state", "/bids")) {
                http.perform(get(MANAGE + "/" + auction + suffix).header("Authorization", "Bearer admin"))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/{id}", "/{id}/state", "/{id}/bids"})
    void everyBackOfficeReadRequiresAdminOrSupportRoleAndItsPrivilege(String suffix) throws Exception {
        auction(1, SELLER, "CREATED", "PUBLIC");
        String path = MANAGE + suffix.replace("{id}", id(1).toString());
        http.perform(get(path)).andExpect(status().isUnauthorized());
        for (String token : List.of("seller", "buyer", "support-no-privilege")) {
            http.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        http.perform(get(path).header("Authorization", "Bearer support")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
    }

    private void auction(int number, UUID seller, String status, String visibility) {
        jdbc.update("""
                INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,current_price,
                status,visibility,start_at,end_at,original_end_at,created_at)
                VALUES(?,gen_random_uuid(),gen_random_uuid(),?,?,100,10,100,?::auction_status,?::auction_visibility,
                now()-interval '1 hour',now()+interval '1 hour',now()+interval '1 hour',?::timestamptz)
                """, id(number), seller, "Auction " + number, status, visibility, "2026-11-12T00:00:%02dZ".formatted(number));
    }

    private void bid(int number, int auction, int amount, int second) {
        jdbc.update("""
                INSERT INTO bids(id,auction_id,bidder_id,bidder_name,amount,status,idempotency_key,placed_at)
                VALUES(?,?,gen_random_uuid(),'B***r',?,'PLACED'::bid_status,?,?::timestamptz)
                """, id(number), id(auction), amount, id(number).toString(), "2026-11-12T00:01:%02dZ".formatted(second));
    }

    private static UUID id(int number) { return UUID.fromString("26000000-0000-4000-8000-%012d".formatted(number)); }

    private static Jwt token(String value, String role, String... privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privileges)).build();
    }
}
