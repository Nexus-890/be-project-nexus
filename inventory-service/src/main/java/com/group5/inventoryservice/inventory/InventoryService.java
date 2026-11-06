package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.IntakeRequest;
import com.group5.inventoryservice.shared.config.InventoryProperties;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryBalanceRepository balances;
    private final WarehouseRepository warehouses;
    private final InventoryProperties properties;

    public InventoryService(InventoryBalanceRepository balances, WarehouseRepository warehouses,
                            InventoryProperties properties) {
        this.balances = balances;
        this.warehouses = warehouses;
        this.properties = properties;
    }

    @Transactional
    public BalanceResponse intake(IntakeRequest request, CurrentUser currentUser) {
        UUID warehouseId = resolveWarehouse(request.warehouseId());
        balances.intake(request.skuId(), warehouseId, request.quantity(), currentUser.id());
        // ĐỌC LẠI DÒNG SAU KHI UPSERT ĐỂ TRẢ VỀ SỐ MỚI NHẤT (clearAutomatically ĐÃ XÓA CACHE CŨ)
        return balances.findBySkuIdAndWarehouseId(request.skuId(), warehouseId)
                .map(BalanceResponse::from)
                .orElseThrow();
    }

    private UUID resolveWarehouse(UUID warehouseId) {
        if (warehouseId != null) {
            if (!warehouses.existsById(warehouseId)) {
                throw new DomainException(InventoryErrorCode.WAREHOUSE_NOT_FOUND);
            }
            return warehouseId;
        }
        // KHÔNG GỬI warehouseId -> LẤY KHO MẶC ĐỊNH THEO CODE TRONG CONFIG (WH_001), KHÔNG HARDCODE UUID
        return warehouses.findByCode(properties.defaultWarehouseCode())
                .map(Warehouse::getId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.DEFAULT_WAREHOUSE_NOT_FOUND));
    }
}
