package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.AdjustmentRequest;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.IntakeRequest;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final WarehouseResolver warehouses;

    public InventoryService(InventoryBalanceRepository balances, InventoryMovementRepository movements,
                            WarehouseResolver warehouses) {
        this.balances = balances;
        this.movements = movements;
        this.warehouses = warehouses;
    }

    @Transactional
    public BalanceResponse intake(IntakeRequest request, CurrentUser currentUser) {
        UUID warehouseId = warehouses.resolve(request.warehouseId());
        balances.intake(request.skuId(), warehouseId, request.quantity(), currentUser.id());
        // ĐỌC LẠI DÒNG SAU KHI UPSERT ĐỂ TRẢ VỀ SỐ MỚI NHẤT (clearAutomatically ĐÃ XÓA CACHE CŨ)
        InventoryBalance balance = balances.findBySkuIdAndWarehouseId(request.skuId(), warehouseId).orElseThrow();
        // GHI SỔ KHO CÙNG TRANSACTION VỚI SỐ TỒN -> LỖI Ở ĐÂU THÌ CẢ 2 CÙNG ROLLBACK, KHÔNG LỆCH NHAU
        movements.record(request.skuId(), warehouseId, MovementType.INTAKE, request.quantity(), balance.getId(),
                currentUser.id());
        return BalanceResponse.from(balance);
    }

    @Transactional
    public BalanceResponse adjust(AdjustmentRequest request, CurrentUser currentUser) {
        UUID warehouseId = warehouses.resolve(request.warehouseId());
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
}
