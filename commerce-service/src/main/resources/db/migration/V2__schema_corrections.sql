ALTER TABLE orders
    ADD COLUMN payment_due_at timestamptz,
    ADD COLUMN memo_code      varchar(32) UNIQUE;

CREATE INDEX idx_orders_reference ON orders (reference_id) WHERE reference_id IS NOT NULL;

CREATE UNIQUE INDEX idx_carts_one_active ON carts (user_id)
    WHERE status = 'ACTIVE' AND user_id IS NOT NULL;
