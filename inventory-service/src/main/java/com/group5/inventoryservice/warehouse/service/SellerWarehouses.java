package com.group5.inventoryservice.warehouse.service;

import com.group5.common_core.exception.DomainException;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import com.group5.inventoryservice.warehouse.entity.Warehouse;
import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;
import com.group5.inventoryservice.warehouse.repository.WarehouseRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SellerWarehouses {

    public static final String DEFAULT_NAME = "Seller warehouse";

    private final JdbcTemplate jdbc;
    private final WarehouseRepository warehouses;

    public SellerWarehouses(JdbcTemplate jdbc, WarehouseRepository warehouses) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
    }

    public static String codeFor(UUID sellerId) {
        return "SELLER-" + sellerId.toString().replace("-", "").toUpperCase();
    }

    public void ensure(UUID sellerId, String name, String address) {
        jdbc.update("""
                INSERT INTO warehouses (code, name, address, seller_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, codeFor(sellerId), name, address, sellerId, sellerId, sellerId);
    }

    public Warehouse forIntake(UUID sellerId) {
        ensure(sellerId, DEFAULT_NAME, null);
        Warehouse warehouse = warehouses.findBySellerIdForIntake(sellerId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.DUPLICATE_WAREHOUSE_CODE));
        if (warehouse.getStatus() != WarehouseStatus.ACTIVE) {
            throw new DomainException(InventoryErrorCode.WAREHOUSE_UNAVAILABLE);
        }
        return warehouse;
    }
}
