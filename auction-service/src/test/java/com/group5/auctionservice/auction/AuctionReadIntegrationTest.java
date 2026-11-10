package com.group5.auctionservice.auction;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real security, Flyway, JPA queries and PostgreSQL 18. No Catalog client or server is supplied. */
@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-read-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.open-in-view=false", "management.tracing.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs"
})
class AuctionReadIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static { POSTGRES.start(); }

    private static final String PATH = "/api/v1/auctions";
    private static final String PREFIX = "00000000-0000-4000-8000-00000000000";
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc client;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        client = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        jdbc.update("DELETE FROM auctions");
        seed(1, "ACTIVE", "PUBLIC", 180, false);
        seed(2, "ACTIVE", "PUBLIC", 60, false);
        seed(3, "ACTIVE", "PUBLIC", 60, false);
        seed(4, "SCHEDULED", "PUBLIC", 30, false);
        seed(5, "ACTIVE", "RESTRICTED", 15, false);
        seed(6, "ACTIVE", "PUBLIC", 10, true);
        seed(7, "ENDED", "PUBLIC", -60, false);
    }

    @Test
    void anonymousDetailReadsStoredSnapshotWithoutCatalogAndExposesNoBidderIdentity() throws Exception {
        client.perform(get(PATH + "/" + PREFIX + "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.id").value(PREFIX + "1"))
                .andExpect(jsonPath("$.data.title").value("Snapshot 1"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/1.jpg"))
                .andExpect(jsonPath("$.data.currentPrice").value(125.25))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.winnerId").doesNotExist())
                .andExpect(jsonPath("$.data.leaderBidderId").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    void activeFilterSortAndPagingApplyBeforeCountingAndExcludeHiddenRows() throws Exception {
        client.perform(get(PATH).param("status", "ACTIVE").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(PREFIX + "2"))
                .andExpect(jsonPath("$.data[1].id").value(PREFIX + "3"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        client.perform(get(PATH).param("status", "ACTIVE").param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(PREFIX + "1"))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void omittedStatusReturnsOnlyPublicNonDeletedActiveAuctionsWithDefaultPaging() throws Exception {
        client.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[2].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].id").value(PREFIX + "2"))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(3));
        client.perform(get(PATH).param("status", "SCHEDULED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void emptyAndOutOfRangePagesRetainActualTotals() throws Exception {
        client.perform(get(PATH).param("status", "WON"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        client.perform(get(PATH).param("status", "ACTIVE").param("page", "9").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(9))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6, 9})
    void unknownRestrictedAndDeletedDetailsReturnSame404(int id) throws Exception {
        client.perform(get(PATH + "/" + PREFIX + id)).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value(PATH + "/" + PREFIX + id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=INVALID", "status=active", "page=no", "size=no"})
    void unparseableQueryReturnsInvalidParameter(String query) throws Exception {
        client.perform(get(PATH + "?" + query)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=-1", "size=101"})
    void pagingBoundsReturnFieldValidationErrors(String query) throws Exception {
        client.perform(get(PATH + "?" + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(query.split("=")[0]));
    }

    @Test
    void maximumSizeNullImageAndMalformedUuidAreHandled() throws Exception {
        jdbc.update("UPDATE auctions SET image_url = NULL WHERE id = ?::uuid", PREFIX + "7");
        client.perform(get(PATH).param("size", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
        client.perform(get(PATH + "/" + PREFIX + "7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").isEmpty());
        client.perform(get(PATH + "/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void readingDoesNotWriteAndPublicGetDoesNotOpenPost() throws Exception {
        List<?> before = jdbc.queryForList("SELECT * FROM auctions ORDER BY id");
        client.perform(get(PATH)).andExpect(status().isOk());
        client.perform(get(PATH + "/" + PREFIX + "1")).andExpect(status().isOk());
        client.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        client.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList("SELECT * FROM auctions ORDER BY id")).isEqualTo(before);
    }

    private void seed(int id, String status, String visibility, int endMinutes, boolean deleted) {
        jdbc.update("""
                INSERT INTO auctions (id, product_id, sku_id, seller_id, title, image_url,
                    starting_price, current_price, bid_increment, start_at, end_at, original_end_at,
                    status, visibility, deleted_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, 125.25, 125.25, 10,
                    '2026-11-05T00:00:00Z'::timestamptz,
                    '2026-11-05T12:00:00Z'::timestamptz + ? * interval '1 minute',
                    '2026-11-05T12:00:00Z'::timestamptz + ? * interval '1 minute',
                    ?::auction_status, ?::auction_visibility,
                    CASE WHEN ? THEN now() ELSE NULL END)
                """, PREFIX + id, PREFIX + "1", PREFIX + "2", PREFIX + "3", "Snapshot " + id,
                "https://cdn.example.com/" + id + ".jpg", endMinutes, endMinutes, status, visibility, deleted);
    }
}
