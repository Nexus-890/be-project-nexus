package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.reservation.ReservationExpiryJob;
import com.group5.inventoryservice.reservation.dto.request.ReserveRequest;
import com.group5.inventoryservice.reservation.service.ReservationService;
import com.group5.inventoryservice.shared.catalog.CatalogSkuOwners;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-intake-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_IT",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class InventoryIntakeIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BUYER_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryMovementRepository movements;
    @Autowired private ReservationService reservations;
    @Autowired private ReservationExpiryJob expiry;
    @Autowired private Tracer tracer;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private CatalogSkuOwners skuOwners;
    private MockMvc mvc;
    private UUID defaultWarehouse;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_IT', 'Intake test') ON CONFLICT (code) DO NOTHING");
        defaultWarehouse = warehouseId("WH_IT");
        reset(decoder, skuOwners);
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN_ID, "ADMIN"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER_ID, "BUYER"));
        when(decoder.decode("seller")).thenReturn(token("seller", SELLER_ID, "SELLER"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void intakeWithoutWarehouseUsesTheConfiguredDefaultWarehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.data.warehouseId").value(defaultWarehouse.toString()))
                .andExpect(jsonPath("$.data.total").value(100))
                .andExpect(jsonPath("$.data.available").value(100))
                .andExpect(jsonPath("$.data.reserved").value(0))
                .andExpect(jsonPath("$.data.unavailable").value(0));

        Map<String, Object> row = row(sku, defaultWarehouse);
        assertThat(row).containsEntry("total_quantity", 100).containsEntry("available_quantity", 100)
                .containsEntry("reserved_quantity", 0).containsEntry("unavailable_quantity", 0)
                .containsEntry("warehouse_id", defaultWarehouse)
                .containsEntry("created_by", ADMIN_ID).containsEntry("updated_by", ADMIN_ID);
        assertThat(defaultWarehouse).isNotEqualTo(warehouseId("WH_001"));
    }

    @Test
    void flywayAddsIntakeAndCommitToTheMovementType() {
        String values = jdbc.queryForObject("SELECT enum_range(NULL::inventory_movement_type)::text", String.class);
        assertThat(values).contains("INTAKE", "COMMIT", "RESERVE", "RELEASE");
    }

    @Test
    void eachIntakeWritesOneIntakeMovementWhoseSumMatchesTheTotal() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        intake("admin", body(sku, null, 50)).andExpect(status().isCreated());

        UUID balanceId = (UUID) row(sku, defaultWarehouse).get("id");
        List<InventoryMovement> ledger = movements.findBySkuIdAndWarehouseIdOrderByCreatedAtAscIdAsc(sku,
                defaultWarehouse);
        assertThat(ledger).extracting(InventoryMovement::getMovementType)
                .containsExactly(MovementType.INTAKE, MovementType.INTAKE);
        assertThat(ledger).extracting(InventoryMovement::getQuantity).containsExactly(100, 50);
        assertThat(ledger).extracting(InventoryMovement::getReferenceId).containsOnly(balanceId);
        assertThat(ledger).extracting(InventoryMovement::getCreatedBy).containsOnly(ADMIN_ID);
        assertThat(ledger).allSatisfy(movement -> assertThat(movement.getCreatedAt()).isNotNull());
        assertThat(intakeSum(sku)).isEqualTo(row(sku, defaultWarehouse).get("total_quantity"));
    }

    @Test
    void secondIntakeRaisesTotalAndAvailableOnTheSameRow() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        intake("admin", body(sku, null, 50))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(150))
                .andExpect(jsonPath("$.data.available").value(150));

        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 150)
                .containsEntry("available_quantity", 150).containsEntry("version", 1L);
    }

    @Test
    void aFailedLedgerInsertRollsTheBalanceBack() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        Map<String, Object> before = row(sku, defaultWarehouse);
        jdbc.execute("ALTER TABLE inventory_movements ADD CONSTRAINT it_block_large_movement CHECK (quantity < 1000)");
        try {
            intake("admin", body(sku, null, 5000)).andExpect(status().is5xxServerError());
            intake("admin", body(UUID.randomUUID(), null, 5000)).andExpect(status().is5xxServerError());
        } finally {
            jdbc.execute("ALTER TABLE inventory_movements DROP CONSTRAINT it_block_large_movement");
        }

        assertThat(row(sku, defaultWarehouse)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balances", Integer.class)).isEqualTo(1);
        assertThat(movementCount(sku)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentFirstIntakesLeaveOneRowWithTheSum() throws Exception {
        UUID sku = UUID.randomUUID();
        int callers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 1; i <= callers; i++) {
            int quantity = i;
            calls.add(() -> {
                start.await();
                return intake("admin", body(sku, null, quantity)).andReturn().getResponse().getStatus();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 55)
                .containsEntry("available_quantity", 55);
        assertThat(movementCount(sku)).isEqualTo(callers);
        assertThat(intakeSum(sku)).isEqualTo(55);
    }

    @Test
    void buyerGets403AndAnonymousGets401WithoutTouchingStock() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("buyer", body(sku, null, 100))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/inventory/intake").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sku, null, 100)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        assertThat(rowCount(sku)).isZero();
        assertThat(movementCount(sku)).isZero();
    }

    @Test
    void nonPositiveQuantityReturns400AndTheRowIsUnchanged() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 100)).andExpect(status().isCreated());
        Map<String, Object> before = row(sku, defaultWarehouse);

        for (int quantity : new int[] {-5, 0}) {
            intake("admin", body(sku, null, quantity))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        }
        assertThat(row(sku, defaultWarehouse)).isEqualTo(before);
        assertThat(movementCount(sku)).isEqualTo(1);
        assertThat(intakeSum(sku)).isEqualTo(100);
    }

    @Test
    void explicitWarehouseIsUsedAndAnUnknownOneReturns404() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID main = warehouseId("WH_001");
        intake("admin", body(sku, main, 20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(main.toString()));
        assertThat(row(sku, main)).containsEntry("total_quantity", 20);

        intake("admin", body(sku, UUID.randomUUID(), 20))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_FOUND"));
        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(movementCount(sku)).isEqualTo(1);
    }

    @Test
    void sellerAddsStockToTheirOwnSkuInTheirOwnWarehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(sku, null, 30))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.available").value(30));

        UUID own = sellerWarehouse(SELLER_ID);
        assertThat(jdbc.queryForMap("SELECT code, name, status::text AS status FROM warehouses WHERE id = ?", own))
                .containsEntry("code", "SELLER-" + SELLER_ID.toString().replace("-", "").toUpperCase())
                .containsEntry("name", "Seller warehouse").containsEntry("status", "ACTIVE");
        assertThat(row(sku, own)).containsEntry("total_quantity", 30).containsEntry("created_by", SELLER_ID);
        assertThat(rowCount(sku)).isEqualTo(1);
        assertThat(movementCount(sku)).isEqualTo(1);
    }

    @Test
    void theFirstSellerIntakeMovesSharedAvailableStockWithTransferMovements() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 20)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(defaultWarehouse.toString()));
        jdbc.update("""
                UPDATE inventory_balances SET available_quantity = 17, reserved_quantity = 3
                WHERE sku_id = ? AND warehouse_id = ?
                """, sku, defaultWarehouse);

        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(sku, null, 5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(22))
                .andExpect(jsonPath("$.data.available").value(22));

        UUID own = sellerWarehouse(SELLER_ID);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 3)
                .containsEntry("available_quantity", 0).containsEntry("reserved_quantity", 3);
        assertThat(row(sku, own)).containsEntry("total_quantity", 22).containsEntry("available_quantity", 22);
        assertThat(jdbc.queryForList("""
                SELECT warehouse_id, quantity FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'TRANSFER' ORDER BY quantity
                """, sku)).extracting(r -> r.get("warehouse_id") + ":" + r.get("quantity"))
                .containsExactly(defaultWarehouse + ":-17", own + ":17");
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0)::int FROM inventory_movements WHERE sku_id = ? AND warehouse_id = ?
                """, Integer.class, sku, own)).isEqualTo(22);

        intake("seller", body(sku, null, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.available").value(23));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_movements WHERE sku_id = ? AND movement_type = 'TRANSFER'
                """, Integer.class, sku)).isEqualTo(2);
    }

    @Test
    void stockReleasedIntoTheSharedWarehouseAfterTheMoveGoesOnToTheSellerWarehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 20)).andExpect(status().isCreated());
        CurrentUser buyer = new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of());
        UUID cancelled = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(new ReserveRequest.Item(cancelled, sku, 2))), buyer);
        reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(new ReserveRequest.Item(expired, sku, 1))), buyer);
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(sku, null, 5)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.available").value(22));
        UUID own = sellerWarehouse(SELLER_ID);

        reservations.releaseOrder(cancelled);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 1)
                .containsEntry("available_quantity", 0).containsEntry("reserved_quantity", 1);
        assertThat(row(sku, own)).containsEntry("total_quantity", 24).containsEntry("available_quantity", 24);

        jdbc.update("""
                UPDATE inventory_reservations SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                WHERE order_id = ?
                """, expired);
        expiry.runOnce();
        assertThat(jdbc.queryForObject("SELECT status::text FROM inventory_reservations WHERE order_id = ?",
                String.class, expired)).isEqualTo("EXPIRED");
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 0)
                .containsEntry("available_quantity", 0).containsEntry("reserved_quantity", 0);
        assertThat(row(sku, own)).containsEntry("total_quantity", 25).containsEntry("available_quantity", 25);
        assertThat(jdbc.queryForList("""
                SELECT quantity FROM inventory_movements
                WHERE sku_id = ? AND warehouse_id = ? AND movement_type = 'TRANSFER' ORDER BY created_at, quantity DESC
                """, Integer.class, sku, own)).containsExactly(17, 2, 1);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0)::int FROM inventory_movements WHERE sku_id = ? AND warehouse_id = ?
                """, Integer.class, sku, own)).isEqualTo(25);
    }

    @Test
    void aLatePaymentTakesBackTheStockMovedToTheSellerWarehouseAfterExpiry() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 2)).andExpect(status().isCreated());
        UUID order = UUID.randomUUID();
        reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(new ReserveRequest.Item(order, sku, 2))),
                new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of()));
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(sku, null, 1)).andExpect(status().isCreated());
        UUID own = sellerWarehouse(SELLER_ID);
        jdbc.update("""
                UPDATE inventory_reservations SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                WHERE order_id = ?
                """, order);
        expiry.runOnce();
        assertThat(row(sku, own)).containsEntry("total_quantity", 3).containsEntry("available_quantity", 3);

        ReservationService.OrderConfirmation confirmation = reservations.confirmOrder(order);

        assertThat(confirmation.reclaimed()).hasSize(1);
        assertThat(confirmation.lost()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT status::text AS status, warehouse_id FROM inventory_reservations WHERE order_id = ?",
                order)).containsEntry("status", "CONFIRMED").containsEntry("warehouse_id", defaultWarehouse);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 2)
                .containsEntry("available_quantity", 0).containsEntry("unavailable_quantity", 2);
        assertThat(row(sku, own)).containsEntry("total_quantity", 1).containsEntry("available_quantity", 1);
        assertThat(jdbc.queryForList("""
                SELECT quantity FROM inventory_movements
                WHERE sku_id = ? AND warehouse_id = ? AND movement_type = 'TRANSFER' AND reason = 'Moved back for a late payment'
                """, Integer.class, sku, own)).containsExactly(-2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'InventoryStockUnavailable'
                """, Integer.class, order.toString())).isZero();
    }

    @Test
    void aLatePaymentStillReportsTheShortageWhenTheSellerWarehouseSoldTheMovedStock() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 2)).andExpect(status().isCreated());
        UUID order = UUID.randomUUID();
        reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(new ReserveRequest.Item(order, sku, 2))),
                new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of()));
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(sku, null, 1)).andExpect(status().isCreated());
        UUID own = sellerWarehouse(SELLER_ID);
        jdbc.update("""
                UPDATE inventory_reservations SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                WHERE order_id = ?
                """, order);
        expiry.runOnce();
        reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(new ReserveRequest.Item(UUID.randomUUID(), sku, 2))),
                new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of()));

        Span span = tracer.nextSpan().name("late-payment").start();
        ReservationService.OrderConfirmation confirmation;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            confirmation = reservations.confirmOrder(order);
        } finally {
            span.end();
        }

        assertThat(confirmation.reclaimed()).isEmpty();
        assertThat(confirmation.lost()).hasSize(1);
        assertThat(row(sku, own)).containsEntry("available_quantity", 1).containsEntry("reserved_quantity", 2);
        assertThat(row(sku, defaultWarehouse)).containsEntry("total_quantity", 0);
    }

    @Test
    void anOrderWithAnOwnSkuAndASharedSkuOfTheSameSellerIsHeldInTheSellerWarehouse() throws Exception {
        UUID own = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        when(skuOwners.sellerOf(own)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(own, null, 3)).andExpect(status().isCreated());
        intake("admin", body(shared, null, 4)).andExpect(status().isCreated());
        when(skuOwners.sellerOf(shared)).thenReturn(Optional.of(SELLER_ID));
        UUID sellerWarehouse = sellerWarehouse(SELLER_ID);
        UUID order = UUID.randomUUID();

        var response = reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(
                new ReserveRequest.Item(order, own, 1), new ReserveRequest.Item(order, shared, 2))),
                new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of()));

        assertThat(response.warehouseId()).isEqualTo(sellerWarehouse);
        assertThat(jdbc.queryForList("SELECT DISTINCT warehouse_id FROM inventory_reservations WHERE order_id = ?",
                UUID.class, order)).containsExactly(sellerWarehouse);
        assertThat(row(shared, sellerWarehouse)).containsEntry("total_quantity", 4)
                .containsEntry("available_quantity", 2).containsEntry("reserved_quantity", 2);
        assertThat(row(shared, defaultWarehouse)).containsEntry("total_quantity", 0);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0)::int FROM inventory_movements
                WHERE sku_id = ? AND warehouse_id = ? AND movement_type = 'TRANSFER'
                """, Integer.class, shared, sellerWarehouse)).isEqualTo(4);
    }

    @Test
    void anOrderMixingAnotherSellersSharedSkuIsRefusedAndHoldsNothing() throws Exception {
        UUID own = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(skuOwners.sellerOf(own)).thenReturn(Optional.of(SELLER_ID));
        intake("seller", body(own, null, 3)).andExpect(status().isCreated());
        intake("admin", body(foreign, null, 4)).andExpect(status().isCreated());
        when(skuOwners.sellerOf(foreign)).thenReturn(Optional.of(UUID.randomUUID()));
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(
                new ReserveRequest.Item(order, own, 1), new ReserveRequest.Item(order, foreign, 1))),
                new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of())))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(InventoryErrorCode.SKUS_FROM_DIFFERENT_SELLERS.code()));
        assertThat(row(foreign, defaultWarehouse)).containsEntry("available_quantity", 4);
        assertThat(row(own, sellerWarehouse(SELLER_ID))).containsEntry("reserved_quantity", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations WHERE order_id = ?",
                Integer.class, order)).isZero();
    }

    @Test
    void anOrderWhoseSkusSitInTwoPlatformWarehousesIsRefusedAndHoldsNothing() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        intake("admin", body(first, null, 3)).andExpect(status().isCreated());
        intake("admin", body(second, warehouseId("WH_001"), 3)).andExpect(status().isCreated());
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(
                new ReserveRequest.Item(order, first, 1), new ReserveRequest.Item(order, second, 1))),
                new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of())))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(InventoryErrorCode.ORDER_SPANS_WAREHOUSES.code()));
        assertThat(row(first, defaultWarehouse)).containsEntry("reserved_quantity", 0);
        assertThat(row(second, warehouseId("WH_001"))).containsEntry("reserved_quantity", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations WHERE order_id = ?",
                Integer.class, order)).isZero();
    }

    @Test
    void adminCannotPutASkuIntoAnotherSellersWarehouseEvenOnItsFirstIntake() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID unowned = UUID.randomUUID();
        UUID otherWarehouse = jdbc.queryForObject("""
                INSERT INTO warehouses (code, name, seller_id) VALUES (?, 'Other seller', ?) RETURNING id
                """, UUID.class, "S-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));

        for (UUID refused : List.of(sku, unowned)) {
            intake("admin", body(refused, otherWarehouse, 2))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_SKU_HOME"));
            assertThat(rowCount(refused)).isZero();
        }
        jdbc.update("INSERT INTO warehouses (code, name, seller_id) VALUES (?, 'Own', ?) ON CONFLICT (seller_id) DO NOTHING",
                "S-" + UUID.randomUUID().toString().substring(0, 8), SELLER_ID);
        intake("admin", body(sku, sellerWarehouse(SELLER_ID), 2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(sellerWarehouse(SELLER_ID).toString()));
    }

    @Test
    void checkoutsGatheringAnOrderAndSellerIntakesOfTheSameSkusRunTogetherWithoutDeadlock() throws Exception {
        CurrentUser buyer = new CurrentUser(BUYER_ID, Set.of("BUYER"), Set.of());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 25; round++) {
                UUID own = UUID.randomUUID();
                UUID shared = UUID.randomUUID();
                when(skuOwners.sellerOf(own)).thenReturn(Optional.of(SELLER_ID));
                intake("seller", body(own, null, 10)).andExpect(status().isCreated());
                intake("admin", body(shared, null, 10)).andExpect(status().isCreated());
                when(skuOwners.sellerOf(shared)).thenReturn(Optional.of(SELLER_ID));
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Object>> calls = new ArrayList<>();
                for (int order = 0; order < 2; order++) {
                    UUID orderId = UUID.randomUUID();
                    calls.add(() -> {
                        start.await();
                        return reservations.reserve(new ReserveRequest(UUID.randomUUID(), List.of(
                                new ReserveRequest.Item(orderId, own, 1), new ReserveRequest.Item(orderId, shared, 1))),
                                buyer);
                    });
                }
                calls.add(() -> {
                    start.await();
                    return intake("seller", body(shared, null, 1)).andReturn().getResponse().getStatus();
                });
                List<Future<Object>> results = calls.stream().map(pool::submit).toList();
                start.countDown();
                for (Future<Object> result : results) {
                    assertThat(result.get()).isNotEqualTo(500);
                }
                UUID sellerWarehouse = sellerWarehouse(SELLER_ID);
                assertThat(row(shared, sellerWarehouse)).containsEntry("total_quantity", 11)
                        .containsEntry("reserved_quantity", 2);
                assertThat(row(own, sellerWarehouse)).containsEntry("reserved_quantity", 2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void adminIntakeWithoutWarehouseGoesToTheOwnersWarehouseAndAnotherOneIsRefused() throws Exception {
        UUID sku = UUID.randomUUID();
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));
        intake("admin", body(sku, null, 4)).andExpect(status().isCreated());
        UUID own = sellerWarehouse(SELLER_ID);
        assertThat(row(sku, own)).containsEntry("total_quantity", 4).containsEntry("created_by", ADMIN_ID);

        intake("admin", body(sku, warehouseId("WH_001"), 4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_SKU_HOME"));
        intake("admin", body(sku, own, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(5));
        assertThat(rowCount(sku)).isEqualTo(1);
    }

    @Test
    void sellerGets403ForAnotherSellersOrUnknownSkuWithoutTouchingStock() throws Exception {
        UUID foreign = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(skuOwners.sellerOf(foreign)).thenReturn(Optional.of(UUID.randomUUID()));
        when(skuOwners.sellerOf(unknown)).thenReturn(Optional.empty());

        for (UUID sku : List.of(foreign, unknown)) {
            intake("seller", body(sku, null, 30))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("INVENTORY_FORBIDDEN"));
            assertThat(rowCount(sku)).isZero();
            assertThat(movementCount(sku)).isZero();
        }
    }

    @Test
    void sellerCannotChooseAWarehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("seller", body(sku, warehouseId("WH_001"), 30))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVENTORY_FORBIDDEN"));

        verify(skuOwners, never()).sellerOf(any());
        assertThat(rowCount(sku)).isZero();
    }

    @Test
    void sellerIntakeReturns503WhenCatalogCannotConfirmTheOwner() throws Exception {
        UUID sku = UUID.randomUUID();
        when(skuOwners.sellerOf(sku)).thenThrow(new DomainException(InventoryErrorCode.CATALOG_UNAVAILABLE));
        intake("seller", body(sku, null, 30))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_CATALOG_UNAVAILABLE"));

        assertThat(rowCount(sku)).isZero();
    }

    @Test
    void adminIntakeForASkuWithoutAKnownOwnerUsesTheDefaultWarehouse() throws Exception {
        UUID sku = UUID.randomUUID();
        intake("admin", body(sku, null, 5)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warehouseId").value(defaultWarehouse.toString()));
        verify(skuOwners).sellerOf(sku);
    }

    private ResultActions intake(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/inventory/intake").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(UUID sku, UUID warehouse, int quantity) {
        String warehouseField = warehouse == null ? "" : ",\"warehouseId\":\"" + warehouse + "\"";
        return "{\"skuId\":\"" + sku + "\"" + warehouseField + ",\"quantity\":" + quantity + "}";
    }

    private UUID warehouseId(String code) {
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE code = ?", UUID.class, code);
    }

    private UUID sellerWarehouse(UUID seller) {
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE seller_id = ?", UUID.class, seller);
    }

    private Map<String, Object> row(UUID sku, UUID warehouse) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?", sku, warehouse);
    }

    private int rowCount(UUID sku) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_balances WHERE sku_id = ?", Integer.class, sku);
    }

    private int movementCount(UUID sku) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE sku_id = ?", Integer.class, sku);
    }

    private Integer intakeSum(UUID sku) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0)::int FROM inventory_movements
                WHERE sku_id = ? AND movement_type = 'INTAKE'
                """, Integer.class, sku);
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
