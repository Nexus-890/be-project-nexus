package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.AdjustmentRequest;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.IntakeRequest;
import com.group5.inventoryservice.shared.catalog.CatalogSkuOwners;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import com.group5.inventoryservice.warehouse.service.SellerWarehouses;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class InventoryService {

    static final String TRANSFER_REASON = "Moved to the seller warehouse";

    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final WarehouseResolver warehouses;
    private final CatalogSkuOwners skuOwners;
    private final SellerWarehouses sellerWarehouses;
    private final SkuWarehouses skuWarehouses;
    private final JdbcTemplate jdbc;

    public InventoryService(InventoryBalanceRepository balances, InventoryMovementRepository movements,
                            WarehouseResolver warehouses, CatalogSkuOwners skuOwners,
                            SellerWarehouses sellerWarehouses, SkuWarehouses skuWarehouses, JdbcTemplate jdbc) {
        this.balances = balances;
        this.movements = movements;
        this.warehouses = warehouses;
        this.skuOwners = skuOwners;
        this.sellerWarehouses = sellerWarehouses;
        this.skuWarehouses = skuWarehouses;
        this.jdbc = jdbc;
    }

    public void requireIntakeAccess(IntakeRequest request, CurrentUser currentUser) {
        if (currentUser.hasRole("ADMIN")) {
            return;
        }
        if (request.warehouseId() != null) {
            throw new DomainException(InventoryErrorCode.FORBIDDEN,
                    "Sellers can only add stock to their own warehouse");
        }
        if (skuOwners.sellerOf(request.skuId()).filter(currentUser.id()::equals).isEmpty()) {
            throw new DomainException(InventoryErrorCode.FORBIDDEN);
        }
    }

    @Transactional
    public BalanceResponse intake(IntakeRequest request, CurrentUser currentUser) {
        UUID warehouseId = intakeWarehouse(request, currentUser);
        balances.intake(request.skuId(), warehouseId, request.quantity(), currentUser.id());
        // ĐỌC LẠI DÒNG SAU KHI UPSERT ĐỂ TRẢ VỀ SỐ MỚI NHẤT (clearAutomatically ĐÃ XÓA CACHE CŨ)
        InventoryBalance balance = balances.findBySkuIdAndWarehouseId(request.skuId(), warehouseId).orElseThrow();
        // GHI SỔ KHO CÙNG TRANSACTION VỚI SỐ TỒN -> LỖI Ở ĐÂU THÌ CẢ 2 CÙNG ROLLBACK, KHÔNG LỆCH NHAU
        movements.record(request.skuId(), warehouseId, MovementType.INTAKE, request.quantity(), balance.getId(),
                currentUser.id());
        return BalanceResponse.from(moveSharedStock(request.skuId(), balance, currentUser.id()));
    }

    @Transactional
    public BalanceResponse adjust(AdjustmentRequest request, CurrentUser currentUser) {
        UUID warehouseId = request.warehouseId() == null
                ? skuWarehouses.warehouseFor(request.skuId())
                : warehouses.resolve(request.warehouseId());
        int delta = request.quantityDelta();
        if (balances.adjust(request.skuId(), warehouseId, delta, currentUser.id()) == 0) {
            throw balances.findBySkuIdAndWarehouseId(request.skuId(), warehouseId).isPresent()
                    ? new DomainException(InventoryErrorCode.NEGATIVE_QUANTITY)
                    : new DomainException(InventoryErrorCode.BALANCE_NOT_FOUND);
        }
        InventoryBalance balance = balances.findBySkuIdAndWarehouseId(request.skuId(), warehouseId).orElseThrow();
        movements.recordAdjustment(request.skuId(), warehouseId, delta, balance.getId(), request.reason().strip(),
                currentUser.id());
        return BalanceResponse.from(balance);
    }

    private UUID intakeWarehouse(IntakeRequest request, CurrentUser currentUser) {
        if (!currentUser.hasRole("ADMIN")) {
            return sellerWarehouses.forIntake(currentUser.id()).getId();
        }
        if (request.warehouseId() != null) {
            UUID chosen = warehouses.resolveForIntake(request.warehouseId());
            SkuWarehouses.Home home = skuWarehouses.home(request.skuId());
            if (home != null && !home.warehouseId().equals(chosen)) {
                throw new DomainException(InventoryErrorCode.WAREHOUSE_NOT_SKU_HOME);
            }
            return chosen;
        }
        Optional<UUID> owner = skuOwners.sellerOf(request.skuId());
        if (owner.isPresent()) {
            return sellerWarehouses.forIntake(owner.get()).getId();
        }
        SkuWarehouses.Home home = skuWarehouses.home(request.skuId());
        return warehouses.resolveForIntake(home == null ? null : home.warehouseId());
    }

    private InventoryBalance moveSharedStock(UUID skuId, InventoryBalance target, UUID actor) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT seller_id IS NOT NULL FROM warehouses WHERE id = ?", Boolean.class, target.getWarehouseId()))) {
            return target;
        }
        var moved = jdbc.query("""
                WITH shared AS (
                    SELECT b.id, b.available_quantity AS quantity
                    FROM inventory_balances b
                    JOIN warehouses w ON w.id = b.warehouse_id
                    WHERE b.sku_id = ? AND w.seller_id IS NULL AND b.available_quantity > 0
                    FOR UPDATE OF b
                )
                UPDATE inventory_balances b
                SET total_quantity = b.total_quantity - shared.quantity,
                    available_quantity = b.available_quantity - shared.quantity,
                    updated_by = ?, updated_at = now(), version = b.version + 1
                FROM shared
                WHERE b.id = shared.id
                RETURNING b.id, b.warehouse_id, shared.quantity
                """, (row, number) -> new Moved(row.getObject("id", UUID.class),
                row.getObject("warehouse_id", UUID.class), row.getInt("quantity")), skuId, actor);
        if (moved.isEmpty()) {
            return target;
        }
        for (Moved from : moved) {
            movements.insert(skuId, from.warehouseId(), MovementType.TRANSFER.name(), -from.quantity(),
                    target.getId(), TRANSFER_REASON, actor);
            balances.intake(skuId, target.getWarehouseId(), from.quantity(), actor);
            movements.insert(skuId, target.getWarehouseId(), MovementType.TRANSFER.name(), from.quantity(),
                    from.balanceId(), TRANSFER_REASON, actor);
        }
        return balances.findBySkuIdAndWarehouseId(skuId, target.getWarehouseId()).orElseThrow();
    }

    private record Moved(UUID balanceId, UUID warehouseId, int quantity) {
    }
}
