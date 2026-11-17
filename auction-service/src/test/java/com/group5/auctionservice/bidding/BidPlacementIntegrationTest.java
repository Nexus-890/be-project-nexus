package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.reputation.EligibilityAction;
import com.group5.auctionservice.reputation.EligibilityClient;
import com.group5.auctionservice.reputation.ReputationGuard;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.group5.auctionservice.TestPostgres;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #146: real HTTP boundary, PostgreSQL 18, Flyway, conditional update and outbox transaction. */
@SpringJUnitConfig(BidPlacementIntegrationTest.Config.class)
@WebAppConfiguration
class BidPlacementIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final BigDecimal MINIMUM = new BigDecimal("1050000.00");
    static { POSTGRES.start(); }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxWriter outbox;
    @Autowired private AuctionRepository auctions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuctionProperties properties;
    @Autowired private EligibilityClient eligibility;
    private MockMvc client;

    @BeforeEach
    void setup() {
        reset(outbox);
        reset(eligibility);
        properties.setReputationEnforcementEnabled(false);
        properties.setAntiSnipingEnabled(true);
        properties.setAntiSnipingExtensionMinutes(5);
        properties.setAntiSnipingMaxExtensionMinutes(30);
        client = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void tooLowReturns400WithMinimumAndWritesNothing() throws Exception {
        Fixture auction = auction("ACTIVE");
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), UUID.randomUUID(), "1020000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_BID_TOO_LOW"))
                .andExpect(jsonPath("$.minimum").value(1050000.00))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void acceptedBidReturns201AndCommitsAuctionBidAndOneBidPlacedTogether() throws Exception {
        Fixture auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();

        MvcResult response = client.perform(request(auction.id(), bidder, MINIMUM.toPlainString()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.currentPrice").value(1050000.00))
                .andExpect(jsonPath("$.data.leaderBidderId").value(bidder.toString()))
                .andExpect(jsonPath("$.page").doesNotExist()).andReturn();

        JsonNode body = JsonMapper.shared().readTree(response.getResponse().getContentAsString());
        UUID bidId = UUID.fromString(body.get("data").get("bidId").asString());
        Map<String, Object> stored = assertAccepted(auction.id(), bidder, bidId);
        assertThat(stored.get("bidder_name")).isEqualTo("N***A");
        assertThat(jdbc.queryForObject("SELECT leader_bidder_name FROM auctions WHERE id = ?",
                String.class, auction.id())).isEqualTo("N***A");
        assertThat(stored.get("status").toString()).isEqualTo("PLACED");
        assertThat(stored.get("idempotency_key").toString()).isNotBlank();
        Instant storedPlacedAt = jdbc.queryForObject("SELECT placed_at FROM bids WHERE id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), bidId);
        assertThat(storedPlacedAt).isEqualTo(NOW);
        assertThat(stored.get("created_by")).isEqualTo(bidder);
        assertThat(stored.get("updated_by")).isEqualTo(bidder);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox WHERE aggregate_id = ?",
                auction.id().toString());
        assertThat(row.get("aggregate_type")).isEqualTo("Auction");
        assertThat(row.get("topic")).isEqualTo(Topics.AUCTION);
        assertThat(row.get("published_at")).isNull();
        JsonNode event = JsonMapper.shared().readTree(row.get("payload").toString());
        assertThat(event.get("eventType").asString()).isEqualTo("BidPlaced");
        assertThat(event.get("aggregateId").asString()).isEqualTo(auction.id().toString());
        assertThat(event.get("payload").get("auctionId").asString()).isEqualTo(auction.id().toString());
        assertThat(event.get("payload").get("bidId").asString()).isEqualTo(bidId.toString());
        assertThat(event.get("payload").get("bidderId").asString()).isEqualTo(bidder.toString());
        assertThat(event.get("payload").get("amount").asString()).isEqualTo("1050000.00");
        assertThat(event.get("payload").get("currency").asString()).isEqualTo("VND");
        assertThat(Instant.parse(event.get("payload").get("placedAt").asString())).isEqualTo(NOW);
    }

    @Test
    void createdAuctionReturns409AndDoesNotWriteABidOrOutbox() throws Exception {
        Fixture auction = auction("CREATED");
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    /** Chặn bid ngoài giờ kể cả khi scheduler chưa kịp đổi trạng thái ACTIVE. */
    @ParameterizedTest
    @CsvSource({"false, 1050000", "false, 1", "true, 1050000", "true, 1"})
    void activeAuctionOutsideItsTimeWindowReturns409WithoutWrites(boolean beforeStart, String amount) throws Exception {
        Fixture auction = auction("ACTIVE");
        if (beforeStart) {
            jdbc.update("UPDATE auctions SET start_at=now()+interval '1 hour', end_at=now()+interval '2 hours', "
                    + "original_end_at=now()+interval '2 hours' WHERE id=?", auction.id());
        } else {
            jdbc.update("UPDATE auctions SET start_at=now()-interval '2 hours', end_at=now()-interval '1 second', "
                    + "original_end_at=now()-interval '1 second' WHERE id=?", auction.id());
        }
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), UUID.randomUUID(), amount))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    /** Dùng cùng transaction DB để kiểm tra chính xác: nhận tại start_at, từ chối tại end_at. */
    @ParameterizedTest
    @CsvSource({"true, 1", "false, 0"})
    void conditionalUpdateHonorsInclusiveStartAndExclusiveEnd(boolean atStart, int expectedUpdates) {
        Fixture auction = auction("ACTIVE");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            if (atStart) {
                jdbc.update("UPDATE auctions SET start_at=now(), end_at=now()+interval '1 hour', "
                        + "original_end_at=now()+interval '1 hour' WHERE id=?", auction.id());
            } else {
                jdbc.update("UPDATE auctions SET start_at=now()-interval '1 hour', end_at=now(), "
                        + "original_end_at=now() WHERE id=?", auction.id());
            }
            Snapshot before = snapshot(auction.id());

            assertThat(auctions.acceptBid(auction.id(), UUID.randomUUID(), MINIMUM, "***")).isEqualTo(expectedUpdates);

            if (!atStart) assertThat(snapshot(auction.id())).isEqualTo(before);
            // Chỉ kiểm tra UPDATE; rollback để không để lại auction tăng giá mà chưa có bid/outbox.
            transaction.setRollbackOnly();
        });
    }

    @Test
    void sellerWithBuyerPrivilegesStillGets409AndCannotChangeTheirOwnAuction() throws Exception {
        Fixture auction = auction("ACTIVE");
        Snapshot before = snapshot(auction.id());

        client.perform(request(auction.id(), auction.seller(), MINIMUM.toPlainString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUCTION_SELLER_CANNOT_BID"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void aFailureAfterOutboxInsertionRollsBackPriceLeaderCountVersionAndBid() throws Exception {
        Fixture auction = auction("ACTIVE");
        Snapshot before = snapshot(auction.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Test failure after BidPlaced insert");
        }).when(outbox).write(anyString(), anyString(), any());

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void parallelBidsAtTheSameMinimumAcceptOneAndRejectTheOtherWithoutALostUpdate() throws Exception {
        Fixture auction = auction("ACTIVE");
        List<UUID> bidders = List.of(UUID.randomUUID(), UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<MvcResult> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = bidders.stream().map(bidder -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing race start");
                return client.perform(request(auction.id(), bidder, MINIMUM.toPlainString())).andReturn();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 400);
        MvcResult accepted = results.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
        MvcResult refused = results.stream().filter(result -> result.getResponse().getStatus() == 400).findFirst().orElseThrow();
        JsonNode acceptedBody = JsonMapper.shared().readTree(accepted.getResponse().getContentAsString());
        JsonNode refusedBody = JsonMapper.shared().readTree(refused.getResponse().getContentAsString());
        UUID winner = UUID.fromString(acceptedBody.get("data").get("leaderBidderId").asString());
        assertThat(bidders).contains(winner);
        assertAccepted(auction.id(), winner, UUID.fromString(acceptedBody.get("data").get("bidId").asString()));
        assertThat(refusedBody.get("code").asString()).isEqualTo("AUCTION_BID_TOO_LOW");
        assertThat(refusedBody.get("minimum").decimalValue()).isEqualByComparingTo("1100000.00");
    }

    /** #149: thời gian được gia hạn dựa vào deadline DB, không dựa vào Clock của event. */
    @Test
    void lastMinuteAcceptedBidAddsFiveMinutesAndPreservesOriginalDeadline() throws Exception {
        Fixture auction = auctionEndingIn(2);
        Instant originalEnd = time(auction.id(), "original_end_at");
        Instant before = time(auction.id(), "end_at");

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isCreated());

        assertThat(time(auction.id(), "end_at")).isEqualTo(before.plus(Duration.ofMinutes(5)));
        assertThat(time(auction.id(), "original_end_at")).isEqualTo(originalEnd);
        assertThat(extensionCount(auction.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id = ?", Integer.class,
                auction.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class,
                auction.id().toString())).isEqualTo(1);
    }

    @Test
    void acceptedBidThirtyMinutesBeforeEndDoesNotExtend() throws Exception {
        Fixture auction = auctionEndingIn(30);
        Instant before = time(auction.id(), "end_at");

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isCreated());

        assertThat(time(auction.id(), "end_at")).isEqualTo(before);
        assertThat(time(auction.id(), "original_end_at")).isEqualTo(before);
        assertThat(extensionCount(auction.id())).isZero();
    }

    /** Cùng transaction để kiểm tra chính xác biên 5 phút và 5 phút cộng 1 microsecond. */
    @ParameterizedTest
    @CsvSource({"0, true", "1, false"})
    void extensionWindowIncludesExactBoundary(long extraMicros, boolean extended) {
        Fixture auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            jdbc.update("UPDATE auctions SET end_at=now()+interval '5 minutes' + ? * interval '1 microsecond', "
                    + "original_end_at=now()+interval '5 minutes' + ? * interval '1 microsecond' WHERE id=?",
                    extraMicros, extraMicros, auction.id());
            Instant before = time(auction.id(), "end_at");

            // Giữ cùng DB now() để kiểm tra biên; service #147 mở REQUIRES_NEW transaction.
            assertThat(auctions.acceptBid(auction.id(), bidder, MINIMUM, "***")).isEqualTo(1);
            context.getBean(AntiSnipingPolicy.class).extendAcceptedBid(auction.id(), bidder);

            assertThat(time(auction.id(), "end_at"))
                    .isEqualTo(extended ? before.plus(Duration.ofMinutes(5)) : before);
            assertThat(time(auction.id(), "original_end_at")).isEqualTo(before);
            assertThat(extensionCount(auction.id())).isEqualTo(extended ? 1 : 0);
            transaction.setRollbackOnly();
        });
    }

    @Test
    void finalExtensionIsClippedAtCapAndLaterAcceptedBidsCannotMoveIt() throws Exception {
        properties.setAntiSnipingMaxExtensionMinutes(12);
        Fixture auction = auctionEndingIn(2);
        jdbc.update("UPDATE auctions SET original_end_at=end_at-interval '10 minutes' WHERE id=?", auction.id());
        Instant originalEnd = time(auction.id(), "original_end_at");
        Instant cap = originalEnd.plus(Duration.ofMinutes(12));
        UUID bidder = UUID.randomUUID();

        client.perform(request(auction.id(), bidder, MINIMUM.toPlainString()))
                .andExpect(status().isCreated());
        assertThat(time(auction.id(), "end_at")).isEqualTo(cap);
        assertThat(extensionCount(auction.id())).isEqualTo(1);
        for (int next = 1; next <= 3; next++) {
            String amount = MINIMUM.add(new BigDecimal("50000.00").multiply(BigDecimal.valueOf(next)))
                    .toPlainString();
            client.perform(request(auction.id(), bidder, amount)).andExpect(status().isCreated());
            assertThat(time(auction.id(), "end_at")).isEqualTo(cap);
            assertThat(time(auction.id(), "original_end_at")).isEqualTo(originalEnd);
            assertThat(extensionCount(auction.id())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT bid_count FROM auctions WHERE id=?", Integer.class,
                auction.id())).isEqualTo(4);
    }

    @Test
    void loweringCapBelowExistingExtensionNeverShortensDeadline() throws Exception {
        properties.setAntiSnipingMaxExtensionMinutes(12);
        Fixture auction = auctionEndingIn(2);
        jdbc.update("UPDATE auctions SET original_end_at=end_at-interval '15 minutes', extension_count=3 WHERE id=?",
                auction.id());
        Instant before = time(auction.id(), "end_at");
        Instant originalEnd = time(auction.id(), "original_end_at");

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isCreated());

        assertThat(time(auction.id(), "end_at")).isEqualTo(before);
        assertThat(time(auction.id(), "original_end_at")).isEqualTo(originalEnd);
        assertThat(extensionCount(auction.id())).isEqualTo(3);
    }

    @Test
    void disabledPolicyLeavesLastMinuteDeadlineUnchanged() throws Exception {
        properties.setAntiSnipingEnabled(false);
        Fixture auction = auctionEndingIn(2);
        Instant before = time(auction.id(), "end_at");

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isCreated());

        assertThat(time(auction.id(), "end_at")).isEqualTo(before);
        assertThat(time(auction.id(), "original_end_at")).isEqualTo(before);
        assertThat(extensionCount(auction.id())).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false, 400, AUCTION_BID_TOO_LOW", "true, 409, AUCTION_SELLER_CANNOT_BID"})
    void refusedLastMinuteBidLeavesDeadlineAndAllOtherStateUnchanged(boolean sellerBid, int status,
                                                                     String errorCode) throws Exception {
        Fixture auction = auctionEndingIn(2);
        Snapshot before = snapshot(auction.id());
        UUID bidder = sellerBid ? auction.seller() : UUID.randomUUID();
        String amount = sellerBid ? MINIMUM.toPlainString() : "1020000.00";

        client.perform(request(auction.id(), bidder, amount))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status))
                .andExpect(jsonPath("$.code").value(errorCode));

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void failureAfterOutboxInsertionRollsBackLastMinuteExtensionTogetherWithBid() throws Exception {
        Fixture auction = auctionEndingIn(2);
        Snapshot before = snapshot(auction.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Test failure after anti-sniping extension and BidPlaced insert");
        }).when(outbox).write(anyString(), anyString(), any());

        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isInternalServerError());

        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void lowReputationBidReturns403WithoutConsumingKeyOrChangingAnyAuctionState() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        Fixture auction = auctionEndingIn(2);
        UUID bidder = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Snapshot before = snapshot(auction.id());
        when(eligibility.check(bidder, EligibilityAction.BID)).thenReturn(
                new EligibilityClient.Decision(bidder, EligibilityAction.BID, false, 39, "BASIC", 40,
                        "Reputation score 39 is below 40 required for BID", "reputation-policy-1"));
        client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUCTION_REPUTATION_TOO_LOW"))
                .andExpect(jsonPath("$.detail").value("Reputation score 39 is below 40 required for BID"));
        assertThat(snapshot(auction.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE idempotency_key=?", Long.class, key.toString())).isZero();
        doReturn(eligible(bidder)).when(eligibility).check(bidder, EligibilityAction.BID);
        client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void unavailableOrMalformedEligibilityLeavesBidDeadlineOutboxAndKeyUntouched(boolean malformed) throws Exception {
        properties.setReputationEnforcementEnabled(true);
        Fixture auction = auctionEndingIn(2);
        UUID bidder = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Snapshot before = snapshot(auction.id());
        if (malformed) {
            when(eligibility.check(bidder, EligibilityAction.BID)).thenReturn(
                    new EligibilityClient.Decision(UUID.randomUUID(), EligibilityAction.BID, true, 50, "NEW", 40,
                            "Wrong user", "reputation-policy-1"));
        } else {
            when(eligibility.check(bidder, EligibilityAction.BID)).thenThrow(new IllegalStateException("Fake timeout"));
        }
        client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUCTION_REPUTATION_UNAVAILABLE"));
        assertThat(snapshot(auction.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE idempotency_key=?", Long.class, key.toString())).isZero();
        doReturn(eligible(bidder)).when(eligibility).check(bidder, EligibilityAction.BID);
        client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                .andExpect(status().isCreated());
    }

    @Test
    void enforcementOffSkipsEligibilityEvenWhenItsClientWouldFail() throws Exception {
        Fixture auction = auction("ACTIVE");
        when(eligibility.check(any(), any())).thenThrow(new IllegalStateException("Client must not be called"));
        client.perform(request(auction.id(), UUID.randomUUID(), MINIMUM.toPlainString()))
                .andExpect(status().isCreated());
        verify(eligibility, never()).check(any(), any());
    }

    @Test
    void acceptedBidReplayBypassesLaterEligibilityOutageAndKeepsConflictChecks() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        Fixture auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        when(eligibility.check(bidder, EligibilityAction.BID)).thenReturn(eligible(bidder));
        MvcResult accepted = client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                .andExpect(status().isCreated()).andReturn();
        Snapshot before = snapshot(auction.id());
        reset(eligibility);
        when(eligibility.check(any(), any())).thenThrow(new IllegalStateException("Later outage"));
        MvcResult replay = client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(JsonMapper.shared().readTree(replay.getResponse().getContentAsString()).path("data"))
                .isEqualTo(JsonMapper.shared().readTree(accepted.getResponse().getContentAsString()).path("data"));
        client.perform(reputationRequest(auction.id(), bidder, key, "1100000"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_IDEMPOTENCY_KEY_CONFLICT"));
        client.perform(reputationRequest(auction.id(), UUID.randomUUID(), key, MINIMUM.toPlainString()))
                .andExpect(status().isConflict());
        verify(eligibility, never()).check(any(), any());
        assertThat(snapshot(auction.id())).isEqualTo(before);
    }

    @Test
    void aBidCommittedDuringEligibilityFailureIsReplayedWithoutASecondMutation() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        Fixture auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        CountDownLatch firstChecking = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(eligibility.check(bidder, EligibilityAction.BID)).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                firstChecking.countDown();
                if (!firstCommitted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit timed out");
                throw new IllegalStateException("Eligibility failed after the accepted bid committed");
            }
            return eligible(bidder);
        });
        try (var workers = Executors.newFixedThreadPool(1)) {
            var waiting = workers.submit(() -> client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                    .andReturn().getResponse().getStatus());
            assertThat(firstChecking.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                client.perform(reputationRequest(auction.id(), bidder, key, MINIMUM.toPlainString()))
                        .andExpect(status().isCreated());
            } finally {
                firstCommitted.countDown();
            }
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id=?", Long.class, auction.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='BidPlaced'",
                Long.class, auction.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT bid_count FROM auctions WHERE id=?", Integer.class, auction.id())).isEqualTo(1);
    }

    @Test
    void eligibilityDelayCannotUseAStaleTransactionTimestampToAcceptAnExpiredAuction() throws Exception {
        properties.setReputationEnforcementEnabled(true);
        Fixture auction = auction("ACTIVE");
        UUID bidder = UUID.randomUUID();
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch deadlinePassed = new CountDownLatch(1);
        when(eligibility.check(bidder, EligibilityAction.BID)).thenAnswer(call -> {
            checking.countDown();
            if (!deadlinePassed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Deadline transition timed out");
            return eligible(bidder);
        });
        try (var workers = Executors.newFixedThreadPool(1)) {
            var request = workers.submit(() -> client.perform(reputationRequest(auction.id(), bidder,
                    UUID.randomUUID(), MINIMUM.toPlainString())).andReturn().getResponse().getStatus());
            assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
            Snapshot expired;
            try {
                jdbc.update("UPDATE auctions SET end_at=clock_timestamp()-interval '1 millisecond' WHERE id=?", auction.id());
                expired = snapshot(auction.id());
            } finally {
                deadlinePassed.countDown();
            }
            assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(snapshot(auction.id())).isEqualTo(expired);
        }
    }

    private static EligibilityClient.Decision eligible(UUID bidder) {
        return new EligibilityClient.Decision(bidder, EligibilityAction.BID, true, 50, "NEW", 40,
                "Reputation score meets the BID minimum", "reputation-policy-1");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reputationRequest(
            UUID auction, UUID bidder, UUID key, String amount) {
        return post("/api/v1/auctions/{id}/bids", auction)
                .header("Authorization", "Bearer " + bidder)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private Fixture auctionEndingIn(int minutes) {
        Fixture auction = auction("ACTIVE");
        jdbc.update("UPDATE auctions SET end_at=now()+?*interval '1 minute', "
                + "original_end_at=now()+?*interval '1 minute' WHERE id=?", minutes, minutes, auction.id());
        return auction;
    }

    private Instant time(UUID auction, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM auctions WHERE id=?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(), auction);
    }

    private int extensionCount(UUID auction) {
        return jdbc.queryForObject("SELECT extension_count FROM auctions WHERE id=?", Integer.class, auction);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            UUID auction, UUID bidder, String amount) {
        // #146 ignores this header; sending one keeps the same HTTP tests valid after dependent #147 merges.
        return post("/api/v1/auctions/{id}/bids", auction)
                .header("Authorization", "Bearer " + bidder)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private Fixture auction(String status) {
        UUID id = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auctions (id, product_id, sku_id, seller_id, title, starting_price,
                    bid_increment, current_price, start_at, end_at, original_end_at, status)
                VALUES (?, ?, ?, ?, 'Bid placement integration fixture', 1000000.00, 50000.00, 1000000.00,
                    now()-interval '1 hour', now()+interval '1 hour', now()+interval '1 hour', CAST(? AS auction_status))
                """, id, UUID.randomUUID(), UUID.randomUUID(), seller, status);
        return new Fixture(id, seller);
    }

    private Map<String, Object> assertAccepted(UUID auction, UUID bidder, UUID bidId) {
        Map<String, Object> storedAuction = jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", auction);
        assertThat((BigDecimal) storedAuction.get("current_price")).isEqualByComparingTo(MINIMUM);
        assertThat(storedAuction.get("leader_bidder_id")).isEqualTo(bidder);
        assertThat(((Number) storedAuction.get("bid_count")).intValue()).isEqualTo(1);
        assertThat(((Number) storedAuction.get("version")).longValue()).isEqualTo(1);
        assertThat(storedAuction.get("updated_by")).isEqualTo(bidder);
        List<Map<String, Object>> storedBids = jdbc.queryForList("SELECT * FROM bids WHERE auction_id = ?", auction);
        assertThat(storedBids).hasSize(1);
        Map<String, Object> bid = storedBids.getFirst();
        assertThat(bid.get("id")).isEqualTo(bidId);
        assertThat(bid.get("auction_id")).isEqualTo(auction);
        assertThat(bid.get("bidder_id")).isEqualTo(bidder);
        assertThat((BigDecimal) bid.get("amount")).isEqualByComparingTo(MINIMUM);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'BidPlaced'",
                Long.class, auction.toString())).isEqualTo(1);
        return bid;
    }

    private Snapshot snapshot(UUID auction) {
        return new Snapshot(jdbc.queryForMap("SELECT * FROM auctions WHERE id = ?", auction),
                jdbc.queryForList("SELECT * FROM bids WHERE auction_id = ? ORDER BY id", auction),
                jdbc.queryForList("SELECT * FROM outbox WHERE aggregate_id = ? ORDER BY id", auction.toString()));
    }

    private record Fixture(UUID id, UUID seller) { }
    private record Snapshot(Map<String, Object> auction, List<Map<String, Object>> bids, List<Map<String, Object>> events) { }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {AuctionRepository.class, BidRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    @Import({BidService.class, BidController.class, BidHistoryQuery.class, AntiSnipingPolicy.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            String url = POSTGRES.getJdbcUrl();
            DataSource source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=auction_bid_placement", POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("auction_bid_placement")
                    .defaultSchema("auction_bid_placement").load().migrate();
            return source;
        }

        @Bean AuctionProperties auctionProperties() { return new AuctionProperties(); }
        @Bean EligibilityClient eligibilityClient() { return mock(EligibilityClient.class); }
        @Bean ReputationGuard reputationGuard(AuctionProperties properties, EligibilityClient client) {
            return new ReputationGuard(properties, client);
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(null); }
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(token)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("name", "Nguyen Van A").claim("roles", List.of("BUYER"))
                    .claim("privileges", List.of("AUCTION.BID")).build();
        }
        @Bean
        EventEnvelopeFactory envelopes(Clock clock) {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("146bid");
            return new EventEnvelopeFactory(tracer, clock);
        }
        @Bean
        OutboxWriter outbox(DataSource source, PlatformTransactionManager transactions) {
            return spy(new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions));
        }
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Auction.class.getPackageName(), Bid.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
}
