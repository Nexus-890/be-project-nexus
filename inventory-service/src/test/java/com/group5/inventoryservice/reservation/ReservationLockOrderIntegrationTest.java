package com.group5.inventoryservice.reservation;

import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.reservation.dto.request.ReserveRequest;
import com.group5.inventoryservice.reservation.service.ReservationService;
import com.group5.inventoryservice.shared.UuidOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-lock-order-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_LOCK",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class ReservationLockOrderIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID LOW_SKU = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID HIGH_SKU = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final int ROUNDS = 40;
    private static final CurrentUser BUYER = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of());

    @Autowired private ReservationService reservations;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private UUID warehouse;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_LOCK', 'Lock order test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_LOCK'", UUID.class);
    }

    @Test
    void theSkuOrderInJavaIsThePostgresOrderBySkuId() {
        List<UUID> ids = new ArrayList<>(List.of(LOW_SKU, HIGH_SKU, new UUID(0, 0), new UUID(-1, -1),
                UUID.fromString("7fffffff-ffff-ffff-7fff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-8000-000000000000")));
        for (int i = 0; i < 500; i++) {
            ids.add(UUID.randomUUID());
        }

        List<UUID> postgres = jdbc.queryForList("SELECT u FROM unnest(?::uuid[]) AS u ORDER BY u", UUID.class,
                (Object) ids.stream().map(UUID::toString).toArray(String[]::new));

        assertThat(ids.stream().sorted(UuidOrder.DATABASE).toList()).isEqualTo(postgres);
        assertThat(LOW_SKU.compareTo(HIGH_SKU)).isPositive();
        assertThat(UuidOrder.DATABASE.compare(LOW_SKU, HIGH_SKU)).isNegative();
    }

    @Test
    void reserveAndConfirmOrderOnTheSameTwoSkusRunTogetherWithoutDeadlock() throws Exception {
        stock(LOW_SKU, 1000);
        stock(HIGH_SKU, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID paidOrder = UUID.randomUUID();
                reservations.reserve(request(paidOrder), BUYER);
                UUID newOrder = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> confirm = pool.submit(() -> {
                    start.await();
                    return reservations.confirmOrder(paidOrder);
                });
                Future<?> reserve = pool.submit(() -> {
                    start.await();
                    return reservations.reserve(request(newOrder), BUYER);
                });
                start.countDown();
                for (Future<?> task : List.of(confirm, reserve)) {
                    try {
                        task.get(30, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException failure) {
                        failures.add(failure.getCause());
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();
        for (UUID sku : List.of(LOW_SKU, HIGH_SKU)) {
            assertThat(jdbc.queryForMap("""
                    SELECT available_quantity, reserved_quantity, unavailable_quantity FROM inventory_balances
                    WHERE sku_id = ? AND warehouse_id = ?
                    """, sku, warehouse))
                    .containsEntry("available_quantity", 1000 - 2 * ROUNDS)
                    .containsEntry("reserved_quantity", ROUNDS)
                    .containsEntry("unavailable_quantity", ROUNDS);
        }
    }

    private static ReserveRequest request(UUID order) {
        return new ReserveRequest(UUID.randomUUID(), List.of(
                new ReserveRequest.Item(order, HIGH_SKU, 1),
                new ReserveRequest.Item(order, LOW_SKU, 1)));
    }

    private void stock(UUID sku, int total) {
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, ?, ?)
                """, sku, warehouse, total, total);
    }
}
