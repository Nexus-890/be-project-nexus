package com.group5.inventoryservice.reservation;

import com.group5.common_test.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/inventory-concurrency-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_CONC",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class ReservationConcurrencyIT extends PostgresIntegrationTest {

    private static final int REQUESTS = 100;
    private static final int STOCK = 10;

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private UUID warehouse;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_CONC', 'Concurrency test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_CONC'", UUID.class);
        when(decoder.decode("buyer")).thenReturn(Jwt.withTokenValue("buyer").header("alg", "RS256")
                .subject(UUID.randomUUID().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("BUYER")).claim("privileges", List.of()).build());
    }

    @RepeatedTest(10)
    void hundredConcurrentOneUnitReservationsAgainstTenUnitsReserveExactlyTen(RepetitionInfo repetition)
            throws Exception {
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, ?, ?)
                """, sku, warehouse, STOCK, STOCK);

        List<HttpResponse<String>> responses = fireTogether(sku);

        List<HttpResponse<String>> created = responses.stream().filter(response -> response.statusCode() == 201).toList();
        List<HttpResponse<String>> conflicts = responses.stream().filter(response -> response.statusCode() == 409).toList();
        Map<String, Object> balance = jdbc.queryForMap("""
                SELECT total_quantity, available_quantity, reserved_quantity, unavailable_quantity, version
                FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?
                """, sku, warehouse);
        Integer reservationRows = jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class);
        System.out.printf("RESERVATION_CONCURRENCY run=%d requests=%d created=%d conflicts=%d reservation_rows=%d "
                        + "available=%s reserved=%s total=%s%n", repetition.getCurrentRepetition(), REQUESTS,
                created.size(), conflicts.size(), reservationRows, balance.get("available_quantity"),
                balance.get("reserved_quantity"), balance.get("total_quantity"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(created.size()).as("HTTP 201 responses").isEqualTo(STOCK);
        softly.assertThat(conflicts.size()).as("HTTP 409 responses").isEqualTo(REQUESTS - STOCK);
        softly.assertThat(conflicts.stream().map(response -> (String) JsonPath.read(response.body(), "$.code")).distinct())
                .as("409 problem codes").containsOnly("INVENTORY_INSUFFICIENT_STOCK");
        softly.assertThat(balance).as("balance row").isEqualTo(Map.of("total_quantity", STOCK,
                "available_quantity", 0, "reserved_quantity", STOCK, "unavailable_quantity", 0,
                "version", (long) STOCK));
        softly.assertThat(jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'inventory_balances'::regclass AND contype = 'c' AND convalidated
                """, String.class)).as("validated CHECK constraints").contains(
                "CHECK ((total_quantity = ((available_quantity + reserved_quantity) + unavailable_quantity)))",
                "CHECK ((available_quantity >= 0))", "CHECK ((reserved_quantity >= 0))");
        softly.assertThat(reservationRows).as("SELECT count(*) FROM inventory_reservations").isEqualTo(STOCK);
        List<String> createdIds = created.stream()
                .map(response -> (String) JsonPath.read(response.body(), "$.data.reservationIds[0]")).toList();
        softly.assertThat(jdbc.queryForList("SELECT id::text FROM inventory_reservations WHERE sku_id = ? AND status = 'HELD'",
                String.class, sku)).as("HELD reservation ids").containsExactlyInAnyOrderElementsOf(createdIds);
        softly.assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'RESERVE' AND quantity = 1
                """, Integer.class, sku)).as("RESERVE movements").isEqualTo(STOCK);
        softly.assertAll();
    }

    private List<HttpResponse<String>> fireTogether(UUID sku) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/api/v1/inventory/reservations"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer buyer")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"orderGroupId\":\"" + UUID.randomUUID()
                                + "\",\"items\":[{\"orderId\":\"" + UUID.randomUUID() + "\",\"skuId\":\"" + sku
                                + "\",\"quantity\":1}]}"))
                        .build();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(90, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
            client.close();
        }
    }
}
