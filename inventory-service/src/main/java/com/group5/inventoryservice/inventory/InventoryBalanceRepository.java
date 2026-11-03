package com.group5.inventoryservice.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Optional<InventoryBalance> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId);
}
