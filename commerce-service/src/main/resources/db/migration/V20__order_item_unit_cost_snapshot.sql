ALTER TABLE order_items
    ADD COLUMN unit_cost_price numeric(19,2),
    ADD CONSTRAINT order_items_unit_cost_price_check CHECK (unit_cost_price >= 0);

COMMENT ON COLUMN order_items.unit_cost_price IS
    'Private SKU unit cost captured when the order is created. NULL means unknown, including historical orders.';
