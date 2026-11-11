package com.group5.inventoryservice.inventory;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.MovementResponse;
import com.group5.inventoryservice.shared.catalog.CatalogSkuOwners;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    private static final Sort BALANCE_ORDER = Sort.by("skuId", "warehouseId");
    private static final Sort MOVEMENT_ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final CatalogSkuOwners skuOwners;

    public InventoryQueryService(InventoryBalanceRepository balances, InventoryMovementRepository movements,
                                 CatalogSkuOwners skuOwners) {
        this.balances = balances;
        this.movements = movements;
        this.skuOwners = skuOwners;
    }

    public Page<BalanceResponse> balances(UUID skuId, UUID warehouseId, int page, int size, CurrentUser currentUser) {
        if (!currentUser.hasRole("ADMIN")) {
            requireOwnSku(skuId, currentUser);
        }
        return balances.search(skuId, warehouseId, PageRequest.of(page, size, BALANCE_ORDER))
                .map(BalanceResponse::from);
    }

    public Page<MovementResponse> movements(UUID skuId, UUID warehouseId, MovementType type, UUID referenceId,
                                            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new DomainException(CommonErrorCode.INVALID_PARAMETER, "from must be before to");
        }
        return movements.search(skuId, warehouseId, type == null ? null : type.name(), referenceId, from, to,
                PageRequest.of(page, size, MOVEMENT_ORDER)).map(MovementResponse::from);
    }

    private void requireOwnSku(UUID skuId, CurrentUser currentUser) {
        if (skuId == null) {
            throw new DomainException(CommonErrorCode.INVALID_PARAMETER, "A seller must filter by skuId");
        }
        if (!skuOwners.sellerOf(skuId).filter(currentUser.id()::equals).isPresent()) {
            throw new DomainException(InventoryErrorCode.FORBIDDEN);
        }
    }
}
