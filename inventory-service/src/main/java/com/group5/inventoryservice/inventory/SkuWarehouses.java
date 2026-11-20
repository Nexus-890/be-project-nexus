package com.group5.inventoryservice.inventory;

import com.group5.inventoryservice.shared.config.InventoryProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SkuWarehouses {

    private final JdbcTemplate jdbc;
    private final WarehouseResolver warehouses;
    private final InventoryProperties properties;

    public SkuWarehouses(JdbcTemplate jdbc, WarehouseResolver warehouses, InventoryProperties properties) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.properties = properties;
    }

    public record Home(UUID warehouseId, UUID sellerId, int available) {
        public boolean sellerOwned() {
            return sellerId != null;
        }
    }

    public Map<UUID, Home> homes(Collection<UUID> skuIds) {
        Map<UUID, Home> found = new HashMap<>();
        if (skuIds.isEmpty()) {
            return found;
        }
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT DISTINCT ON (b.sku_id) b.sku_id, b.warehouse_id, w.seller_id, b.available_quantity
                    FROM inventory_balances b
                    JOIN warehouses w ON w.id = b.warehouse_id
                    WHERE b.sku_id = ANY (?)
                    ORDER BY b.sku_id, (w.seller_id IS NULL), (w.code <> ?), b.id
                    """);
            Array ids = connection.createArrayOf("uuid", skuIds.toArray());
            statement.setArray(1, ids);
            statement.setString(2, properties.defaultWarehouseCode());
            return statement;
        }, row -> {
            found.put(row.getObject("sku_id", UUID.class), new Home(row.getObject("warehouse_id", UUID.class),
                    row.getObject("seller_id", UUID.class), row.getInt("available_quantity")));
        });
        return found;
    }

    public static Set<UUID> sellerWarehouses(Collection<Home> homes) {
        return homes.stream().filter(Objects::nonNull).filter(Home::sellerOwned)
                .map(Home::warehouseId).collect(Collectors.toSet());
    }

    public Home home(UUID skuId) {
        return homes(java.util.List.of(skuId)).get(skuId);
    }

    public UUID warehouseFor(UUID skuId) {
        Home home = home(skuId);
        return home == null ? warehouses.defaultWarehouseId() : home.warehouseId();
    }

    public Map<UUID, UUID> warehousesFor(Collection<UUID> skuIds) {
        Map<UUID, Home> homes = homes(skuIds);
        Map<UUID, UUID> result = new HashMap<>();
        UUID fallback = null;
        for (UUID skuId : skuIds) {
            Home home = homes.get(skuId);
            if (home == null && fallback == null) {
                fallback = warehouses.defaultWarehouseId();
            }
            result.put(skuId, home == null ? fallback : home.warehouseId());
        }
        return result;
    }
}
