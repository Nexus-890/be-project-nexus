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
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises the actual migration chains; never connects to a developer database. */
class SchemaMigrationTest {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:18-alpine")
            .withEnv("POSTGRES_PASSWORD", "migration-test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
            .withStartupTimeout(Duration.ofMinutes(3));

    record Service(String name, String baseline, List<String> enums, List<String> businessTables) {
        @Override public String toString() { return name; }
    }

    private static final Map<String, String[]> RETIRED_COLUMNS = Map.of("users",
            new String[] {"password_hash", "failed_login_count", "locked_until", "last_login_at"});

    static Stream<Service> services() {
        return Stream.of(
                new Service("user-service", "3", List.of("users.status", "users.gender"), List.of("users")),
                new Service("catalog-service", "4", List.of("products.status", "skus.status"),
                        List.of("categories", "products", "skus", "product_images", "product_stats")),
                // Optional V3 applies the payment split before fixtures when that migration is available.
                new Service("commerce-service", "3?", List.of("carts.status", "orders.status", "orders.order_type",
                        "order_status_history.previous_status", "order_status_history.new_status",
                        "promo_mega_sale_events.status", "promo_vouchers.status",
                        "promo_event_sellers.status", "promo_customer_vouchers.status",
                        "promo_voucher_sellers.status", "promo_voucher_usages.status"),
                        List.of("carts", "cart_items", "orders", "order_items", "order_status_history",
                                "promo_mega_sale_events", "promo_vouchers", "promo_event_sellers",
                                "promo_customer_vouchers", "promo_voucher_products", "promo_voucher_sellers",
                                "promo_voucher_usages", "promo_voucher_usage_allocations")),
                new Service("auction-service", "2",
                        List.of("auctions.status", "auctions.visibility", "bids.status"),
                        List.of("auctions", "bids", "auction_watchers")),
                new Service("notification-service", "1", List.of("notification_templates.channel",
                        "notification_deliveries.channel", "notification_deliveries.status",
                        "notification_delivery_attempts.status", "notification_preferences.channel"),
                        List.of("event_logs", "notification_templates", "notifications", "notification_deliveries",
                                "notification_delivery_attempts", "notification_preferences")),
                new Service("inventory-service", "3", List.of("warehouses.status", "inventory_reservations.status",
                        "inventory_movements.movement_type"),
                        List.of("warehouses", "inventory_balances", "inventory_reservations",
                                "inventory_movements")));
    }

    @BeforeAll static void startPostgres() { POSTGRES.start(); }
    @AfterAll static void stopPostgres() { POSTGRES.stop(); }

    @ParameterizedTest(name = "fresh schema: {0}")
    @MethodSource("services")
    void freshSchemaUsesEnumsAndAuditColumns(Service service) throws Exception {
        String database = newDatabase();
        migrate(service, database, null);
        try (Connection connection = connect(database)) {
            verifyContract(connection, service);
            verifyOutboxIndex(connection, service);
        }
    }

    @ParameterizedTest(name = "preserves existing data: {0}")
    @MethodSource("services")
    void upgradePreservesEveryExistingBusinessRow(Service service) throws Exception {
        String database = newDatabase();
        migrate(service, database, service.baseline());
        Map<String, List<String>> before;
        try (Connection connection = connect(database)) {
            seedLegacyRows(connection, service.name());
            before = snapshot(connection, service);
        }
        migrate(service, database, null);
        try (Connection connection = connect(database)) {
            assertThat(snapshot(connection, service)).isEqualTo(before);
            verifyContract(connection, service);
        }
    }

    private void verifyContract(Connection connection, Service service) throws SQLException {
        if (service.name().equals("inventory-service")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT to_regclass('public.shipments'), to_regclass('public.shipment_history')")) {
                rows.next();
                assertThat(rows.getString(1)).as("inventory no longer owns shipments").isNull();
                assertThat(rows.getString(2)).as("inventory no longer owns shipment history").isNull();
            }
        }
        if (service.name().equals("user-service")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT to_regclass('public.refresh_tokens'), to_regclass('public.password_reset_tokens'),
                           to_regclass('public.user_roles'), to_regclass('public.role_permissions'),
                           to_regclass('public.roles'), to_regclass('public.permissions'),
                           (SELECT count(*) FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'users'
                              AND column_name = ANY (ARRAY['password_hash', 'failed_login_count',
                                                           'locked_until', 'last_login_at']))
                    """)) {
                rows.next();
                for (int column = 1; column <= 6; column++) {
                    assertThat(rows.getString(column)).as("Keycloak owns auth table %d", column).isNull();
                }
                assertThat(rows.getLong(7)).as("Keycloak owns credentials and lockout state").isZero();
            }
        }
        for (String qualified : service.enums()) {
            String[] parts = qualified.split("\\.");
            try (var query = connection.prepareStatement("""
                    SELECT c.data_type, t.typtype FROM information_schema.columns c
                    JOIN pg_type t ON t.typname = c.udt_name
                    WHERE c.table_schema = 'public' AND c.table_name = ? AND c.column_name = ?
                    """)) {
                query.setString(1, parts[0]);
                query.setString(2, parts[1]);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).as(qualified).isTrue();
                    assertThat(rows.getString(1)).as(qualified).isEqualTo("USER-DEFINED");
                    assertThat(rows.getString(2)).as(qualified).isEqualTo("e");
                }
            }
            // An unknown literal must fail enum coercion before any NOT NULL/FK checks.
            SQLException invalid = assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO " + parts[0] + " (" + parts[1]
                            + ") VALUES ('__INVALID_ENUM__')");
                }
            });
            assertThat(invalid.getSQLState()).as(qualified).isEqualTo("22P02");
        }
        for (String table : service.businessTables()) {
            try (var query = connection.prepareStatement("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ?
                      AND column_name IN ('created_by', 'updated_by')
                      AND data_type = 'uuid' AND is_nullable = 'NO'
                    ORDER BY column_name
                    """)) {
                query.setString(1, table);
                List<String> columns = new ArrayList<>();
                try (var rows = query.executeQuery()) { while (rows.next()) columns.add(rows.getString(1)); }
                assertThat(columns).as(table).containsExactly("created_by", "updated_by");
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT count(*) FROM " + table + " WHERE created_by IS NULL OR updated_by IS NULL")) {
                rows.next();
                assertThat(rows.getLong(1)).as(table).isZero();
            }
        }
    }

    private void verifyOutboxIndex(Connection connection, Service service) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, topic, payload, published_at)
                    SELECT 'test', n::text, 'test', 'test', '{}'::jsonb,
                           CASE WHEN n <= 1990 THEN now() ELSE NULL END
                    FROM generate_series(1, 2000) n
                    """);
            statement.execute("ANALYZE outbox");
            try (var rows = statement.executeQuery("""
                    EXPLAIN SELECT id FROM outbox WHERE published_at IS NULL
                    ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED
                    """)) {
                StringBuilder plan = new StringBuilder();
                while (rows.next()) plan.append(rows.getString(1)).append('\n');
                assertThat(plan.toString()).as(service.name() + " outbox relay").contains("idx_outbox_unpublished");
                System.out.println(service.name() + " outbox plan:\n" + plan);
            }
        }
    }

    private Map<String, List<String>> snapshot(Connection connection, Service service) throws SQLException {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : service.businessTables()) {
            List<String> rows = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT (to_jsonb(t) - 'created_by' - 'updated_by' - ?::text[])::text FROM " + table + " t ORDER BY 1")) {
                query.setArray(1, connection.createArrayOf("text", RETIRED_COLUMNS.getOrDefault(table, new String[0])));
                try (var result = query.executeQuery()) { while (result.next()) rows.add(result.getString(1)); }
            }
            snapshot.put(table, rows);
        }
        return snapshot;
    }

    private void seedLegacyRows(Connection connection, String service) throws SQLException {
        String sql = switch (service) {
            case "commerce-service" -> """
                    DO $$
                    DECLARE
                        actor uuid := uuidv7();
                        sale uuid := uuidv7();
                        voucher uuid := uuidv7();
                        seller uuid := uuidv7();
                        claimed uuid := uuidv7();
                        accepted uuid := uuidv7();
                        usage uuid := uuidv7();
                        purchase uuid := uuidv7();
                        item uuid := uuidv7();
                        basket uuid := uuidv7();
                    BEGIN
                        INSERT INTO carts (id, user_id, status) VALUES (basket, actor, 'CHECKED_OUT');
                        INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price)
                        VALUES (basket, uuidv7(), uuidv7(), 1, 100);
                        INSERT INTO orders (id, user_id, seller_id, order_number, order_type, status,
                            subtotal, total_amount, idempotency_key)
                        VALUES (purchase, actor, actor, 'legacy-order', 'AUCTION', 'REFUNDED', 100, 100, 'legacy-order');
                        INSERT INTO order_items (id, order_id, product_id, sku_id, product_name,
                            quantity, unit_price, total_price)
                        VALUES (item, purchase, uuidv7(), uuidv7(), 'Legacy', 1, 100, 100);
                        INSERT INTO order_status_history (order_id, previous_status, new_status)
                        VALUES (purchase, 'REFUND_REQUESTED', 'REFUNDED');
                        INSERT INTO promo_mega_sale_events (id, name, registration_start_at,
                            registration_end_at, start_at, end_at, status, created_by)
                        VALUES (sale, 'Legacy sale', now(), now() + interval '1 day',
                            now() + interval '2 days', now() + interval '3 days', 'REGISTRATION_OPEN', actor);
                        INSERT INTO promo_vouchers (id, code, name, source_type, event_id, discount_type,
                            discount_value, funding_type, product_scope, claim_start_at, claim_end_at,
                            valid_from, valid_until, status, created_by)
                        VALUES (voucher, 'LEGACY', 'Legacy voucher', 'PLATFORM', sale, 'FIXED', 10,
                            'PLATFORM', 'ALL', now(), now() + interval '1 day', now(),
                            now() + interval '3 days', 'PAUSED', actor);
                        INSERT INTO promo_event_sellers (id, event_id, seller_id, status)
                        VALUES (seller, sale, actor, 'APPROVED');
                        INSERT INTO promo_customer_vouchers (id, customer_id, voucher_id, status, expires_at)
                        VALUES (claimed, actor, voucher, 'USED', now() + interval '3 days');
                        INSERT INTO promo_voucher_products (voucher_id, product_id) VALUES (voucher, uuidv7());
                        INSERT INTO promo_voucher_sellers (id, voucher_id, event_seller_id, status,
                            accepted_terms_version, seller_funding_percent_snapshot, accepted_by)
                        VALUES (accepted, voucher, seller, 'WITHDRAWN', 1, 0, actor);
                        INSERT INTO promo_voucher_usages (id, customer_voucher_id, order_id, idempotency_key,
                            status, eligible_amount, discount_amount, platform_funded_amount,
                            voucher_snapshot, reserved_until)
                        VALUES (usage, claimed, purchase, 'legacy-usage', 'REVERSED', 100, 10, 10,
                            '{}'::jsonb, now() + interval '1 day');
                        INSERT INTO promo_voucher_usage_allocations (voucher_usage_id, order_item_id, seller_id,
                            eligible_amount, discount_amount, platform_funded_amount)
                        VALUES (usage, item, actor, 100, 10, 10);
                    END;
                    $$;
                    """;
            case "auction-service" -> """
                    INSERT INTO auctions (id, product_id, sku_id, seller_id, title, starting_price,
                        bid_increment, current_price, start_at, end_at, original_end_at, status, visibility)
                    VALUES ('10000000-0000-0000-0000-000000000001', uuidv7(), uuidv7(), uuidv7(),
                        'Legacy auction', 100, 10, 120, now(), now() + interval '1 day',
                        now() + interval '1 day', 'ACTIVE', 'RESTRICTED');
                    INSERT INTO bids (auction_id, bidder_id, amount, status, idempotency_key)
                    VALUES ('10000000-0000-0000-0000-000000000001', uuidv7(), 120, 'OUTBID', 'legacy');
                    INSERT INTO auction_watchers (auction_id, user_id)
                    VALUES ('10000000-0000-0000-0000-000000000001', uuidv7());
                    """;
            case "notification-service" -> """
                    INSERT INTO notification_templates (code, channel, body) VALUES ('legacy', 'EMAIL', 'Legacy');
                    INSERT INTO notifications (id, user_id, type, title, content)
                    VALUES ('20000000-0000-0000-0000-000000000001', uuidv7(), 'ORDER', 'Legacy', 'Legacy');
                    INSERT INTO notification_deliveries (id, notification_id, channel, status)
                    VALUES ('20000000-0000-0000-0000-000000000002',
                        '20000000-0000-0000-0000-000000000001', 'PUSH', 'FAILED');
                    INSERT INTO notification_delivery_attempts (delivery_id, attempt_number, status)
                    VALUES ('20000000-0000-0000-0000-000000000002', 1, 'FAILED');
                    INSERT INTO notification_preferences (user_id, notification_type, channel)
                    VALUES (uuidv7(), 'ORDER', 'SMS');
                    INSERT INTO event_logs (event_id, event_type, occurred_at) VALUES (uuidv7(), 'legacy', now());
                    """;
            case "inventory-service" -> """
                    INSERT INTO inventory_balances (sku_id, warehouse_id)
                    SELECT uuidv7(), id FROM warehouses LIMIT 1;
                    INSERT INTO inventory_reservations (sku_id, warehouse_id, quantity, expires_at, status)
                    SELECT uuidv7(), id, 1, now() + interval '1 day', 'CONFIRMED' FROM warehouses LIMIT 1;
                    INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity)
                    SELECT uuidv7(), id, 'ADJUSTMENT', 1 FROM warehouses LIMIT 1;
                    """;
            default -> "SELECT 1"; // User/catalog production migrations already contain the demo seed.
        };
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void migrate(Service service, String database, String target) {
        var configuration = Flyway.configure().dataSource(url(database), "postgres", "migration-test")
                .locations("filesystem:" + Path.of(System.getProperty("nexus.backend"), service.name(),
                        "src/main/resources/db/migration").toAbsolutePath().normalize());
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static String newDatabase() throws SQLException {
        String database = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connect("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        return database;
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "postgres", "migration-test");
    }
}
