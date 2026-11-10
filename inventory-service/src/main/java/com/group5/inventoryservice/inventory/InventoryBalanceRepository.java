package com.group5.inventoryservice.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Optional<InventoryBalance> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId);

    // 1 CÂU SQL DUY NHẤT: CHƯA CÓ DÒNG (SKU + KHO) THÌ INSERT, CÓ RỒI THÌ CỘNG DỒN TOTAL VÀ AVAILABLE
    // 2 REQUEST NHẬP KHO CÙNG LÚC -> POSTGRES TỰ XẾP HÀNG, KHÔNG TẠO 2 DÒNG, KHÔNG MẤT SỐ LƯỢNG
    // SQL NATIVE KHÔNG ĐI QUA @Version NÊN PHẢI TỰ TĂNG version + 1
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO inventory_balances
                (sku_id, warehouse_id, total_quantity, available_quantity, created_by, updated_by)
            VALUES (:skuId, :warehouseId, :quantity, :quantity, :actor, :actor)
            ON CONFLICT (sku_id, warehouse_id) DO UPDATE
            SET total_quantity     = inventory_balances.total_quantity + EXCLUDED.total_quantity,
                available_quantity = inventory_balances.available_quantity + EXCLUDED.available_quantity,
                updated_by         = EXCLUDED.updated_by,
                updated_at         = now(),
                version            = inventory_balances.version + 1
            """)
    int intake(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId,
               @Param("quantity") int quantity, @Param("actor") UUID actor);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE inventory_balances
            SET available_quantity = available_quantity - :quantity,
                reserved_quantity  = reserved_quantity + :quantity,
                updated_by         = :actor,
                updated_at         = now(),
                version            = version + 1
            WHERE sku_id = :skuId AND warehouse_id = :warehouseId AND available_quantity >= :quantity
            """)
    int reserve(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId,
                @Param("quantity") int quantity, @Param("actor") UUID actor);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE inventory_balances
            SET reserved_quantity    = reserved_quantity - :quantity,
                unavailable_quantity = unavailable_quantity + :quantity,
                updated_by           = :actor,
                updated_at           = now(),
                version              = version + 1
            WHERE sku_id = :skuId AND warehouse_id = :warehouseId AND reserved_quantity >= :quantity
            """)
    int commitReserved(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId,
                       @Param("quantity") int quantity, @Param("actor") UUID actor);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE inventory_balances
            SET reserved_quantity  = reserved_quantity - :quantity,
                available_quantity = available_quantity + :quantity,
                updated_by         = :actor,
                updated_at         = now(),
                version            = version + 1
            WHERE sku_id = :skuId AND warehouse_id = :warehouseId AND reserved_quantity >= :quantity
            """)
    int releaseReserved(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId,
                        @Param("quantity") int quantity, @Param("actor") UUID actor);
}
