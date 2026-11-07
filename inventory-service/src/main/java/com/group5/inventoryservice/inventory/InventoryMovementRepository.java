package com.group5.inventoryservice.inventory;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

// KẾ THỪA Repository (KHÔNG PHẢI JpaRepository) -> KHÔNG CÓ save/delete, SỔ KHO CHỈ CÓ INSERT VÀ ĐỌC
public interface InventoryMovementRepository extends Repository<InventoryMovement, UUID> {

    default void record(UUID skuId, UUID warehouseId, MovementType type, int quantity, UUID referenceId,
                        UUID actor) {
        insert(skuId, warehouseId, type.name(), quantity, referenceId, actor);
    }

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO inventory_movements
                (sku_id, warehouse_id, movement_type, quantity, reference_id, created_by, updated_by)
            VALUES (:skuId, :warehouseId, CAST(:type AS inventory_movement_type), :quantity, :referenceId,
                    :actor, :actor)
            """)
    int insert(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId, @Param("type") String type,
               @Param("quantity") int quantity, @Param("referenceId") UUID referenceId, @Param("actor") UUID actor);

    List<InventoryMovement> findBySkuIdAndWarehouseIdOrderByCreatedAtAscIdAsc(UUID skuId, UUID warehouseId);
}
