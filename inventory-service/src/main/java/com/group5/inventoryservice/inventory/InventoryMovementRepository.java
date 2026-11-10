package com.group5.inventoryservice.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// KẾ THỪA Repository (KHÔNG PHẢI JpaRepository) -> KHÔNG CÓ save/delete, SỔ KHO CHỈ CÓ INSERT VÀ ĐỌC
public interface InventoryMovementRepository extends Repository<InventoryMovement, UUID> {

    default void record(UUID skuId, UUID warehouseId, MovementType type, int quantity, UUID referenceId,
                        UUID actor) {
        insert(skuId, warehouseId, type.name(), quantity, referenceId, null, actor);
    }

    default void recordAdjustment(UUID skuId, UUID warehouseId, int quantityDelta, UUID referenceId, String reason,
                                  UUID actor) {
        insert(skuId, warehouseId, MovementType.ADJUSTMENT.name(), quantityDelta, referenceId, reason, actor);
    }

    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO inventory_movements
                (sku_id, warehouse_id, movement_type, quantity, reference_id, reason, created_by, updated_by)
            VALUES (:skuId, :warehouseId, CAST(:type AS inventory_movement_type), :quantity, :referenceId,
                    :reason, :actor, :actor)
            """)
    int insert(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId, @Param("type") String type,
               @Param("quantity") int quantity, @Param("referenceId") UUID referenceId,
               @Param("reason") String reason, @Param("actor") UUID actor);

    List<InventoryMovement> findBySkuIdAndWarehouseIdOrderByCreatedAtAscIdAsc(UUID skuId, UUID warehouseId);

    @Query("""
            SELECT m FROM InventoryMovement m
            WHERE (:skuId IS NULL OR m.skuId = :skuId)
              AND (:warehouseId IS NULL OR m.warehouseId = :warehouseId)
              AND (:type IS NULL OR CAST(m.movementType AS String) = :type)
              AND (:referenceId IS NULL OR m.referenceId = :referenceId)
              AND (CAST(:from AS OffsetDateTime) IS NULL OR m.createdAt >= :from)
              AND (CAST(:to AS OffsetDateTime) IS NULL OR m.createdAt < :to)
            """)
    Page<InventoryMovement> search(@Param("skuId") UUID skuId, @Param("warehouseId") UUID warehouseId,
                                   @Param("type") String type, @Param("referenceId") UUID referenceId,
                                   @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                   Pageable pageable);
}
