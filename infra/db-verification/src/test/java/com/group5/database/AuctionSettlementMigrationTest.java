package com.group5.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Kiểm tra chuỗi migration thật và nâng cấp từ schema develop, không sửa DB đang dùng. */
class AuctionSettlementMigrationTest {
    private static final UUID REFERENCE = UUID.fromString("17200000-0000-4000-8000-000000000001");
    private static GenericContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    record Service(String name, String baseline, String latest) {
        @Override public String toString() { return name; }
    }

    static Stream<Service> services() {
        return Stream.of(new Service("auction-service", "7", "8"),
                new Service("commerce-service", "13", "14"));
    }

    @BeforeAll static void startPostgres() {
        jdbcUrl = System.getProperty("settlement.test.jdbc-url");
        username = "postgres";
        password = "";
        if (jdbcUrl == null) {
            postgres = new GenericContainer<>("postgres:18-alpine")
                    .withEnv("POSTGRES_PASSWORD", "migration-test")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                    .withStartupTimeout(Duration.ofMinutes(3));
            postgres.start();
            jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/postgres";
            password = "migration-test";
        }
    }

    @AfterAll static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @ParameterizedTest(name = "fresh settlement schema: {0}")
    @MethodSource("services")
    void freshChainHasNoDuplicateVersions(Service service) throws Exception {
        inOwnedSchema(database -> {
            Flyway flyway = database.flyway(service, null);
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo(service.latest());
            try (Connection connection = database.connect()) {
                verifyAppliedVersions(connection, service, true);
                seed(connection, service);
                verifySettlementSchema(connection, service);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        });
    }

    @ParameterizedTest(name = "preserves develop schema and data: {0}")
    @MethodSource("services")
    void upgradeFromDevelopPreservesRowsAndAppliedHistory(Service service) throws Exception {
        inOwnedSchema(database -> {
            Flyway baseline = database.flyway(service, service.baseline());
            baseline.migrate();
            assertThat(baseline.info().current().getVersion().toString()).isEqualTo(service.baseline());
            Map<String, List<String>> before;
            List<String> historyBefore;
            try (Connection connection = database.connect()) {
                verifyAppliedVersions(connection, service, false);
                seed(connection, service);
                before = businessRows(connection);
                historyBefore = rows(connection, "SELECT to_jsonb(t)::text FROM flyway_schema_history t ORDER BY installed_rank");
            }

            // Chỉ migration mới của #172 được chạy; checksum và lịch sử develop phải còn nguyên.
            Flyway upgraded = database.flyway(service, null);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            upgraded.validate();
            assertThat(upgraded.info().current().getVersion().toString()).isEqualTo(service.latest());
            try (Connection connection = database.connect()) {
                verifyAppliedVersions(connection, service, true);
                assertThat(businessRows(connection)).isEqualTo(before);
                List<String> historyAfter = rows(connection,
                        "SELECT to_jsonb(t)::text FROM flyway_schema_history t ORDER BY installed_rank");
                assertThat(historyAfter).hasSize(historyBefore.size() + 1);
                assertThat(historyAfter.subList(0, historyBefore.size())).containsExactlyElementsOf(historyBefore);
                verifySettlementSchema(connection, service);
            }
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            upgraded.validate();
        });
    }

    private static void verifyAppliedVersions(Connection connection, Service service, boolean includeLatest)
            throws SQLException {
        // Kiểm đủ migration lịch sử: chỉ nhìn version cuối sẽ bỏ sót trường hợp thiếu V5/V6.
        // Commerce chủ động không có V10; danh tính thanh toán đã được chuyển sang V12.
        List<String> expected = new ArrayList<>(service.name().equals("auction-service")
                ? List.of("1", "2", "3", "4", "5", "6", "7")
                : List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "11", "12", "13"));
        if (includeLatest) expected.add(service.latest());
        assertThat(rows(connection, """
                SELECT version FROM flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank
                """)).containsExactlyElementsOf(expected);
    }

    private static void seed(Connection connection, Service service) throws SQLException {
        if (service.name().equals("auction-service")) {
            execute(connection, """
                    INSERT INTO auctions (id, product_id, sku_id, seller_id, title, starting_price,
                        bid_increment, current_price, leader_bidder_id, leader_bidder_name, bid_count,
                        start_at, end_at, original_end_at, status, winner_id, final_price, payment_due_at)
                    VALUES ('17200000-0000-4000-8000-000000000001', uuidv7(), uuidv7(), uuidv7(),
                        'Develop winner', 100000, 10000, 900000,
                        '17200000-0000-4000-8000-000000000002', 'Win***', 1,
                        now() - interval '2 days', now() - interval '1 day', now() - interval '1 day',
                        'WON', '17200000-0000-4000-8000-000000000002', 900000, now() + interval '1 day');
                    INSERT INTO bids (auction_id, bidder_id, bidder_name, amount, status, idempotency_key)
                    VALUES ('17200000-0000-4000-8000-000000000001',
                        '17200000-0000-4000-8000-000000000002', 'Win***', 900000, 'WON', 'develop-bid');
                    """);
        } else {
            UUID order = insertOrder(connection, "AUCTION", REFERENCE);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO order_items (order_id, product_id, sku_id, product_name,
                        quantity, unit_price, total_price)
                    VALUES (?, uuidv7(), uuidv7(), 'Develop winning item', 1, 900000, 900000)
                    """)) {
                statement.setObject(1, order);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO order_status_history (order_id, new_status, reason)
                    VALUES (?, 'PENDING', 'Created before settlement migration')
                    """)) {
                statement.setObject(1, order);
                statement.executeUpdate();
            }
        }
    }

    private static void verifySettlementSchema(Connection connection, Service service) throws SQLException {
        if (service.name().equals("auction-service")) {
            assertThat(rows(connection, """
                    SELECT data_type || ':' || is_nullable FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'auctions' AND column_name = 'settled_at'
                    """)).containsExactly("timestamp with time zone:YES");
            assertThat(rows(connection, "SELECT settled_at::text FROM auctions")).containsExactly((String) null);
            assertThat(rows(connection, "SELECT leader_bidder_name FROM auctions")).containsExactly("Win***");
            assertThat(rows(connection, "SELECT bidder_name FROM bids")).containsExactly("Win***");
        } else {
            assertThat(rows(connection, "SELECT payment_note FROM orders")).containsExactly("NEXUS-AUC-172");
            assertThat(rows(connection, """
                    SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()
                        AND indexname = 'uq_orders_auction_reference'
                    """)).singleElement().asString().contains("UNIQUE INDEX", "reference_id", "WHERE");

            // Cùng phiên đấu giá chỉ được tạo một đơn AUCTION, nhưng không khóa đơn STANDARD hay tham chiếu null.
            SQLException duplicate = assertThrows(SQLException.class,
                    () -> insertOrder(connection, "AUCTION", REFERENCE));
            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            assertThat(duplicate.getMessage()).contains("uq_orders_auction_reference");
            insertOrder(connection, "STANDARD", REFERENCE);
            insertOrder(connection, "AUCTION", null);
            insertOrder(connection, "AUCTION", null);
        }
    }

    private static UUID insertOrder(Connection connection, String type, UUID reference) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("""
                INSERT INTO order_groups (id, buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, uuidv7(), ?, 900000, now() + interval '1 day')
                """)) {
            statement.setObject(1, id);
            statement.setString(2, id.toString());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO orders (id, order_group_id, user_id, seller_id, order_number, order_type,
                    reference_id, status, subtotal, total_amount, idempotency_key, payment_due_at, payment_note)
                VALUES (?, ?, uuidv7(), uuidv7(), ?, CAST(? AS order_type), ?, 'PENDING',
                    900000, 900000, ?, now() + interval '1 day', 'NEXUS-AUC-172')
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, id);
            statement.setString(3, "AUC-" + id);
            statement.setString(4, type);
            statement.setObject(5, reference);
            statement.setString(6, id.toString());
            statement.executeUpdate();
        }
        return id;
    }

    private static Map<String, List<String>> businessRows(Connection connection) throws SQLException {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : rows(connection, """
                SELECT tablename FROM pg_tables WHERE schemaname = current_schema()
                    AND tablename <> 'flyway_schema_history' ORDER BY tablename
                """)) {
            // settled_at là cột mới duy nhất; giữ mọi cột cũ, kể cả audit/name/payment_note trong snapshot.
            snapshot.put(table, rows(connection, "SELECT (to_jsonb(t) - 'settled_at')::text FROM \""
                    + table.replace("\"", "\"\"") + "\" t ORDER BY 1"));
        }
        return snapshot;
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) rows.add(result.getString(1));
        }
        return rows;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    @FunctionalInterface private interface SchemaCheck { void run(Database database) throws Exception; }

    private static void inOwnedSchema(SchemaCheck check) throws Exception {
        String schema = "auction172_migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            check.run(new Database(schema));
        } finally {
            // Chỉ xóa schema ngẫu nhiên do đúng testcase này tạo; không clean/repair database có sẵn.
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                execute(connection, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private record Database(String schema) {
        Flyway flyway(Service service, String target) {
            var configuration = Flyway.configure().dataSource(jdbcUrl, username, password)
                    .schemas(schema).defaultSchema(schema)
                    .locations("filesystem:" + Path.of(System.getProperty("nexus.backend"), service.name(),
                            "src/main/resources/db/migration").toAbsolutePath().normalize());
            if (target != null) configuration.target(target);
            return configuration.load();
        }

        Connection connect() throws SQLException {
            Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
            connection.setSchema(schema);
            return connection;
        }
    }
}
