package com.group5.inventoryservice.inventory;

import com.group5.inventoryservice.inventory.dto.StockResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class StockService {

    private final InventoryBalanceRepository balances;
    private final WarehouseResolver warehouses;

    public StockService(InventoryBalanceRepository balances, WarehouseResolver warehouses) {
        this.balances = balances;
        this.warehouses = warehouses;
    }

    @Transactional(readOnly = true)
    public StockResponse available(UUID skuId) {
        return balances.findAvailable(warehouses.defaultWarehouseId(), List.of(skuId)).stream()
                .findFirst()
                .orElse(new StockResponse(skuId, 0));
    }
}
