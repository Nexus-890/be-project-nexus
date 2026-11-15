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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real PostgreSQL, HTTP dispatch/security, Flyway, JPA and outbox; only JWT decoding and clock are mocked. */
@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/configure-cancel-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.open-in-view=false", "management.tracing.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs",
        "AUCTION_MIN_DURATION_MINUTES=90", "AUCTION_MAX_DURATION_HOURS=3"
})
class AuctionConfigureCancelIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    static { POSTGRES.start(); }
    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final Instant START = NOW.plusSeconds(3600);
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String PATH = "/api/v1/auctions/";
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private Clock clock;
    private MockMvc client;
    private UUID id;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        client = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(decoder.decode("owner")).thenReturn(token("owner", OWNER, "SELLER", "AUCTION.UPDATE", "AUCTION.CANCEL"));
        when(decoder.decode("other")).thenReturn(token("other", UUID.randomUUID(), "SELLER", "AUCTION.UPDATE", "AUCTION.CANCEL"));
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN, "ADMIN", "AUCTION.ADMIN_CANCEL", "AUCTION.UPDATE"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", OWNER, "BUYER", "AUCTION.UPDATE", "AUCTION.CANCEL", "AUCTION.ADMIN_CANCEL"));
        when(decoder.decode("no-privilege")).thenReturn(token("no-privilege", OWNER, "SELLER"));
        when(decoder.decode("admin-no-update")).thenReturn(token("admin-no-update", ADMIN, "ADMIN", "AUCTION.ADMIN_CANCEL"));
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM bids");
        jdbc.update("DELETE FROM auctions");
        id = jdbc.queryForObject("""
                INSERT INTO auctions(product_id, sku_id, seller_id, title, starting_price, bid_increment,
                    current_price, status, start_at, end_at, original_end_at)
                VALUES(gen_random_uuid(), gen_random_uuid(), ?, 'configure cancel fixture', 100000, 10000,
                    100000, 'SCHEDULED', ?::timestamptz, ?::timestamptz, ?::timestamptz) RETURNING id
                """, UUID.class, OWNER, START.toString(), START.plusSeconds(7200).toString(), START.plusSeconds(7200).toString());
    }

    @Test
    void scheduledEditPersistsAllParametersIncludingPriceAndExtensionBaseline() throws Exception {
        edit("owner", body(120)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.startingPrice").value(200000))
                .andExpect(jsonPath("$.data.currentPrice").value(200000))
                .andExpect(jsonPath("$.data.bidIncrement").value(20000));
        assertThat(row().get("starting_price")).isEqualTo(new BigDecimal("200000.00"));
        assertThat(row().get("end_at")).isEqualTo(row().get("original_end_at"));
        client.perform(get(PATH + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentPrice").value(200000));
        assertThat(events()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "ENDED", "WON", "SETTLED", "FAILED", "PAYMENT_TIMEOUT", "CANCELLED"})
    void editingStartedOrFinalAuctionsIsConflictEvenWithFutureRequest(String state) throws Exception {
        setStatus(state);
        var before = row();
        edit("owner", body(120)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_NOT_EDITABLE"));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void storedStartTimeBlocksEditingEvenBeforeSchedulerChangesStatus() throws Exception {
        jdbc.update("UPDATE auctions SET start_at = ?::timestamptz WHERE id = ?", NOW.toString(), id);
        edit("owner", body(120)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_NOT_EDITABLE"));
    }

    @Test
    void adminEditsAnotherSellersScheduledAuctionWithoutTakingOwnership() throws Exception {
        edit("admin", body(120)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sellerId").value(OWNER.toString()))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.startingPrice").value(200000))
                .andExpect(jsonPath("$.data.bidIncrement").value(20000));
        assertThat(row().get("seller_id")).isEqualTo(OWNER);
        assertThat(row().get("starting_price")).isEqualTo(new BigDecimal("200000.00"));
        assertThat(events()).isZero();
    }

    @Test
    void sellerEditingAnotherSellersAuctionIsStillForbidden() throws Exception {
        var before = row();
        edit("other", body(120)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUCTION_FORBIDDEN"));
        edit("admin-no-update", body(120)).andExpect(status().isForbidden());
        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "ENDED", "CANCELLED"})
    void adminEditingStartedOrFinalAuctionIsNotEditable(String state) throws Exception {
        setStatus(state);
        var before = row();
        edit("admin", body(120)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_NOT_EDITABLE"));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void adminEditingAfterStoredStartOrWithValidBidsIsRejected() throws Exception {
        seedBid("PLACED");
        edit("admin", body(120)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_HAS_BIDS"));
        jdbc.update("UPDATE auctions SET start_at = ?::timestamptz WHERE id = ?", NOW.toString(), id);
        edit("admin", body(120)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_NOT_EDITABLE"));
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 180})
    void configuredDurationBoundariesAreAccepted(int minutes) throws Exception {
        edit("owner", body(minutes)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(ints = {89, 181})
    void environmentMappedDurationLimitsRejectOutOfRangeRequests(int minutes) throws Exception {
        var before = row();
        edit("owner", body(minutes)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(minutes < 90 ? "AUCTION_DURATION_TOO_SHORT" : "AUCTION_DURATION_TOO_LONG"));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void invalidPriceMissingFieldsAndInvalidWindowDoNotModifyAuction() throws Exception {
        var before = row();
        edit("owner", body(120).replace("200000", "-1")).andExpect(status().isBadRequest());
        edit("owner", "{}").andExpect(status().isBadRequest());
        edit("owner", body(0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUCTION_INVALID_TIME_WINDOW"));
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void sellerWithoutBidsCancelsWithAuditAndOneVersionedEvent() throws Exception {
        cancel("owner", false).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledBy").value("SELLER"))
                .andExpect(jsonPath("$.data.cancelledActorId").value(OWNER.toString()))
                .andExpect(jsonPath("$.data.cancelledReason").value("Seller request"));
        assertCancellation("SELLER", OWNER);
        client.perform(get(PATH + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLACED", "OUTBID", "WON"})
    void sellerWithAnyValidBidGetsConflictWithoutWrites(String bidStatus) throws Exception {
        seedBid(bidStatus);
        var before = row();
        cancel("owner", false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_HAS_BIDS"));
        assertThat(row()).isEqualTo(before);
        assertThat(events()).isZero();
    }

    @Test
    void cancelledBidHistoryDoesNotBlockSellerCancellation() throws Exception {
        seedBid("CANCELLED");
        jdbc.update("UPDATE auctions SET bid_count = 10 WHERE id = ?", id);
        cancel("owner", false).andExpect(status().isOk());
        assertCancellation("SELLER", OWNER);
    }

    @Test
    void adminCancelsActiveAuctionWithBidsWithoutOwningIt() throws Exception {
        setStatus("ACTIVE");
        seedBid("PLACED");
        cancel("admin", true).andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelledBy").value("ADMIN"));
        assertCancellation("ADMIN", ADMIN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SETTLED", "FAILED", "PAYMENT_TIMEOUT", "CANCELLED"})
    void finalAuctionsCannotBeCancelled(String state) throws Exception {
        setStatus(state);
        var before = row();
        cancel("admin", true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_ILLEGAL_TRANSITION"));
        assertThat(row()).isEqualTo(before);
        assertThat(events()).isZero();
    }

    @Test
    void repeatedCancellationDoesNotCreateAnotherEventOrAuditWrite() throws Exception {
        cancel("owner", false).andExpect(status().isOk());
        var cancelled = row();
        cancel("admin", true).andExpect(status().isConflict());
        assertThat(row()).isEqualTo(cancelled);
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void rolePrivilegeAndOwnershipAreEnforced() throws Exception {
        var before = row();
        client.perform(post(PATH + id + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        for (String token : List.of("other", "buyer", "no-privilege")) {
            edit(token, body(120)).andExpect(status().isForbidden());
            cancel(token, false).andExpect(status().isForbidden());
        }
        cancel("owner", true).andExpect(status().isForbidden());
        cancel("buyer", true).andExpect(status().isForbidden());
        assertThat(row()).isEqualTo(before);
        assertThat(events()).isZero();
    }

    @Test
    void emptyOrOversizedReasonIsRejectedAndActorIsNeverTakenFromBody() throws Exception {
        for (String reason : List.of("", "   ", "x".repeat(256))) {
            client.perform(post(PATH + id + "/cancel").header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("reason", reason))))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(events()).isZero();
        client.perform(post(PATH + id + "/cancel").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Seller request\",\"actorId\":\"" + ADMIN + "\"}"))
                .andExpect(status().isOk());
        assertCancellation("SELLER", OWNER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAccepts255UnicodeCodePoints(boolean admin) throws Exception {
        String reason = "\uD83D\uDE00".repeat(255);
        cancel(admin ? "admin" : "owner", admin, reason).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelledReason").value(reason));
        assertCancellation(admin ? "ADMIN" : "SELLER", admin ? ADMIN : OWNER, reason);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationRejects256UnicodeCodePointsWithoutWrites(boolean admin) throws Exception {
        var before = row();
        String reason = "\uD83D\uDE00".repeat(256);
        cancel(admin ? "admin" : "owner", admin, reason).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(row()).isEqualTo(before);
        assertThat(events()).isZero();
    }

    @Test
    void missingAndSoftDeletedAuctionsReturnNotFound() throws Exception {
        UUID real = id;
        id = UUID.randomUUID();
        edit("owner", body(120)).andExpect(status().isNotFound());
        cancel("admin", true).andExpect(status().isNotFound());
        id = real;
        jdbc.update("UPDATE auctions SET deleted_at = now() WHERE id = ?", id);
        cancel("owner", false).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
        assertThat(events()).isZero();
    }

    @Test
    void outboxFailureRollsBackCancellationAndAudit() throws Exception {
        var before = row();
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT reject_cancel_test CHECK (event_type <> 'AuctionCancelled')");
        try {
            var failure = cancel("owner", false).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andReturn().getResolvedException();
            assertThat(failure)
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                    .hasStackTraceContaining("reject_cancel_test");
            assertThat(row()).isEqualTo(before);
            assertThat(events()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT reject_cancel_test");
        }
    }

    @Test
    void twoConcurrentCancellationsCommitOnce() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> request = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return cancel("owner", false).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertCancellation("SELLER", OWNER);
    }

    @Test
    void bidWinningRowLockCausesSellerCancellationToSeeCommittedBid() throws Exception {
        setStatus("ACTIVE");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var bid = pool.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM auctions WHERE id = ? FOR UPDATE", UUID.class, id);
                seedBid("PLACED");
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> cancel("owner", false).andReturn().getResponse().getStatus());
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class) > 0);
            } finally { release.countDown(); }
            bid.get(15, TimeUnit.SECONDS);
            assertThat(cancel.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(row().get("status_text")).isEqualTo("ACTIVE");
        assertThat(events()).isZero();
    }

    private void assertCancellation(String role, UUID actor) throws Exception {
        assertCancellation(role, actor, "Seller request");
    }

    private void assertCancellation(String role, UUID actor, String reason) throws Exception {
        var row = row();
        assertThat(row).containsEntry("status_text", "CANCELLED").containsEntry("cancelled_by", role)
                .containsEntry("cancelled_actor_id", actor).containsEntry("cancelled_reason", reason);
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(events()).isEqualTo(1);
        var event = json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ?", String.class, id.toString()));
        assertThat(event.path("eventType").asText()).isEqualTo("AuctionCancelled");
        assertThat(event.path("aggregateId").asText()).isEqualTo(id.toString());
        var payload = event.path("payload");
        for (String field : List.of("auctionId", "sellerId", "productId", "skuId", "actorId", "cancelledBy", "reason", "auctionVersion", "cancelledAt")) {
            assertThat(payload.hasNonNull(field)).as(field).isTrue();
        }
        assertThat(payload.path("auctionId").asText()).isEqualTo(id.toString());
        assertThat(payload.path("actorId").asText()).isEqualTo(actor.toString());
        assertThat(payload.path("cancelledBy").asText()).isEqualTo(role);
        assertThat(payload.path("reason").asText()).isEqualTo(reason);
        assertThat(payload.path("auctionVersion").asLong()).isEqualTo(((Number) row.get("version")).longValue());
        assertThat(Instant.parse(payload.path("cancelledAt").asText())).isEqualTo(NOW);
    }

    private ResultActions edit(String token, String body) throws Exception {
        return client.perform(put(PATH + id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions cancel(String token, boolean admin) throws Exception {
        return cancel(token, admin, "  Seller request  ");
    }

    private ResultActions cancel(String token, boolean admin, String reason) throws Exception {
        return client.perform(post(PATH + id + (admin ? "/admin-cancel" : "/cancel"))
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", reason))));
    }

    private String body(int minutes) {
        return """
                {"startingPrice":200000,"bidIncrement":20000,"startAt":"%s","endAt":"%s"}
                """.formatted(START, START.plusSeconds(minutes * 60L));
    }

    private void setStatus(String status) { jdbc.update("UPDATE auctions SET status = ?::auction_status WHERE id = ?", status, id); }
    private Map<String, Object> row() { return jdbc.queryForMap("SELECT *, status::text AS status_text FROM auctions WHERE id = ?", id); }
    private int events() { return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, id.toString()); }
    private void seedBid(String state) {
        jdbc.update("INSERT INTO bids(auction_id,bidder_id,amount,status,idempotency_key) VALUES(?,gen_random_uuid(),110000,?::bid_status,?)",
                id, state, UUID.randomUUID().toString());
    }
    private static Jwt token(String value, UUID subject, String role, String... privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(subject.toString())
                .claim("roles", List.of(role)).claim("privileges", List.of(privileges)).build();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for transaction"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
