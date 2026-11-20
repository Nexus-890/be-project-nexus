package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.shared.catalog.CatalogSkuOwners;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/inventory-support-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.inventory.default-warehouse-code=WH_SUP",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class InventorySupportIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID SUPPORT_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    private static final UUID OTHER_SELLER_ID = UUID.randomUUID();
    private static final UUID BUYER_ID = UUID.randomUUID();
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 10, 8, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private CatalogSkuOwners skuOwners;
    private MockMvc mvc;
    private UUID warehouse;
    private UUID otherWarehouse;

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
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_SUP', 'Support test') ON CONFLICT (code) DO NOTHING");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_SUP_2', 'Second') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_SUP'", UUID.class);
        otherWarehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_SUP_2'", UUID.class);
        reset(decoder, skuOwners);
        when(decoder.decode("admin")).thenReturn(token("admin", ADMIN_ID, "ADMIN"));
        when(decoder.decode("support")).thenReturn(token("support", SUPPORT_ID, "SUPPORT_STAFF"));
        when(decoder.decode("seller")).thenReturn(token("seller", SELLER_ID, "SELLER"));
        when(decoder.decode("buyer")).thenReturn(token("buyer", BUYER_ID, "BUYER"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void anAdjustmentOfMinusTwoLowersTotalAndAvailableByTwoAndWritesOneAdjustmentMovementWithTheReason()
            throws Exception {
        UUID sku = balance(warehouse, 10, 10, 0);

        adjust("admin", adjustment(sku, warehouse, -2, "  Damaged in storage  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.skuId").value(sku.toString()))
                .andExpect(jsonPath("$.data.warehouseId").value(warehouse.toString()))
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.available").value(8))
                .andExpect(jsonPath("$.data.reserved").value(0))
                .andExpect(jsonPath("$.data.unavailable").value(0));

        assertThat(row(sku, warehouse)).containsEntry("total_quantity", 8).containsEntry("available_quantity", 8)
                .containsEntry("reserved_quantity", 0).containsEntry("version", 1L)
                .containsEntry("updated_by", ADMIN_ID);
        List<Map<String, Object>> movements = jdbc.queryForList("""
                SELECT movement_type::text AS type, quantity, reason, reference_id, created_by
                FROM inventory_movements WHERE sku_id = ?
                """, sku);
        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst()).containsEntry("type", "ADJUSTMENT").containsEntry("quantity", -2)
                .containsEntry("reason", "Damaged in storage").containsEntry("created_by", ADMIN_ID)
                .containsEntry("reference_id", row(sku, warehouse).get("id"));
    }

    @Test
    void aPositiveAdjustmentRaisesTotalAndAvailableAndLeavesReservedAlone() throws Exception {
        UUID sku = balance(warehouse, 10, 4, 6);

        adjust("admin", adjustment(sku, null, 3, "Recount found three more"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.available").value(7))
                .andExpect(jsonPath("$.data.reserved").value(6));

        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_movements WHERE sku_id = ?", Integer.class, sku))
                .isEqualTo(3);
    }

    @Test
    void anAdjustmentThatWouldMakeAvailableNegativeReturns409AndChangesNothing() throws Exception {
        UUID sku = balance(warehouse, 10, 3, 7);

        adjust("admin", adjustment(sku, warehouse, -4, "Lost"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVENTORY_NEGATIVE_QUANTITY"));
        adjust("admin", adjustment(sku, warehouse, -3, "Lost"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.available").value(0))
                .andExpect(jsonPath("$.data.total").value(7));

        assertThat(row(sku, warehouse)).containsEntry("available_quantity", 0).containsEntry("reserved_quantity", 7)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE sku_id = ?", Integer.class, sku))
                .isEqualTo(1);
    }

    @Test
    void anAdjustmentWithNoBalanceRowOrAnUnknownWarehouseReturns404() throws Exception {
        adjust("admin", adjustment(UUID.randomUUID(), warehouse, 5, "First stock"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_BALANCE_NOT_FOUND"));
        adjust("admin", adjustment(UUID.randomUUID(), UUID.randomUUID(), -1, "Lost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_WAREHOUSE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class)).isZero();
    }

    @Test
    void anAdjustmentRequestIsValidated() throws Exception {
        UUID sku = balance(warehouse, 10, 10, 0);

        adjust("admin", adjustment(sku, warehouse, 0, "Nothing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("quantityDelta"));
        adjust("admin", adjustment(sku, warehouse, -1, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        adjust("admin", adjustment(sku, warehouse, -1, "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        adjust("admin", adjustment(sku, warehouse, 1_000_001, "Too many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantityDelta"));
        adjust("admin", "{\"warehouseId\":\"" + warehouse + "\",\"quantityDelta\":-1,\"reason\":\"Lost\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skuId"));

        assertThat(row(sku, warehouse)).containsEntry("version", 0L);
    }

    @Test
    void onlyAnAdminCanAdjust() throws Exception {
        UUID sku = balance(warehouse, 10, 10, 0);
        for (String caller : List.of("seller", "support", "buyer")) {
            adjust(caller, adjustment(sku, warehouse, -1, "Lost"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(post("/api/v1/inventory/adjustments").contentType(MediaType.APPLICATION_JSON)
                        .content(adjustment(sku, warehouse, -1, "Lost")))
                .andExpect(status().isUnauthorized());
        assertThat(row(sku, warehouse)).containsEntry("available_quantity", 10).containsEntry("version", 0L);
    }

    @Test
    void theLedgerRefusesAnAdjustmentRowWithoutAReasonOrWithZeroQuantity() {
        UUID sku = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity)
                VALUES (?, ?, 'ADJUSTMENT', -1)
                """, sku, warehouse)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reason)
                VALUES (?, ?, 'ADJUSTMENT', 0, 'Nothing')
                """, sku, warehouse)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity)
                VALUES (?, ?, 'INTAKE', 5)
                """, sku, warehouse);
    }

    @Test
    void anAdminListsEveryBalanceAndFiltersBySkuAndWarehouse() throws Exception {
        UUID first = balance(warehouse, 10, 8, 2);
        UUID second = balance(warehouse, 5, 5, 0);
        balance(otherWarehouse, first, 3, 3, 0);

        fetch("admin", "/api/v1/inventory/balances")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.number").value(0));
        fetch("admin", "/api/v1/inventory/balances?skuId=" + first)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].skuId", contains(first.toString(), first.toString())));
        fetch("admin", "/api/v1/inventory/balances?skuId=" + first + "&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].total").value(10))
                .andExpect(jsonPath("$.data[0].available").value(8))
                .andExpect(jsonPath("$.data[0].reserved").value(2))
                .andExpect(jsonPath("$.data[0].unavailable").value(0));
        fetch("admin", "/api/v1/inventory/balances?warehouseId=" + warehouse + "&size=1&page=1")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.number").value(1));
        fetch("admin", "/api/v1/inventory/balances?skuId=" + second + "&warehouseId=" + otherWarehouse)
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        verify(skuOwners, never()).sellerOf(any());
    }

    @Test
    void aSellerReadsTheBalancesOfTheirOwnSkusOnly() throws Exception {
        UUID own = balance(warehouse, 10, 7, 3);
        UUID foreign = balance(warehouse, 4, 4, 0);
        UUID unknown = balance(warehouse, 1, 1, 0);
        when(skuOwners.sellerOf(own)).thenReturn(Optional.of(SELLER_ID));
        when(skuOwners.sellerOf(foreign)).thenReturn(Optional.of(OTHER_SELLER_ID));
        when(skuOwners.sellerOf(unknown)).thenReturn(Optional.empty());

        fetch("seller", "/api/v1/inventory/balances?skuId=" + own)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuId").value(own.toString()))
                .andExpect(jsonPath("$.data[0].available").value(7));
        for (UUID sku : List.of(foreign, unknown)) {
            fetch("seller", "/api/v1/inventory/balances?skuId=" + sku)
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVENTORY_FORBIDDEN"));
        }
        fetch("seller", "/api/v1/inventory/balances")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void aSellerGets503WhenTheCatalogCannotConfirmTheOwner() throws Exception {
        UUID sku = balance(warehouse, 10, 10, 0);
        when(skuOwners.sellerOf(sku)).thenThrow(new DomainException(InventoryErrorCode.CATALOG_UNAVAILABLE));

        fetch("seller", "/api/v1/inventory/balances?skuId=" + sku)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_CATALOG_UNAVAILABLE"));
    }

    @Test
    void buyersAndSupportCannotReadBalances() throws Exception {
        UUID sku = balance(warehouse, 10, 10, 0);
        for (String caller : List.of("buyer", "support")) {
            fetch(caller, "/api/v1/inventory/balances?skuId=" + sku)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/v1/inventory/balances")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminAndSupportListReservationsNewestFirstAndFilterByOrderSkuAndStatus() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID otherSku = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID oldest = reservation(order, sku, 2, "HELD", T0);
        UUID middle = reservation(order, otherSku, 1, "RELEASED", T0.plusMinutes(1));
        UUID newest = reservation(UUID.randomUUID(), sku, 3, "CONFIRMED", T0.plusMinutes(2));

        for (String caller : List.of("admin", "support")) {
            fetch(caller, "/api/v1/inventory/reservations")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", contains(newest.toString(), middle.toString(),
                            oldest.toString())))
                    .andExpect(jsonPath("$.page.totalElements").value(3));
        }
        fetch("support", "/api/v1/inventory/reservations?orderId=" + order)
                .andExpect(jsonPath("$.data[*].id", contains(middle.toString(), oldest.toString())));
        fetch("support", "/api/v1/inventory/reservations?skuId=" + sku + "&status=HELD")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(oldest.toString()))
                .andExpect(jsonPath("$.data[0].orderId").value(order.toString()))
                .andExpect(jsonPath("$.data[0].skuId").value(sku.toString()))
                .andExpect(jsonPath("$.data[0].warehouseId").value(warehouse.toString()))
                .andExpect(jsonPath("$.data[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].status").value("HELD"))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-11-10T08:30:00Z"))
                .andExpect(jsonPath("$.data[0].releasedAt").isEmpty());
        fetch("support", "/api/v1/inventory/reservations?size=2&page=1")
                .andExpect(jsonPath("$.data[*].id", contains(oldest.toString())))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void reservationListingRejectsBadParametersAndOtherRoles() throws Exception {
        fetch("admin", "/api/v1/inventory/reservations?status=PENDING")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        fetch("admin", "/api/v1/inventory/reservations?orderId=not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        fetch("admin", "/api/v1/inventory/reservations?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String caller : List.of("seller", "buyer")) {
            fetch(caller, "/api/v1/inventory/reservations")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void adminAndSupportReadTheLedgerNewestFirstWithEveryFilter() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        UUID intake = movement(sku, warehouse, "INTAKE", 10, null, null, T0);
        UUID reserve = movement(sku, warehouse, "RESERVE", 2, reference, null, T0.plusMinutes(1));
        UUID adjustment = movement(sku, warehouse, "ADJUSTMENT", -1, null, "Damaged", T0.plusMinutes(2));
        movement(sku, otherWarehouse, "INTAKE", 4, null, null, T0.plusMinutes(3));
        movement(UUID.randomUUID(), warehouse, "INTAKE", 1, null, null, T0.plusMinutes(4));

        for (String caller : List.of("admin", "support")) {
            fetch(caller, "/api/v1/inventory/movements?skuId=" + sku + "&warehouseId=" + warehouse)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", contains(adjustment.toString(), reserve.toString(),
                            intake.toString())));
        }
        fetch("support", "/api/v1/inventory/movements?type=ADJUSTMENT")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(adjustment.toString()))
                .andExpect(jsonPath("$.data[0].movementType").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data[0].quantity").value(-1))
                .andExpect(jsonPath("$.data[0].reason").value("Damaged"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-10T08:02:00Z"));
        fetch("support", "/api/v1/inventory/movements?referenceId=" + reference)
                .andExpect(jsonPath("$.data[*].id", contains(reserve.toString())));
        fetch("support", "/api/v1/inventory/movements?skuId=" + sku
                + "&from=2026-11-10T08:01:00Z&to=2026-11-10T08:03:00Z")
                .andExpect(jsonPath("$.data[*].id", contains(adjustment.toString(), reserve.toString())));
        mvc.perform(get("/api/v1/inventory/movements").param("from", "2026-11-10T15:04:00+07:00")
                        .header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        fetch("support", "/api/v1/inventory/movements?size=2")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    void ledgerReadingRejectsBadParametersAndOtherRoles() throws Exception {
        fetch("admin", "/api/v1/inventory/movements?type=IMPORT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        fetch("admin", "/api/v1/inventory/movements?from=yesterday")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        fetch("admin", "/api/v1/inventory/movements?from=2026-11-10T08:00:00Z&to=2026-11-10T08:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        fetch("admin", "/api/v1/inventory/movements?page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String caller : List.of("seller", "buyer")) {
            fetch(caller, "/api/v1/inventory/movements")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/v1/inventory/movements")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdjustmentShowsUpInTheLedgerRead() throws Exception {
        UUID sku = balance(warehouse, 10, 10, 0);
        adjust("admin", adjustment(sku, warehouse, -2, "Damaged in storage")).andExpect(status().isCreated());

        fetch("support", "/api/v1/inventory/movements?skuId=" + sku + "&type=ADJUSTMENT")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].quantity").value(-2))
                .andExpect(jsonPath("$.data[0].reason").value("Damaged in storage"))
                .andExpect(jsonPath("$.data[0].referenceId").value(row(sku, warehouse).get("id").toString()));
    }

    @Test
    void aSellerPicksThenPacksTheirPaidReservationAndEachStepWritesOneLedgerRowWithoutMovingStock()
            throws Exception {
        UUID order = UUID.randomUUID();
        UUID sku = balance(warehouse, 10, 10, 0);
        UUID held = reservation(order, sku, 3, "CONFIRMED", T0);
        when(skuOwners.sellerOf(sku)).thenReturn(Optional.of(SELLER_ID));

        step("seller", held, "ship").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_PICKED"));
        step("seller", held, "pick").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(held.toString()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.pickedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.shippedAt").isEmpty());
        step("seller", held, "pick").andExpect(status().isOk());
        step("seller", held, "ship").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippedAt").isNotEmpty());
        step("seller", held, "ship").andExpect(status().isOk());

        assertThat(jdbc.queryForList("""
                SELECT movement_type::text AS type, quantity, created_by FROM inventory_movements
                WHERE reference_id = ? ORDER BY created_at, id
                """, held)).containsExactly(
                Map.of("type", "PICK", "quantity", 3, "created_by", SELLER_ID),
                Map.of("type", "SHIP", "quantity", 3, "created_by", SELLER_ID));
        assertThat(row(sku, warehouse)).containsEntry("total_quantity", 10).containsEntry("available_quantity", 10)
                .containsEntry("reserved_quantity", 0);
        assertThat(jdbc.queryForMap("SELECT picked_by, shipped_by FROM inventory_reservations WHERE id = ?", held))
                .containsEntry("picked_by", SELLER_ID).containsEntry("shipped_by", SELLER_ID);
    }

    @Test
    void onlyAPaidReservationCanBePickedAndOnlyItsSellerOrAnAdminMayDoIt() throws Exception {
        UUID order = UUID.randomUUID();
        UUID heldSku = balance(warehouse, 10, 8, 2);
        UUID paidSku = balance(warehouse, 10, 10, 0);
        UUID releasedSku = balance(warehouse, 10, 10, 0);
        UUID held = reservation(order, heldSku, 2, "HELD", T0);
        UUID paid = reservation(order, paidSku, 1, "CONFIRMED", T0.plusMinutes(1));
        UUID released = reservation(order, releasedSku, 1, "RELEASED", T0.plusMinutes(2));
        when(skuOwners.sellerOf(any())).thenReturn(Optional.of(SELLER_ID));
        when(decoder.decode("other")).thenReturn(token("other", OTHER_SELLER_ID, "SELLER"));

        step("seller", held, "pick").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_PICKABLE"));
        step("seller", released, "pick").andExpect(status().isConflict());
        step("seller", released, "ship").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_PICKABLE"));
        step("seller", UUID.randomUUID(), "pick").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_RESERVATION_NOT_FOUND"));
        step("other", paid, "pick").andExpect(status().isForbidden());
        step("buyer", paid, "pick").andExpect(status().isForbidden());
        step("support", paid, "ship").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Long.class)).isZero();

        step("admin", paid, "pick").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT picked_by FROM inventory_reservations WHERE id = ?", UUID.class,
                paid)).isEqualTo(ADMIN_ID);
    }

    @Test
    void aSellerReadsTheFulfilmentStepsOfAnOrderOnlyWhenEverySkuIsTheirs() throws Exception {
        UUID order = UUID.randomUUID();
        UUID mine = balance(warehouse, 10, 10, 0);
        UUID theirs = balance(warehouse, 10, 10, 0);
        UUID first = reservation(order, mine, 1, "CONFIRMED", T0);
        when(skuOwners.sellerOf(mine)).thenReturn(Optional.of(SELLER_ID));
        when(skuOwners.sellerOf(theirs)).thenReturn(Optional.of(OTHER_SELLER_ID));

        fetch("seller", "/api/v1/inventory/reservations/fulfillment?orderId=" + order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(first.toString()))
                .andExpect(jsonPath("$.data[0].pickedAt").isEmpty());
        fetch("seller", "/api/v1/inventory/reservations/fulfillment?orderId=" + UUID.randomUUID())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        reservation(order, theirs, 1, "CONFIRMED", T0.plusMinutes(1));
        fetch("seller", "/api/v1/inventory/reservations/fulfillment?orderId=" + order)
                .andExpect(status().isForbidden());
        fetch("admin", "/api/v1/inventory/reservations/fulfillment?orderId=" + order)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        fetch("buyer", "/api/v1/inventory/reservations/fulfillment?orderId=" + order)
                .andExpect(status().isForbidden());
        fetch("seller", "/api/v1/inventory/reservations/fulfillment")
                .andExpect(status().isBadRequest());
    }

    private ResultActions step(String caller, UUID reservationId, String action) throws Exception {
        return mvc.perform(post("/api/v1/inventory/reservations/" + reservationId + "/" + action)
                .header("Authorization", "Bearer " + caller));
    }

    private ResultActions adjust(String caller, String body) throws Exception {
        return mvc.perform(post("/api/v1/inventory/adjustments").header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions fetch(String caller, String path) throws Exception {
        return mvc.perform(get(path)
                .header("Authorization", "Bearer " + caller));
    }

    private static String adjustment(UUID sku, UUID warehouseId, int delta, String reason) {
        return "{\"skuId\":\"" + sku + "\","
                + (warehouseId == null ? "" : "\"warehouseId\":\"" + warehouseId + "\",")
                + "\"quantityDelta\":" + delta + ",\"reason\":\"" + reason + "\"}";
    }

    private UUID balance(UUID warehouseId, int total, int available, int reserved) {
        return balance(warehouseId, UUID.randomUUID(), total, available, reserved);
    }

    private UUID balance(UUID warehouseId, UUID sku, int total, int available, int reserved) {
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity,
                                                reserved_quantity)
                VALUES (?, ?, ?, ?, ?)
                """, sku, warehouseId, total, available, reserved);
        return sku;
    }

    private Map<String, Object> row(UUID sku, UUID warehouseId) {
        return jdbc.queryForMap("SELECT * FROM inventory_balances WHERE sku_id = ? AND warehouse_id = ?",
                sku, warehouseId);
    }

    private UUID reservation(UUID order, UUID sku, int quantity, String status, OffsetDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_reservations (order_id, sku_id, warehouse_id, quantity, status, expires_at,
                                                    created_at)
                VALUES (?, ?, ?, ?, CAST(? AS inventory_reservation_status), ?, ?)
                RETURNING id
                """, UUID.class, order, sku, warehouse, quantity, status,
                Timestamp.from(createdAt.plusMinutes(30).toInstant()), Timestamp.from(createdAt.toInstant()));
    }

    private UUID movement(UUID sku, UUID warehouseId, String type, int quantity, UUID reference, String reason,
                          OffsetDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reference_id,
                                                 reason, created_at)
                VALUES (?, ?, CAST(? AS inventory_movement_type), ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, sku, warehouseId, type, quantity, reference, reason,
                Timestamp.from(createdAt.toInstant()));
    }

    private static Jwt token(String value, UUID userId, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
