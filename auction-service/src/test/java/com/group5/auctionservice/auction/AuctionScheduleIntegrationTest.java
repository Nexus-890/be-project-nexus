package com.group5.auctionservice.auction;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import com.group5.auctionservice.TestPostgres;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/auction-schedule-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.open-in-view=false", "management.tracing.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs"
})
class AuctionScheduleIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    static { POSTGRES.start(); }

    private static final String PATH = "/api/v1/auctions";
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER_SELLER = UUID.randomUUID();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private TransactionTemplate transactions;
    @Autowired private AuctionRepository auctions;
    @Autowired private AuctionStateMachine stateMachine;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private AuctionInventoryClient inventory;
    private MockMvc client;
    private UUID auctionId;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void createAuction() throws Exception {
        client = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        when(decoder.decode("owner")).thenReturn(token("owner", OWNER, "SELLER", "AUCTION.CREATE", "AUCTION.UPDATE"));
        when(decoder.decode("other")).thenReturn(token("other", OTHER_SELLER, "SELLER", "AUCTION.UPDATE"));
        when(decoder.decode("admin")).thenReturn(token("admin", OWNER, "ADMIN", "AUCTION.UPDATE"));
        when(decoder.decode("no-update")).thenReturn(token("no-update", OWNER, "SELLER", "AUCTION.CREATE"));
        Instant start = Instant.now().plusSeconds(3600);
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        when(catalog.getProduct(productId)).thenReturn(ApiResponse.of(new CatalogClient.Product(
                OWNER, "Scheduled auction", List.of(new CatalogClient.Sku(skuId)),
                List.of(new CatalogClient.Image("https://example.com/auction.png")))));
        when(inventory.available(any())).thenAnswer(call ->
                ApiResponse.of(new AuctionInventoryClient.Stock(call.getArgument(0), 5)));
        String body = """
                {"productId":"%s","skuId":"%s","title":"Scheduled auction","startingPrice":100000,
                 "startAt":"%s","endAt":"%s"}
                """.formatted(productId, skuId, start, start.plusSeconds(7200));
        String response = client.perform(post(PATH).header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        auctionId = UUID.fromString(json.readTree(response).path("data").path("id").asText());
    }

    @Test
    void ownerSchedulesOnceAndPublicDetailReadsCommittedStatus() throws Exception {
        Map<String, Object> before = row();
        schedule("owner").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(auctionId.toString()))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.page").doesNotExist());
        Map<String, Object> scheduled = row();
        assertThat(scheduled).containsEntry("status_text", "SCHEDULED")
                .containsEntry("version", (long) before.get("version") + 1)
                .containsEntry("start_at", before.get("start_at"))
                .containsEntry("end_at", before.get("end_at"));

        schedule("owner").andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_ILLEGAL_TRANSITION"));
        assertThat(row()).isEqualTo(scheduled);
        client.perform(get(PATH + "/" + auctionId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    @Test
    void otherSellerIsForbiddenBeforeAndAfterScheduling() throws Exception {
        Map<String, Object> created = row();
        schedule("other").andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_FORBIDDEN"));
        assertThat(row()).isEqualTo(created);
        schedule("owner").andExpect(status().isOk());
        Map<String, Object> scheduled = row();
        schedule("other").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUCTION_FORBIDDEN"));
        assertThat(row()).isEqualTo(scheduled);
    }

    @Test
    void illegalSkipReturnsConflictAndDoesNotChangeTheRow() {
        Map<String, Object> before = row();
        assertThatExceptionOfType(DomainException.class).isThrownBy(() ->
                        transactions.executeWithoutResult(tx -> stateMachine.transition(
                                auctions.findById(auctionId).orElseThrow(), AuctionStatus.ENDED)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("AUCTION_ILLEGAL_TRANSITION");
                });
        assertThat(row()).isEqualTo(before).containsEntry("status_text", "CREATED");
    }

    @Test
    void simultaneousSchedulesCommitOnlyOnce() throws Exception {
        long version = (long) row().get("version");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return schedule("owner").andReturn().getResponse().getStatus();
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(row()).containsEntry("status_text", "SCHEDULED").containsEntry("version", version + 1);
    }

    @Test
    void scheduleRequiresBearerSellerRoleAndUpdatePrivilege() throws Exception {
        Map<String, Object> before = row();
        client.perform(post(PATH + "/" + auctionId + "/schedule")).andExpect(status().isUnauthorized());
        schedule("admin").andExpect(status().isForbidden());
        schedule("no-update").andExpect(status().isForbidden());
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void missingOrDeletedAuctionReturnsNotFound() throws Exception {
        client.perform(post(PATH + "/" + UUID.randomUUID() + "/schedule").header("Authorization", "Bearer owner"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
        jdbc.update("UPDATE auctions SET deleted_at = now() WHERE id = ?", auctionId);
        Map<String, Object> deleted = row();
        schedule("owner").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
        assertThat(row()).isEqualTo(deleted);
    }

    private ResultActions schedule(String token) throws Exception {
        return client.perform(post(PATH + "/" + auctionId + "/schedule").header("Authorization", "Bearer " + token));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT auctions.*, status::text AS status_text FROM auctions WHERE id = ?", auctionId);
    }

    private static Jwt token(String value, UUID subject, String role, String... privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(subject.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privileges)).build();
    }
}
