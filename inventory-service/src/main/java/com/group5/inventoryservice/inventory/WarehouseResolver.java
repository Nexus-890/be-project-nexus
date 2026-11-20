package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.shared.config.InventoryProperties;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import com.group5.inventoryservice.warehouse.entity.Warehouse;
import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;
import com.group5.inventoryservice.warehouse.repository.WarehouseRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WarehouseResolver {

    private final WarehouseRepository warehouses;
    private final InventoryProperties properties;

    public WarehouseResolver(WarehouseRepository warehouses, InventoryProperties properties) {
        this.warehouses = warehouses;
        this.properties = properties;
    }

    public UUID sellerOf(UUID warehouseId) {
        return warehouses.findById(warehouseId).map(Warehouse::getSellerId).orElse(null);
    }

    public UUID resolveForIntake(UUID warehouseId) {
        Warehouse warehouse;
        if (warehouseId != null) {
            warehouse = warehouses.findByIdForIntake(warehouseId)
                    .orElseThrow(() -> new DomainException(InventoryErrorCode.WAREHOUSE_NOT_FOUND));
        } else {
            warehouse = warehouses.findByCodeForIntake(properties.defaultWarehouseCode())
                    .orElseThrow(() -> new DomainException(InventoryErrorCode.DEFAULT_WAREHOUSE_NOT_FOUND));
        }
        if (warehouse.getStatus() != WarehouseStatus.ACTIVE) {
            throw new DomainException(InventoryErrorCode.WAREHOUSE_UNAVAILABLE);
        }
        return warehouse.getId();
    }

    public UUID resolve(UUID warehouseId) {
        if (warehouseId == null) {
            return defaultWarehouseId();
        }
        if (!warehouses.existsById(warehouseId)) {
            throw new DomainException(InventoryErrorCode.WAREHOUSE_NOT_FOUND);
        }
        return warehouseId;
    }

    public UUID defaultWarehouseId() {
        // KHÔNG GỬI warehouseId -> LẤY KHO MẶC ĐỊNH THEO CODE TRONG CONFIG (WH_001), KHÔNG HARDCODE UUID
        return warehouses.findByCode(properties.defaultWarehouseCode())
                .map(Warehouse::getId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.DEFAULT_WAREHOUSE_NOT_FOUND));
    }
}
