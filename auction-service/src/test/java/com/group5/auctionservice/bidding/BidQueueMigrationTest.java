package com.group5.auctionservice.bidding;

import com.group5.auctionservice.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL thật: V10 nâng cấp develop V9 và giữ nguyên dữ liệu/lịch sử second-chance. */
class BidQueueMigrationTest {
    private static final UUID BIDDER = UUID.fromString("25300000-0000-4000-8000-000000000002");
    private static final Instant PLACED_AT = Instant.parse("2026-11-01T10:00:00.123456Z");
    private final TestPostgres postgres = new TestPostgres();

    @BeforeEach void start() { postgres.start(); }
    @AfterEach void stop() { postgres.stop(); }

    @Test
    void upgradeFromDevelopV9PreservesHistoryOffersAndHistoricalBids() throws Exception {
        Flyway baseline = flyway("9");
        baseline.migrate();
        assertThat(baseline.info().current().getVersion().toString()).isEqualTo("9");
        List<String> historyBefore;
        List<String> bidsBefore;
        Map<String, List<String>> offersBefore;
        try (Connection connection = connect()) {
            seedLegacy(connection);
            historyBefore = history(connection);
            bidsBefore = rows(connection, "SELECT to_jsonb(b)::text FROM bids b ORDER BY id");
            offersBefore = existingOfferRows(connection);
        }

        Flyway upgraded = flyway("10");
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        try (Connection connection = connect()) {
            List<String> historyAfter = history(connection);
            assertThat(historyAfter).hasSize(historyBefore.size() + 1);
            // Bao gồm checksum, script, installed_rank và thời gian của toàn bộ migration đã áp dụng.
            assertThat(historyAfter.subList(0, historyBefore.size())).containsExactlyElementsOf(historyBefore);
            assertVersions(connection);
            assertThat(existingOfferRows(connection)).isEqualTo(offersBefore);
            assertThat(rows(connection, """
                    SELECT (to_jsonb(b) - ARRAY['requested_at','processed_at','reject_reason',
                        'reject_minimum','result_current_price','result_leader_bidder_id'])::text
                    FROM bids b ORDER BY id
                    """)).containsExactlyElementsOf(bidsBefore);
            try (var statement = connection.createStatement();
                 var bid = statement.executeQuery("""
                         SELECT placed_at,requested_at,processed_at,amount,result_current_price,
                             result_leader_bidder_id,reject_reason,reject_minimum
                         FROM bids WHERE id='25300000-0000-4000-8000-000000000003'
                         """)) {
                assertThat(bid.next()).isTrue();
                assertThat(bid.getTimestamp("placed_at").toInstant()).isEqualTo(PLACED_AT);
                assertThat(bid.getTimestamp("requested_at").toInstant()).isEqualTo(PLACED_AT);
                assertThat(bid.getTimestamp("processed_at").toInstant()).isEqualTo(PLACED_AT);
                assertThat(bid.getBigDecimal("result_current_price")).isEqualByComparingTo(bid.getBigDecimal("amount"));
                assertThat(bid.getObject("result_leader_bidder_id", UUID.class)).isEqualTo(BIDDER);
                assertThat(bid.getString("reject_reason")).isNull();
                assertThat(bid.getBigDecimal("reject_minimum")).isNull();
            }
            assertQueueStatesCanBeStored(connection);
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        upgraded.validate();
    }

    @Test
    void freshChainIncludesBothSecondChanceAndQueueSchemasExactlyOnce() throws Exception {
        Flyway fresh = flyway("10");
        assertThat(fresh.migrate().migrationsExecuted).isEqualTo(10);
        fresh.validate();
        assertThat(fresh.info().current().getVersion().toString()).isEqualTo("10");
        try (Connection connection = connect()) {
            assertVersions(connection);
            assertThat(rows(connection, """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema=current_schema() AND table_name IN
                        ('auction_offers','auction_payment_timeouts','auction_pending_timeouts')
                    ORDER BY table_name
                    """)).containsExactly("auction_offers", "auction_payment_timeouts", "auction_pending_timeouts");
            seedLegacy(connection);
            assertQueueStatesCanBeStored(connection);
            assertThat(rows(connection, "SELECT count(*)::text FROM auction_offers")).containsExactly("1");
            assertThat(rows(connection, "SELECT count(*)::text FROM bids")).containsExactly("3");
        }
        assertThat(fresh.migrate().migrationsExecuted).isZero();
        fresh.validate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath())
                .target(target).load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void assertVersions(Connection connection) throws SQLException {
        assertThat(rows(connection, """
                SELECT version FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank
                """)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(rows(connection, """
                SELECT script FROM flyway_schema_history WHERE version='10' AND success AND checksum IS NOT NULL
                """)).containsExactly("V10__bid_queue.sql");
    }

    private static List<String> history(Connection connection) throws SQLException {
        return rows(connection, "SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank");
    }

    private static Map<String, List<String>> existingOfferRows(Connection connection) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : List.of("auctions", "auction_offers", "auction_payment_timeouts", "auction_pending_timeouts")) {
            result.put(table, rows(connection, "SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text"));
        }
        return result;
    }

    private static void seedLegacy(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,
                    current_price,start_at,end_at,original_end_at,status,winner_id,final_price,
                    payment_due_at,order_id,settled_at,offer_id)
                VALUES ('25300000-0000-4000-8000-000000000001',uuidv7(),uuidv7(),
                    '25300000-0000-4000-8000-000000000004','Historical second chance',100000,10000,900000,
                    '2026-11-01T09:00:00Z','2026-11-01T11:00:00Z','2026-11-01T11:00:00Z','PAYMENT_TIMEOUT',
                    '25300000-0000-4000-8000-000000000002',900000,'2026-11-02T11:00:00Z',
                    '25300000-0000-4000-8000-000000000005','2026-11-01T11:05:00Z',
                    '25300000-0000-4000-8000-000000000006');
                INSERT INTO bids(id,auction_id,bidder_id,bidder_name,amount,status,idempotency_key,placed_at)
                VALUES ('25300000-0000-4000-8000-000000000003','25300000-0000-4000-8000-000000000001',
                    '25300000-0000-4000-8000-000000000002','B***r',900000,'OUTBID','legacy-key',
                    '2026-11-01T10:00:00.123456Z');
                INSERT INTO auction_offers(id,auction_id,bidder_id,bidder_name,amount,status,stock_ready,
                    offered_at,expires_at,responded_at,order_id,payment_due_at,created_by,updated_by)
                VALUES ('25300000-0000-4000-8000-000000000006','25300000-0000-4000-8000-000000000001',
                    '25300000-0000-4000-8000-000000000007','O***r',850000,'ACCEPTED',true,
                    '2026-11-03T10:00:00Z','2026-11-04T10:00:00Z','2026-11-03T10:05:00Z',
                    '25300000-0000-4000-8000-000000000008','2026-11-04T10:05:00Z',
                    '25300000-0000-4000-8000-000000000004','25300000-0000-4000-8000-000000000007');
                INSERT INTO auction_payment_timeouts(order_id,auction_id,winner_id,payment_due_at,timed_out_at)
                VALUES ('25300000-0000-4000-8000-000000000005','25300000-0000-4000-8000-000000000001',
                    '25300000-0000-4000-8000-000000000002','2026-11-02T11:00:00Z','2026-11-02T11:00:01Z');
                INSERT INTO auction_pending_timeouts(order_id,payload)
                VALUES ('25300000-0000-4000-8000-000000000009','{"reason":"PAYMENT_DEADLINE_EXPIRED"}');
                """);
    }

    private static void assertQueueStatesCanBeStored(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO bids(auction_id,bidder_id,bidder_name,amount,status,idempotency_key,placed_at,requested_at)
                VALUES ('25300000-0000-4000-8000-000000000001','25300000-0000-4000-8000-000000000007',
                    'Q***r',950000,'QUEUED','queue-key',NULL,'2026-11-01T10:59:59Z');
                INSERT INTO bids(auction_id,bidder_id,bidder_name,amount,status,idempotency_key,placed_at,
                    requested_at,processed_at,reject_reason,reject_minimum)
                VALUES ('25300000-0000-4000-8000-000000000001','25300000-0000-4000-8000-000000000007',
                    'R***r',800000,'REJECTED','reject-key',NULL,'2026-11-01T10:59:58Z',
                    '2026-11-01T11:00:01Z','AUCTION_BID_TOO_LOW',960000);
                """);
        assertThat(rows(connection, """
                SELECT status::text FROM bids WHERE placed_at IS NULL ORDER BY status::text
                """)).containsExactly("QUEUED", "REJECTED");
        assertThat(rows(connection, """
                SELECT reject_reason || ':' || reject_minimum::text FROM bids
                WHERE status='REJECTED' AND processed_at IS NOT NULL AND requested_at IS NOT NULL
                """)).containsExactly("AUCTION_BID_TOO_LOW:960000.00");
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
