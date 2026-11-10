CREATE UNIQUE INDEX uq_reservation_order_item
    ON inventory_reservations (order_id, sku_id, warehouse_id)
    WHERE order_id IS NOT NULL;
