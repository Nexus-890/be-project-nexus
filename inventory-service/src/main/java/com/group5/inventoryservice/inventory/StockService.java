package com.group5.inventoryservice.inventory;

import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.inventory.dto.StockResponse;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StockService {

    public static final int MAX_BATCH_SIZE = 100;

    private final InventoryBalanceRepository balances;
    private final WarehouseResolver warehouses;

    public StockService(InventoryBalanceRepository balances, WarehouseResolver warehouses) {
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
        Map<UUID, Integer> found = balances.findAvailable(warehouses.defaultWarehouseId(), distinct).stream()
                .collect(Collectors.toMap(StockResponse::skuId, StockResponse::available));
        return distinct.stream()
                .map(skuId -> new StockResponse(skuId, found.getOrDefault(skuId, 0)))
                .toList();
    }
}
