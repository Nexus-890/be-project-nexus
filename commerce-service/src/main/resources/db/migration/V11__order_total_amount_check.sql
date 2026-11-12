ALTER TABLE orders
    ADD CONSTRAINT chk_orders_total_amount
        CHECK (total_amount = subtotal - discount_amount + tax_amount + shipping_fee);
