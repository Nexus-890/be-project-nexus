package com.group5.inventoryservice.inventory;

import com.group5.common_core.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// SỔ KHO CHỈ THÊM DÒNG: KHÔNG SETTER, @Immutable -> HIBERNATE KHÔNG BAO GIỜ PHÁT UPDATE CHO BẢNG NÀY
@Getter
@Immutable
@Entity
@Table(name = "inventory_movements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryMovement extends AuditedEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "movement_type", nullable = false, columnDefinition = "inventory_movement_type")
    private MovementType movementType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
