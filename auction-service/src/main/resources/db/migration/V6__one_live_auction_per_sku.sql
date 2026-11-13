CREATE UNIQUE INDEX uq_auctions_live_sku ON auctions (sku_id)
    WHERE deleted_at IS NULL AND status IN ('CREATED', 'SCHEDULED', 'ACTIVE');
