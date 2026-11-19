package com.group5.inventoryservice.warehouse.service;

import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.inventory.SkuWarehouses;
import com.group5.inventoryservice.warehouse.entity.Warehouse;
import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;
import com.group5.inventoryservice.warehouse.repository.WarehouseRepository;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import com.group5.inventoryservice.warehouse.dto.request.CreateWarehouseRequest;
import com.group5.inventoryservice.warehouse.dto.request.UpdateOwnWarehouseRequest;
import com.group5.inventoryservice.warehouse.dto.request.UpdateWarehouseRequest;
import com.group5.inventoryservice.warehouse.dto.response.WarehousePickupResponse;
import com.group5.inventoryservice.warehouse.dto.response.WarehouseResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WarehouseService {

    private final WarehouseRepository warehouses;
    private final SellerWarehouses sellerWarehouses;
    private final SkuWarehouses skuWarehouses;

    public WarehouseService(WarehouseRepository warehouses, SellerWarehouses sellerWarehouses,
                            SkuWarehouses skuWarehouses) {
        this.warehouses = warehouses;
        this.sellerWarehouses = sellerWarehouses;
        this.skuWarehouses = skuWarehouses;
    }

    @Transactional
    public WarehouseResponse create(CreateWarehouseRequest request) {
        String code = request.code().trim();
        if (warehouses.existsByCode(code)) {
            throw new DomainException(InventoryErrorCode.DUPLICATE_WAREHOUSE_CODE);
        }
        // Hai request cùng lúc đi qua existsByCode() được -> nên sài cái saveWarehouse để bắt lỗi
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(request.name().trim());
        warehouse.setAddress(request.address());
        warehouse.setStatus(WarehouseStatus.ACTIVE);
        Warehouse saved = saveWarehouse(warehouse);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list(WarehouseStatus status) {
        Sort sort = Sort.by("code");
        List<Warehouse> result = status == null
                ? warehouses.findAll(sort)
                : warehouses.findAllByStatus(status, sort);
        return result.stream().map(WarehouseService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseResponse getById(UUID id) {
        Warehouse warehouse = warehouses.findById(id)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.WAREHOUSE_NOT_FOUND));
        return toResponse(warehouse);
    }

    @Transactional(readOnly = true)
    public WarehouseResponse mine(UUID sellerId) {
        return warehouses.findBySellerId(sellerId).map(WarehouseService::toResponse)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.WAREHOUSE_NOT_FOUND,
                        "Your warehouse is created with your first stock intake or when you save its details"));
    }

    @Transactional
    public WarehouseResponse updateMine(UUID sellerId, UpdateOwnWarehouseRequest request) {
        String address = request.address() == null || request.address().isBlank() ? null : request.address().strip();
        sellerWarehouses.ensure(sellerId, request.name().strip(), address);
        Warehouse warehouse = warehouses.findBySellerIdForUpdate(sellerId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.DUPLICATE_WAREHOUSE_CODE));
        warehouse.setName(request.name().strip());
        warehouse.setAddress(address);
        warehouse.setProvinceCode(request.provinceCode());
        warehouse.setWardCode(request.wardCode());
        return toResponse(warehouses.saveAndFlush(warehouse));
    }

    @Transactional(readOnly = true)
    public WarehousePickupResponse pickup(UUID sellerId, List<UUID> skuIds) {
        return warehouses.findBySellerId(sellerId)
                .filter(warehouse -> skuIds == null || skuIds.isEmpty()
                        || skuWarehouses.warehousesFor(skuIds).values().stream().allMatch(warehouse.getId()::equals))
                .filter(warehouse -> warehouse.getStatus() == WarehouseStatus.ACTIVE
                        && warehouse.getProvinceCode() != null && warehouse.getWardCode() != null
                        && warehouse.getAddress() != null && !warehouse.getAddress().isBlank()
                        && warehouse.getAddress().length() <= 255)
                .map(warehouse -> new WarehousePickupResponse(warehouse.getId(), sellerId,
                        warehouse.getProvinceCode(), warehouse.getWardCode(), warehouse.getAddress()))
                .orElse(null);
    }

    @Transactional
    public WarehouseResponse update(UUID id, UpdateWarehouseRequest request) {
        Warehouse warehouse = warehouses.findByIdForUpdate(id)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.WAREHOUSE_NOT_FOUND));

        warehouse.setName(request.name().trim());
        warehouse.setAddress(request.address());
        warehouse.setStatus(request.status());
        Warehouse saved = warehouses.saveAndFlush(warehouse);
        return toResponse(saved);
    }

    //chuẩn hóa lại lỗi do handler dưới db trả ra ( ví dụ 2 request WH_02 cùng lúc thì request thứ 2 sẽ ném ra lỗi 500 do field code UNIQUE -> INTERNAL_ERROR -> biến về 409
    private Warehouse saveWarehouse(Warehouse warehouse) {
        try {
            return warehouses.saveAndFlush(warehouse);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateCode(exception)) {
                throw new DomainException(InventoryErrorCode.DUPLICATE_WAREHOUSE_CODE);
            }
            throw exception;
        }
    }
    //này check xem lỗi đó có đúng là lỗi do UNIQUE của WH_Code không
    private boolean isDuplicateCode(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "warehouses_code_key".equals(constraint.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    public static WarehouseResponse toResponse(Warehouse warehouse) {
        return new WarehouseResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(),
                warehouse.getAddress(), warehouse.getStatus(), warehouse.getSellerId(), warehouse.getProvinceCode(),
                warehouse.getWardCode());
    }
}
