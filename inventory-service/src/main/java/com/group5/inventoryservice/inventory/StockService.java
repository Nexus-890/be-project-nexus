package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.inventory.dto.StockResponse;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StockService {

    public static final int MAX_BATCH_SIZE = 100;

    private final InventoryBalanceRepository balances;
    private final SkuWarehouses warehouses;

    public StockService(InventoryBalanceRepository balances, SkuWarehouses warehouses) {
        this.balances = balances;
        this.warehouses = warehouses;
    }

    @Transactional(readOnly = true)
    public StockResponse available(UUID skuId) {
        return available(List.of(skuId)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<StockResponse> available(List<UUID> skuIds) {
        if (skuIds.size() > MAX_BATCH_SIZE) {
            throw new DomainException(InventoryErrorCode.TOO_MANY_SKUS);
        }
        List<UUID> distinct = skuIds.stream().distinct().toList();
        Map<UUID, SkuWarehouses.Home> homes = warehouses.homes(distinct);
        return distinct.stream()
                .map(skuId -> new StockResponse(skuId, homes.containsKey(skuId) ? homes.get(skuId).available() : 0))
                .toList();
    }
}
