package com.group5.auctionservice.auction;

import com.group5.auctionservice.AuctionServiceApplication;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.group5.auctionservice.TestPostgres;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AuctionSchedulerIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static ConfigurableApplicationContext first;
    private static ConfigurableApplicationContext second;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startInstances() {
        POSTGRES.start();
        first = startInstance();
        second = startInstance();
        jdbc = first.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopInstances() {
        if (second != null) second.close();
        if (first != null) first.close();
        POSTGRES.stop();
    }

    @Test
    void scheduledTickStartsAndEndsWithinTenSecondsAndLeavesIneligibleAuctionsAlone() {
        Instant deadline = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        UUID scheduled = seed("SCHEDULED", deadline, deadline.plusSeconds(3600), deadline.plusSeconds(3600), false);
        UUID active = seed("ACTIVE", deadline.minusSeconds(3600), deadline, deadline, false);
        UUID created = seed("CREATED", deadline.minusSeconds(60), deadline, deadline, false);
        UUID extended = seed("ACTIVE", deadline.minusSeconds(3600), deadline.plusSeconds(3600), deadline, false);
        UUID deleted = seed("SCHEDULED", deadline, deadline.plusSeconds(3600), deadline.plusSeconds(3600), true);
        UUID cancelled = seed("CANCELLED", deadline.minusSeconds(3600), deadline, deadline, false);

        await().atMost(Duration.ofSeconds(13)).untilAsserted(() -> {
            assertThat(status(scheduled)).isEqualTo("ACTIVE");
            assertThat(status(active)).isEqualTo("ENDED");
        });
        assertEvent(scheduled, "AuctionStarted", "startedAt", deadline, deadline.plusSeconds(3600));
        assertEvent(active, "AuctionEnded", "endedAt", deadline, deadline);
        assertUnchanged(created, "CREATED");
        assertUnchanged(extended, "ACTIVE");
        assertUnchanged(deleted, "SCHEDULED");
        assertUnchanged(cancelled, "CANCELLED");
    }

    @Test
    void lifecycleTicksWhileBothDefaultSchedulersAreOccupied() throws InterruptedException {
        var occupied = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Runnable blockDefaultScheduler = () -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        for (var instance : new ConfigurableApplicationContext[]{first, second}) {
            instance.getBean("taskScheduler", TaskScheduler.class).schedule(blockDefaultScheduler, Instant.now());
        }
        try {
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
            Instant due = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
            UUID id = seed("ACTIVE", due.minusSeconds(3600), due, due, false);
            await().atMost(Duration.ofSeconds(13)).untilAsserted(() ->
                    assertThat(status(id)).isEqualTo("ENDED"));
            assertEvent(id, "AuctionEnded", "endedAt", due, due);
        } finally {
            release.countDown();
        }
    }

    @Test
    void twoInstancesLeaveExactlyOneEndEventForEachOfFiftyAuctions() throws Exception {
        String marker = UUID.randomUUID().toString();
        String where = "title = ?";
        var lock = await().atMost(Duration.ofSeconds(5)).until(() -> first.getBean(LockProvider.class).lock(
                        new LockConfiguration(Instant.now(), "auctionLifecycle", Duration.ofSeconds(20), Duration.ZERO)),
                candidate -> candidate.isPresent()).orElseThrow();
        try {
            seedBatch(marker, 50, Instant.now().minusSeconds(60));
            second.getBean(AuctionScheduler.class).tick();
            assertThat(countAuctions(where + " AND status = 'ACTIVE'", marker)).isEqualTo(50);
            assertThat(countBatchEvents(marker, false)).isZero();
        } finally {
            lock.unlock();
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> first.getBean(AuctionScheduler.class).tick());
            var two = executor.submit(() -> second.getBean(AuctionScheduler.class).tick());
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }

        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                assertThat(countAuctions(where + " AND status = 'ENDED'", marker)).isEqualTo(50));
        first.getBean(AuctionScheduler.class).tick();
        second.getBean(AuctionScheduler.class).tick();
        assertThat(countBatchEvents(marker, false)).isEqualTo(50);
        assertThat(countBatchEvents(marker, true)).isEqualTo(50);
    }

    @Test
    void outboxFailureRollsBackStatusAndAllowsRetry() {
        Instant end = Instant.now().plusSeconds(2);
        UUID id = seed("ACTIVE", end.minusSeconds(3600), end, end, false);
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT test_scheduler_outbox_failure "
                + "CHECK (aggregate_id <> '" + id + "') NOT VALID");
        try {
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThatThrownBy(() -> first.getBean(AuctionScheduler.class).tick())
                            .isInstanceOf(DataIntegrityViolationException.class));
            assertUnchanged(id, "ACTIVE");
        } finally {
            jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT test_scheduler_outbox_failure");
        }
        first.getBean(AuctionScheduler.class).tick();
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
            assertThat(status(id)).isEqualTo("ENDED");
            assertThat(events(id)).isEqualTo(1);
        });
    }

    @Test
    void overdueScheduledAuctionStillStartsBeforeItEnds() {
        Instant end = Instant.now().minusSeconds(5);
        UUID id = seed("SCHEDULED", end.minusSeconds(3600), end, end, false);
        first.getBean(AuctionScheduler.class).tick();
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("ENDED"));
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox WHERE aggregate_id = ? ORDER BY occurred_at, id",
                String.class, id.toString())).containsExactly("AuctionStarted", "AuctionEnded");
    }

    @Test
    void closingSkipsAnAuctionWhileItsDeadlineIsBeingExtended() {
        Instant originalEnd = Instant.now().plusSeconds(2);
        Instant effectiveEnd = originalEnd.plusSeconds(3600);
        UUID id = seed("ACTIVE", originalEnd.minusSeconds(3600), originalEnd, originalEnd, false);
        var transaction = new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
        var otherTransaction = new TransactionTemplate(second.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE auctions SET end_at = ? WHERE id = ?", utc(effectiveEnd), id);
            await().atMost(Duration.ofSeconds(5)).until(() -> !Instant.now().isBefore(originalEnd));
            var lockedIds = otherTransaction.execute(otherStatus -> second.getBean(AuctionRepository.class)
                    .lockAuctionsDueToEnd(Instant.now(), 100));
            assertThat(lockedIds).doesNotContain(id);
        });
        second.getBean(AuctionScheduler.class).tick();
        assertUnchanged(id, "ACTIVE");
    }

    @Test
    void closingRepositoryQueryUsesExistingIndexWithFiveThousandAuctions() throws Exception {
        Instant cutoff = Instant.now().plusSeconds(86_400);
        jdbc.execute("TRUNCATE TABLE auctions, bids, auction_watchers, outbox");
        seedBatch("closing-index-fixture", 5000, cutoff.minusSeconds(50));
        jdbc.execute("ANALYZE auctions");
        Query query = AuctionRepository.class.getMethod("lockAuctionsDueToEnd", Instant.class, int.class)
                .getAnnotation(Query.class);
        var named = new NamedParameterJdbcTemplate(jdbc);
        var plan = named.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + query.value(),
                Map.of("now", utc(cutoff), "limit", 100), String.class);
        assertThat(plan).anyMatch(line -> line.contains("Index Scan using idx_auctions_closing"));
    }

    private static ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(AuctionServiceApplication.class).web(WebApplicationType.NONE).run(
                "--spring.config.location=optional:classpath:/auction-scheduler-test.properties",
                "--spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false",
                "--spring.cloud.discovery.enabled=false", "--eureka.client.enabled=false",
                "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false",
                "--spring.jpa.open-in-view=false", "--management.tracing.export.zipkin.enabled=false",
                "--nexus.outbox.relay.enabled=false", "--nexus.auction.settlement.enabled=false");
    }

    private UUID seed(String status, Instant start, Instant end, Instant originalEnd, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, starting_price,
                    bid_increment, current_price, status, start_at, end_at, original_end_at, deleted_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'scheduler fixture', 100,
                    10, 100, ?::auction_status, ?, ?, ?, CASE WHEN ? THEN now() END)
                RETURNING id
                """, UUID.class, status, utc(start), utc(end), utc(originalEnd), deleted);
    }

    private void seedBatch(String marker, int count, Instant firstEnd) {
        jdbc.update("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, starting_price,
                    bid_increment, current_price, status, start_at, end_at, original_end_at)
                SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, 100, 10, 100,
                    'ACTIVE', deadline - interval '1 hour', deadline, deadline
                FROM generate_series(0, ?) n
                CROSS JOIN LATERAL (SELECT ?::timestamptz + n * interval '1 second' AS deadline) due
                """, marker, count - 1, utc(firstEnd));
    }

    private void assertEvent(UUID id, String type, String timeField, Instant due, Instant endAt) {
        assertThat(events(id)).isEqualTo(1);
        var event = JsonMapper.shared().readTree(jdbc.queryForObject("""
                SELECT payload::text FROM outbox
                WHERE aggregate_id = ? AND event_type = ? AND topic = 'nexus.auction.events' AND event_version = 1
                """, String.class, id.toString(), type));
        JsonNode payload = event.path("payload");
        assertThat(event.path("aggregateId").asText()).isEqualTo(id.toString());
        assertThat(event.path("eventType").asText()).isEqualTo(type);
        assertThat(event.path("traceId").asText()).matches("[0-9a-f]{16}|[0-9a-f]{32}");
        assertThat(payload.path("auctionId").asText()).isEqualTo(id.toString());
        for (String idField : new String[]{"sellerId", "productId", "skuId"}) {
            assertThat(UUID.fromString(payload.path(idField).asText())).isNotNull();
        }
        assertThat(Instant.parse(payload.path("endAt").asText())).isEqualTo(endAt);
        assertThat(payload.path(timeField).asText()).isEqualTo(event.path("occurredAt").asText());
        assertThat(Instant.parse(payload.path(timeField).asText())).isBetween(due, due.plusSeconds(10));
    }

    private void assertUnchanged(UUID id, String originalStatus) {
        assertThat(status(id)).isEqualTo(originalStatus);
        assertThat(jdbc.queryForObject("SELECT version FROM auctions WHERE id = ?", Long.class, id)).isZero();
        assertThat(events(id)).isZero();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM auctions WHERE id = ?", String.class, id);
    }

    private int events(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, id.toString());
    }

    private int countAuctions(String predicate, String marker) {
        return jdbc.queryForObject("SELECT count(*) FROM auctions WHERE " + predicate, Integer.class, marker);
    }

    private int countBatchEvents(String marker, boolean distinct) {
        String expression = distinct ? "DISTINCT o.aggregate_id" : "*";
        return jdbc.queryForObject("SELECT count(" + expression + ") FROM outbox o JOIN auctions a "
                + "ON o.aggregate_id = a.id::text WHERE a.title = ? AND o.event_type = 'AuctionEnded'",
                Integer.class, marker);
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
