package com.group5.auctionservice.bidding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.group5.auctionservice.TestPostgres;
import com.group5.auctionservice.auction.*;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;

import io.micrometer.tracing.Tracer;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** PostgreSQL thật: admission, idempotency, xử lý trễ, rebalance và rollback toàn transaction. */
@SpringJUnitConfig(BidIdempotencyTest.JpaConfig.class)
class BidIdempotencyTest {
    private static final TestPostgres POSTGRES = new TestPostgres();

    static {
        POSTGRES.start();
    }

    private static final BigDecimal MINIMUM = new BigDecimal("1050000.00");
    @Autowired private BidService service;
    @Autowired private BidCommandConsumer consumer;
    @Autowired private IdempotentConsumer idempotent;
    @Autowired private Tracer tracer;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuctionProperties properties;
    @MockitoSpyBean private BidRepository bids;
    @MockitoSpyBean private AuctionRepository auctions;
    @MockitoSpyBean private OutboxWriter outbox;

    /** POST chỉ lưu QUEUED/command; consumer mới tăng giá và lưu kết quả của bidder. */
    @Test
    void acceptsWithoutChangingPriceThenAppliesCommandAtomically() {
        Fixture f = auction();
        var receipt = place(f, key(), MINIMUM);
        assertThat(receipt.created()).isTrue();
        assertThat(receipt.response().status()).isEqualTo("QUEUED");
        assertThat(price(f)).isEqualByComparingTo("1000000.00");
        assertThat(state(receipt)).isEqualTo("QUEUED");
        assertThat(command(receipt).aggregateId()).isEqualTo(f.id().toString());
        consume(receipt);
        assertThat(state(receipt)).isEqualTo("PLACED");
        var result = service.status(f.id(), receipt.response().bidId(), f.bidder());
        assertThat(result.currentPrice()).isEqualByComparingTo(MINIMUM);
        assertThat(result.leaderBidderId()).isEqualTo(f.bidder().id());
        assertThat(events(f, "BidPlaced")).isEqualTo(1);
    }

    /** UUID hoa/thường và số thập phân tương đương luôn replay biên nhận gốc kể cả sau kết thúc. */
    @Test
    void replayKeepsReceiptAfterProcessingAndAuctionEnds() {
        Fixture f = auction();
        String key = key();
        var first = place(f, key, MINIMUM);
        consume(first);
        jdbc().update("UPDATE auctions SET status='ENDED' WHERE id=?", f.id());
        var replay = place(f, key.toUpperCase(Locale.ROOT), new BigDecimal("1050000"));
        assertThat(replay.created()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(events(f, "BidRequested")).isEqualTo(1);
        assertThat(events(f, "BidPlaced")).isEqualTo(1);
    }

    /** Một key không được đổi giá, đổi bidder hoặc đổi auction để lấy lại kết quả riêng tư. */
    @Test
    void rejectsKeyReuseAcrossAmountBidderOrAuction() {
        Fixture f = auction();
        String key = key();
        place(f, key, MINIMUM);
        assertConflict(() -> place(f, key, MINIMUM.add(BigDecimal.ONE)));
        assertConflict(() -> service.place(f.id(), key, new PlaceBidRequest(MINIMUM), buyer()));
        assertConflict(
                () -> service.place(auction().id(), key, new PlaceBidRequest(MINIMUM), f.bidder()));
        assertThat(events(f, "BidRequested")).isEqualTo(1);
    }

    /** Hai POST cùng key tranh UNIQUE: chỉ một row và một command, không tăng giá tại POST. */
    @Test
    void concurrentDuplicateAdmissionsKeepOneDurableRequest() throws Exception {
        Fixture f = auction();
        String key = key();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger misses = new AtomicInteger();
        var answer = mockingDetails(bids).getMockCreationSettings().getDefaultAnswer();
        doAnswer(
                        i -> {
                            Optional<?> value = (Optional<?>) answer.answer(i);
                            if (value.isEmpty()
                                    && org.springframework.transaction.support.TransactionSynchronizationManager
                                            .isActualTransactionActive()
                                    && !org.springframework.transaction.support.TransactionSynchronizationManager
                                            .isCurrentTransactionReadOnly()
                                    && misses.incrementAndGet() <= 2)
                                barrier.await(10, TimeUnit.SECONDS);
                            return value;
                        })
                .when(bids)
                .findByIdempotencyKey(key);
        var results = race(() -> place(f, key, MINIMUM), () -> place(f, key, MINIMUM));
        assertThat(results)
                .extracting(BidService.PlacementResult::created)
                .containsExactlyInAnyOrder(true, false);
        assertThat(results.get(0).response()).isEqualTo(results.get(1).response());
        assertThat(events(f, "BidRequested")).isEqualTo(1);
        assertThat(price(f)).isEqualByComparingTo("1000000.00");
    }

    /** Lỗi ghi command rollback cả QUEUED và key; client có thể gửi lại cùng key. */
    @Test
    void enqueueOutboxFailureRollsBackRowAndKey() {
        Fixture f = auction();
        String key = key();
        doAnswer(
                        i -> {
                            i.callRealMethod();
                            throw new IllegalStateException("enqueue failed");
                        })
                .when(outbox)
                .write(anyString(), anyString(), any());
        assertThatThrownBy(() -> place(f, key, MINIMUM)).hasMessage("enqueue failed");
        assertThat(bids.findByIdempotencyKey(key)).isEmpty();
        assertThat(events(f, "BidRequested")).isZero();
        doCallRealMethod().when(outbox).write(anyString(), anyString(), any());
        assertThat(place(f, key, MINIMUM).created()).isTrue();
    }

    /** Giá thấp vẫn nhận 202, sau đó REJECTED bền vững với minimum tại thời điểm xét. */
    @Test
    void tooLowBidConsumesItsKeyAndRecordsReasonInsteadOfThrowingFromPost() {
        Fixture f = auction();
        String key = key();
        var receipt = place(f, key, new BigDecimal("1020000"));
        consume(receipt);
        var result = service.status(f.id(), receipt.response().bidId(), f.bidder());
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reason()).isEqualTo("AUCTION_BID_TOO_LOW");
        assertThat(result.minimum()).isEqualByComparingTo(MINIMUM);
        assertThat(events(f, "BidPlaced")).isZero();
        assertConflict(() -> place(f, key, MINIMUM));
    }

    /** Delivery trùng và consumer khởi động lại không tăng giá hay phát BidPlaced lần hai. */
    @Test
    void duplicateDeliveryAndRestartApplyOnce() {
        Fixture f = auction();
        var receipt = place(f, key(), MINIMUM);
        var command = command(receipt);
        consumer.onEvent(command);
        new BidCommandConsumer(idempotent, service, JsonMapper.shared(), tracer).onEvent(command);
        assertThat(events(f, "BidPlaced")).isEqualTo(1);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT count(*) FROM processed_events WHERE event_id=? AND"
                                            + " consumer=?",
                                        Integer.class,
                                        command.eventId(),
                                        BidCommandConsumer.CONSUMER))
                .isEqualTo(1);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT bid_count FROM auctions WHERE id=?",
                                        Integer.class,
                                        f.id()))
                .isEqualTo(1);
    }

    /** Request trước deadline vẫn được áp dụng khi consumer chậm; lifecycle chờ đến khi drain. */
    @Test
    void lateConsumptionUsesRequestedAtAndLifecycleWaitsForEligibleQueue() {
        properties.setAntiSnipingEnabled(false);
        try {
            Fixture f = auction();
            var receipt = place(f, key(), MINIMUM);
            jdbc().update(
                            "UPDATE auctions SET end_at=?::timestamptz+interval '1 microsecond',"
                                + " original_end_at=?::timestamptz+interval '1 microsecond' WHERE"
                                + " id=?",
                            receipt.response().requestedAt().toString(),
                            receipt.response().requestedAt().toString(),
                            f.id());
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            t -> {
                                assertThat(auctions.hasEligibleQueuedBids(f.id())).isTrue();
                                assertThat(
                                                auctions.lockAuctionsDueToEnd(
                                                        Instant.now().plusSeconds(1), 100))
                                        .doesNotContain(f.id());
                            });
            consume(receipt);
            assertThat(state(receipt)).isEqualTo("PLACED");
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            t ->
                                    assertThat(
                                                    auctions.lockAuctionsDueToEnd(
                                                            Instant.now().plusSeconds(1), 100))
                                            .contains(f.id()));
        } finally {
            properties.setAntiSnipingEnabled(true);
        }
    }

    /** Anti-sniping dùng requestedAt thay vì now của consumer sau deadline cũ. */
    @Test
    void delayedLastSecondBidExtendsFromOriginalDeadline() {
        Fixture f = auction();
        var receipt = place(f, key(), MINIMUM);
        Instant end = receipt.response().requestedAt().plusSeconds(1);
        jdbc().update(
                        "UPDATE auctions SET end_at=?::timestamptz, original_end_at=?::timestamptz"
                            + " WHERE id=?",
                        end.toString(),
                        end.toString(),
                        f.id());
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .until(() -> Instant.now().isAfter(end));
        consume(receipt);
        assertThat(
                        jdbc().<Instant>queryForObject(
                                        "SELECT end_at FROM auctions WHERE id=?",
                                        (r, n) -> r.getObject(1, OffsetDateTime.class).toInstant(),
                                        f.id()))
                .isEqualTo(end.plusSeconds(300));
        assertThat(state(receipt)).isEqualTo("PLACED");
    }

    /** Relay giao command thứ hai trước vẫn xử lý các row đã commit theo thứ tự nhận. */
    @Test
    void reverseCommandDeliveryDrainsEarlierRequestsBeforeHigherBid() {
        Fixture f = auction();
        var low = place(f, key(), MINIMUM);
        var high = place(f, key(), MINIMUM.add(new BigDecimal("50000")));
        consume(high);
        consume(low);
        assertThat(state(low)).isEqualTo("PLACED");
        assertThat(state(high)).isEqualTo("PLACED");
        assertThat(events(f, "BidPlaced")).isEqualTo(2);
        assertThat(price(f)).isEqualByComparingTo("1100000.00");
    }

    /** Hai consumer khi rebalance không thể cùng thắng một bước giá bằng nhau. */
    @Test
    void competingConsumersProduceOnePlacedBidPerPriceStep() throws Exception {
        Fixture f = auction();
        var one = place(f, key(), MINIMUM);
        var two = place(f, key(), MINIMUM);
        race(
                () -> {
                    consume(one);
                    return true;
                },
                () -> {
                    consume(two);
                    return true;
                });
        assertThat(List.of(state(one), state(two))).containsExactlyInAnyOrder("PLACED", "REJECTED");
        assertThat(events(f, "BidPlaced")).isEqualTo(1);
        assertThat(price(f)).isEqualByComparingTo(MINIMUM);
    }

    /** Lỗi sau BidPlaced rollback marker, giá, extension và bid; retry cùng event thành công. */
    @Test
    void processingFailureRollsBackMarkerPriceDeadlineAndResult() {
        Fixture f = auction();
        jdbc().update(
                        "UPDATE auctions SET end_at=now()+interval '2 minutes',"
                            + " original_end_at=now()+interval '2 minutes' WHERE id=?",
                        f.id());
        var receipt = place(f, key(), MINIMUM);
        var command = command(receipt);
        var before = jdbc().queryForMap("SELECT * FROM auctions WHERE id=?", f.id());
        doAnswer(
                        i -> {
                            Object result = i.callRealMethod();
                            if (Topics.AUCTION.equals(i.getArgument(1)))
                                throw new IllegalStateException("apply failed");
                            return result;
                        })
                .when(outbox)
                .write(anyString(), anyString(), any());
        assertThatThrownBy(() -> consumer.onEvent(command)).hasMessage("apply failed");
        assertThat(state(receipt)).isEqualTo("QUEUED");
        assertThat(jdbc().queryForMap("SELECT * FROM auctions WHERE id=?", f.id()))
                .isEqualTo(before);
        assertThat(events(f, "BidPlaced")).isZero();
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT count(*) FROM processed_events WHERE event_id=?",
                                        Integer.class,
                                        command.eventId()))
                .isZero();
        doCallRealMethod().when(outbox).write(anyString(), anyString(), any());
        consume(receipt);
        assertThat(state(receipt)).isEqualTo("PLACED");
    }

    /** Status không tiết lộ bid của người khác hoặc bidId thuộc auction khác. */
    @Test
    void statusHidesOtherBidderAndOtherAuction() {
        Fixture f = auction();
        var receipt = place(f, key(), MINIMUM);
        for (Runnable action :
                List.<Runnable>of(
                        () -> service.status(f.id(), receipt.response().bidId(), buyer()),
                        () ->
                                service.status(
                                        auction().id(), receipt.response().bidId(), f.bidder()))) {
            assertThatThrownBy(action::run)
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo("AUCTION_BID_NOT_FOUND");
        }
    }

    /** Scheduler không đóng phiên khi admission chưa commit dù SELECT không thấy QUEUED. */
    @Test
    void lifecycleAdmissionGuardSeesInFlightPostThatIsNotYetVisible() throws Exception {
        Fixture f = auction();
        CountDownLatch inserted = new CountDownLatch(1), commit = new CountDownLatch(1);
        var answer = mockingDetails(bids).getMockCreationSettings().getDefaultAnswer();
        doAnswer(
                        i -> {
                            Object saved = answer.answer(i);
                            inserted.countDown();
                            if (!commit.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("Missing admission release");
                            return saved;
                        })
                .when(bids)
                .saveAndFlush(any(Bid.class));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var posting = worker.submit(() -> place(f, key(), MINIMUM));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(transactions)
                        .executeWithoutResult(
                                t -> {
                                    assertThat(auctions.hasEligibleQueuedBids(f.id())).isFalse();
                                    assertThat(auctions.tryCloseBidAdmission(f.id())).isFalse();
                                });
            } finally {
                commit.countDown();
            }
            var receipt = posting.get(15, TimeUnit.SECONDS);
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            t -> {
                                assertThat(auctions.tryCloseBidAdmission(f.id())).isTrue();
                                assertThat(auctions.hasEligibleQueuedBids(f.id())).isTrue();
                            });
            consume(receipt);
            assertThat(state(receipt)).isEqualTo("PLACED");
        }
    }

    /** Winner job chờ QUEUED và không bao giờ chọn request REJECTED dù số tiền cao hơn. */
    @Test
    void winnerWaitsForQueueAndIgnoresRejectedRequests() {
        Fixture f = auction();
        var receipt = place(f, key(), MINIMUM);
        jdbc().update("UPDATE auctions SET status='ENDED' WHERE id=?", f.id());
        var settlement =
                new com.group5.auctionservice.settlement.SettlementService(
                        auctions, bids, new AuctionStateMachine(), properties, envelopes(), outbox);
        new TransactionTemplate(transactions).executeWithoutResult(t -> settlement.settle(f.id()));
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT status::text FROM auctions WHERE id=?",
                                        String.class,
                                        f.id()))
                .isEqualTo("ENDED");
        jdbc().update("UPDATE auctions SET status='ACTIVE' WHERE id=?", f.id());
        consume(receipt);
        jdbc().update(
                        "INSERT INTO"
                            + " bids(auction_id,bidder_id,bidder_name,amount,status,idempotency_key,requested_at,reject_reason)"
                            + " VALUES(?,?,'***',9999999,'REJECTED',?,now(),'AUCTION_NOT_ACTIVE')",
                        f.id(),
                        UUID.randomUUID(),
                        key());
        jdbc().update("UPDATE auctions SET status='ENDED' WHERE id=?", f.id());
        new TransactionTemplate(transactions).executeWithoutResult(t -> settlement.settle(f.id()));
        var result =
                jdbc().queryForMap(
                                "SELECT winner_id,final_price,status::text FROM auctions WHERE"
                                    + " id=?",
                                f.id());
        assertThat(result.get("winner_id")).isEqualTo(f.bidder().id());
        assertThat((BigDecimal) result.get("final_price")).isEqualByComparingTo(MINIMUM);
        assertThat(result.get("status")).isEqualTo("WON");
        assertThat(events(f, "AuctionWon")).isEqualTo(1);
    }

    /** Dùng factory thật từ application context để winner và bid cùng ghi envelope chuẩn. */
    @Autowired private EventEnvelopeFactory eventEnvelopes;

    private EventEnvelopeFactory envelopes() {
        return eventEnvelopes;
    }

    /** Consumer không vượt mặt request đến trước nhưng transaction nhận request còn chưa commit. */
    @Test
    void processingWaitsForEarlierAdmissionBeforeDrainingLaterCommand() throws Exception {
        Fixture f = auction();
        CountDownLatch lowerInserted = new CountDownLatch(1), releaseLower = new CountDownLatch(1);
        var answer = mockingDetails(bids).getMockCreationSettings().getDefaultAnswer();
        doAnswer(
                        i -> {
                            Bid bid = i.getArgument(0);
                            Object saved = answer.answer(i);
                            if (bid.getAmount().compareTo(MINIMUM) == 0) {
                                lowerInserted.countDown();
                                if (!releaseLower.await(15, TimeUnit.SECONDS))
                                    throw new IllegalStateException(
                                            "Missing earlier admission release");
                            }
                            return saved;
                        })
                .when(bids)
                .saveAndFlush(any(Bid.class));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var lowFuture = workers.submit(() -> place(f, key(), MINIMUM));
            try {
                assertThat(lowerInserted.await(10, TimeUnit.SECONDS)).isTrue();
                var high = place(f, key(), MINIMUM.add(new BigDecimal("50000")));
                CountDownLatch processingStarted = new CountDownLatch(1);
                var processing =
                        workers.submit(
                                () -> {
                                    processingStarted.countDown();
                                    consume(high);
                                    return true;
                                });
                assertThat(processingStarted.await(10, TimeUnit.SECONDS)).isTrue();
                // Chưa thể xử lý high trong khi row low chưa commit và vẫn giữ shared admission
                // lock.
                assertThatThrownBy(() -> processing.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                releaseLower.countDown();
                var low = lowFuture.get(15, TimeUnit.SECONDS);
                processing.get(15, TimeUnit.SECONDS);
                assertThat(state(low)).isEqualTo("PLACED");
                assertThat(state(high)).isEqualTo("PLACED");
                assertThat(events(f, "BidPlaced")).isEqualTo(2);
                assertThat(price(f)).isEqualByComparingTo("1100000");
            } finally {
                releaseLower.countDown();
            }
        }
    }

    /**
     * Conditional SQL vẫn tự bảo vệ một bước giá ngay cả khi caller không dùng advisory lock
     * consumer.
     */
    @Test
    void conditionalUpdateAloneAllowsOneWinnerForSamePrice() throws Exception {
        Fixture f = auction();
        CyclicBarrier start = new CyclicBarrier(2);
        Instant requested = Instant.now();
        Callable<Integer> attempt =
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        t -> {
                                            try {
                                                start.await(10, TimeUnit.SECONDS);
                                            } catch (Exception failure) {
                                                throw new IllegalStateException(failure);
                                            }
                                            return auctions.acceptQueuedBid(
                                                    f.id(),
                                                    UUID.randomUUID(),
                                                    MINIMUM,
                                                    "***",
                                                    requested);
                                        });
        assertThat(race(attempt, attempt)).containsExactlyInAnyOrder(1, 0);
        assertThat(price(f)).isEqualByComparingTo(MINIMUM);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT bid_count FROM auctions WHERE id=?",
                                        Integer.class,
                                        f.id()))
                .isEqualTo(1);
    }

    /**
     * Sau admission barrier, POST mới vẫn trả biên nhận trong lúc consumer đang giữ row lock giá.
     */
    @Test
    void newAdmissionDoesNotWaitForConsumerPriceTransaction() throws Exception {
        Fixture f = auction();
        var first = place(f, key(), MINIMUM);
        CountDownLatch priceHeld = new CountDownLatch(1), releasePrice = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            Object result = i.callRealMethod();
                            if (Topics.AUCTION.equals(i.getArgument(1))) {
                                priceHeld.countDown();
                                if (!releasePrice.await(15, TimeUnit.SECONDS))
                                    throw new IllegalStateException(
                                            "Missing price transaction release");
                            }
                            return result;
                        })
                .when(outbox)
                .write(anyString(), anyString(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var applying =
                    workers.submit(
                            () -> {
                                consume(first);
                                return true;
                            });
            BidService.PlacementResult second;
            try {
                assertThat(priceHeld.await(10, TimeUnit.SECONDS)).isTrue();
                var posting =
                        workers.submit(() -> place(f, key(), MINIMUM.add(new BigDecimal("50000"))));
                second = posting.get(2, TimeUnit.SECONDS);
                assertThat(state(second)).isEqualTo("QUEUED");
                assertThat(applying).isNotDone();
            } finally {
                releasePrice.countDown();
            }
            applying.get(15, TimeUnit.SECONDS);
            doCallRealMethod().when(outbox).write(anyString(), anyString(), any());
            consume(second);
            assertThat(state(second)).isEqualTo("PLACED");
        }
    }

    /** Dùng hai transaction thật để đua admission/consumer, không thay thế repository bằng fake. */
    private static <T> List<T> race(Callable<T> one, Callable<T> two) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(one);
            var b = pool.submit(two);
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    /** Kiểm tra cùng code conflict cho mọi cách tái sử dụng sai key. */
    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .extracting("errorCode")
                .isEqualTo("AUCTION_IDEMPOTENCY_KEY_CONFLICT");
    }

    /** Gọi đúng service nhận request; không xử lý command tự động trong helper này. */
    private BidService.PlacementResult place(Fixture f, String key, BigDecimal amount) {
        return service.place(f.id(), key, new PlaceBidRequest(amount), f.bidder());
    }

    /** Đọc envelope do OutboxWriter thật lưu để test cũng kiểm tra serialization payload. */
    private EventEnvelope<JsonNode> command(BidService.PlacementResult result) {
        String payload =
                jdbc().queryForObject(
                                "SELECT payload::text FROM outbox WHERE event_type='BidRequested'"
                                    + " AND payload->'payload'->>'bidId'=?",
                                String.class,
                                result.response().bidId().toString());
        return JsonMapper.shared()
                .readValue(
                        payload,
                        JsonMapper.shared()
                                .getTypeFactory()
                                .constructParametricType(EventEnvelope.class, JsonNode.class));
    }

    /** Giao command qua consumer thật để marker và handler dùng cùng transaction. */
    private void consume(BidService.PlacementResult receipt) {
        consumer.onEvent(command(receipt));
    }

    /** Đọc trạng thái DB đã commit, không dùng entity cache của test. */
    private String state(BidService.PlacementResult result) {
        return jdbc().queryForObject(
                        "SELECT status::text FROM bids WHERE id=?",
                        String.class,
                        result.response().bidId());
    }

    /** Đếm event theo auction để phát hiện publish trùng. */
    private long events(Fixture f, String type) {
        return jdbc().queryForObject(
                        "SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type=?",
                        Long.class,
                        f.id().toString(),
                        type);
    }

    /** Snapshot giá trong DB dùng cho kiểm tra POST không cập nhật giá. */
    private BigDecimal price(Fixture f) {
        return jdbc().queryForObject(
                        "SELECT current_price FROM auctions WHERE id=?", BigDecimal.class, f.id());
    }

    /** Tạo auction ACTIVE riêng cho từng test để không chia sẻ giá hoặc idempotency key. */
    private Fixture auction() {
        UUID id = UUID.randomUUID();
        jdbc().update(
                        """
INSERT INTO auctions(id,product_id,sku_id,seller_id,title,image_url,starting_price,bid_increment,current_price,start_at,end_at,original_end_at,status)
VALUES(?,?,?,?, 'Queue integration','https://example.test/item.png',1000000,50000,1000000,now()-interval '1 hour',now()+interval '1 hour',now()+interval '1 hour','ACTIVE')
""",
                        id,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
        return new Fixture(id, buyer());
    }

    /** Key hợp lệ độc lập cho mỗi yêu cầu. */
    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** User fixture chỉ có quyền đặt bid. */
    private static CurrentUser buyer() {
        return new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("AUCTION.BID"));
    }

    /** Truy vấn PostgreSQL cùng datasource đã chạy Flyway. */
    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Fixture(UUID id, CurrentUser bidder) {}

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {AuctionRepository.class, BidRepository.class})
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @Import({BidService.class, AntiSnipingPolicy.class, BidCommandConsumer.class})
    static class JpaConfig {
        @Bean
        DataSource dataSource() {
            String jdbcUrl = POSTGRES.getJdbcUrl();
            DataSource source =
                    new DriverManagerDataSource(
                            jdbcUrl
                                    + (jdbcUrl.contains("?") ? "&" : "?")
                                    + "currentSchema=auction_bid_idempotency",
                            POSTGRES.getUsername(),
                            POSTGRES.getPassword());
            Flyway.configure()
                    .dataSource(source)
                    .schemas("auction_bid_idempotency")
                    .defaultSchema("auction_bid_idempotency")
                    .load()
                    .migrate();
            return source;
        }

        /** Mapper dùng đúng serialization của Kafka envelope và outbox. */
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.shared();
        }

        /** Marker dùng cùng JpaTransactionManager với giá và bid. */
        @Bean
        com.group5.common_outbox.IdempotentConsumer idempotent(
                EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new com.group5.common_outbox.IdempotentConsumer(
                    org.springframework.orm.jpa.SharedEntityManagerCreator
                            .createSharedEntityManager(factory),
                    new org.springframework.transaction.support.TransactionTemplate(transactions));
        }

        @Bean
        AuctionProperties auctionProperties() {
            AuctionProperties properties = new AuctionProperties();
            properties.setReputationEnforcementEnabled(false);
            return properties;
        }

        /** Fixture queue không gọi rating-service; các trường hợp chặn reputation có test HTTP riêng. */
        @Bean
        com.group5.auctionservice.reputation.ReputationGuard reputationGuard(AuctionProperties properties) {
            return new com.group5.auctionservice.reputation.ReputationGuard(
                    properties,
                    (user, action) -> {
                        throw new IllegalStateException("Enforcement is disabled in this fixture");
                    });
        }

        @Bean
        AuditorAware<UUID> auditor() {
            return new CurrentUserAuditor();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        EventEnvelopeFactory envelopes(Clock clock, Tracer tracer) {
            return new EventEnvelopeFactory(tracer, clock);
        }

        /** Tracer fixture giữ span cho consumer tạo BidPlaced giống listener thật. */
        @Bean
        Tracer tracer() {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("253bid");
            return tracer;
        }

        @Bean
        OutboxWriter outbox(DataSource source, PlatformTransactionManager transactions) {
            return new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactions);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Auction.class.getPackageName(), Bid.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "validate",
                            "hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
