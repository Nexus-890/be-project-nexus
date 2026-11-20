package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.AdjustmentRequest;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.IntakeRequest;
import com.group5.inventoryservice.shared.catalog.CatalogSkuOwners;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import com.group5.inventoryservice.warehouse.service.SellerWarehouses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final WarehouseResolver warehouses;
    private final CatalogSkuOwners skuOwners;
    private final SellerWarehouses sellerWarehouses;
    private final SkuWarehouses skuWarehouses;
    private final SellerStockMover stockMover;

    public InventoryService(InventoryBalanceRepository balances, InventoryMovementRepository movements,
                            WarehouseResolver warehouses, CatalogSkuOwners skuOwners,
                            SellerWarehouses sellerWarehouses, SkuWarehouses skuWarehouses,
                            SellerStockMover stockMover) {
        this.balances = balances;
        this.movements = movements;
        this.warehouses = warehouses;
        this.skuOwners = skuOwners;
        this.sellerWarehouses = sellerWarehouses;
        this.skuWarehouses = skuWarehouses;
        this.stockMover = stockMover;
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
        stockMover.lockShared(request.skuId());
        balances.intake(request.skuId(), warehouseId, request.quantity(), currentUser.id());
        // ĐỌC LẠI DÒNG SAU KHI UPSERT ĐỂ TRẢ VỀ SỐ MỚI NHẤT (clearAutomatically ĐÃ XÓA CACHE CŨ)
        InventoryBalance balance = balances.findBySkuIdAndWarehouseId(request.skuId(), warehouseId).orElseThrow();
        // GHI SỔ KHO CÙNG TRANSACTION VỚI SỐ TỒN -> LỖI Ở ĐÂU THÌ CẢ 2 CÙNG ROLLBACK, KHÔNG LỆCH NHAU
        movements.record(request.skuId(), warehouseId, MovementType.INTAKE, request.quantity(), balance.getId(),
                currentUser.id());
        return BalanceResponse.from(stockMover.moveShared(request.skuId(), balance, currentUser.id()));
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
            UUID chosenSeller = warehouses.sellerOf(chosen);
            if (chosenSeller != null && skuOwners.sellerOf(request.skuId()).filter(chosenSeller::equals).isEmpty()) {
                throw new DomainException(InventoryErrorCode.WAREHOUSE_NOT_SKU_HOME,
                        "This warehouse belongs to another seller");
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
}
