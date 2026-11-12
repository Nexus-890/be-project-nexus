ALTER TABLE shipments ADD COLUMN seller_id uuid;
CREATE INDEX idx_shipments_seller ON shipments (seller_id, created_at DESC)
    WHERE seller_id IS NOT NULL;
