package com.group5.inventoryservice.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SellerStockMover {

    static final String TRANSFER_REASON = "Moved to the seller warehouse";
    static final String LATE_PAYMENT_REASON = "Moved back for a late payment";

    private final JdbcTemplate jdbc;
    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final SkuWarehouses skuWarehouses;

    public SellerStockMover(JdbcTemplate jdbc, InventoryBalanceRepository balances,
                            InventoryMovementRepository movements, SkuWarehouses skuWarehouses) {
        this.jdbc = jdbc;
        this.balances = balances;
        this.movements = movements;
        this.skuWarehouses = skuWarehouses;
    }

    public void lockShared(UUID skuId) {
        jdbc.query("""
                SELECT b.id FROM inventory_balances b
                JOIN warehouses w ON w.id = b.warehouse_id
                WHERE b.sku_id = ? AND w.seller_id IS NULL
                ORDER BY b.warehouse_id
                FOR UPDATE OF b
                """, row -> { }, skuId);
    }

    public void afterReturn(UUID skuId, UUID warehouseId, UUID actor) {
        SkuWarehouses.Home home = skuWarehouses.home(skuId);
        if (home == null || !home.sellerOwned() || home.warehouseId().equals(warehouseId)) {
            return;
        }
        balances.findBySkuIdAndWarehouseId(skuId, home.warehouseId())
                .ifPresent(target -> moveShared(skuId, target, actor));
    }

    public void moveInto(UUID skuId, UUID warehouseId, UUID actor) {
        lockShared(skuId);
        balances.intake(skuId, warehouseId, 0, actor);
        moveShared(skuId, balances.findBySkuIdAndWarehouseId(skuId, warehouseId).orElseThrow(), actor);
    }

    public void moveBack(UUID skuId, UUID fromWarehouseId, UUID toWarehouseId, int quantity, UUID actor) {
        InventoryBalance from = balances.findBySkuIdAndWarehouseId(skuId, fromWarehouseId).orElseThrow();
        InventoryBalance to = balances.findBySkuIdAndWarehouseId(skuId, toWarehouseId).orElseThrow();
        if (jdbc.update("""
                UPDATE inventory_balances
                SET total_quantity = total_quantity - ?, available_quantity = available_quantity - ?,
                    updated_by = ?, updated_at = now(), version = version + 1
                WHERE id = ? AND available_quantity >= ?
                """, quantity, quantity, actor, from.getId(), quantity) == 0) {
            throw new IllegalStateException("Balance " + from.getId() + " no longer has " + quantity + " available");
        }
        movements.insert(skuId, fromWarehouseId, MovementType.TRANSFER.name(), -quantity, to.getId(),
                LATE_PAYMENT_REASON, actor);
        balances.intake(skuId, toWarehouseId, quantity, actor);
        movements.insert(skuId, toWarehouseId, MovementType.TRANSFER.name(), quantity, from.getId(),
                LATE_PAYMENT_REASON, actor);
    }

    public InventoryBalance moveShared(UUID skuId, InventoryBalance target, UUID actor) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT seller_id IS NOT NULL FROM warehouses WHERE id = ?", Boolean.class, target.getWarehouseId()))) {
            return target;
        }
        lockShared(skuId);
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
