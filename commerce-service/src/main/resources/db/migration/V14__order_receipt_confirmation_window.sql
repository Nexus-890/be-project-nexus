ALTER TABLE orders ADD COLUMN delivered_at timestamptz;
ALTER TABLE orders ADD COLUMN auto_complete_at timestamptz;

-- Historical DELIVERED rows get a fresh seven-day window at deployment. Existing
-- COMPLETED orders keep their settled state and never publish another completion.
UPDATE orders SET delivered_at = now(), auto_complete_at = now() + interval '7 days'
WHERE status = 'DELIVERED';

ALTER TABLE orders ADD CONSTRAINT order_receipt_dates_check
    CHECK ((delivered_at IS NULL AND auto_complete_at IS NULL)
        OR (delivered_at IS NOT NULL AND auto_complete_at IS NOT NULL AND auto_complete_at > delivered_at));
CREATE INDEX idx_orders_receipt_due ON orders (auto_complete_at, id)
WHERE status = 'DELIVERED';
