ALTER TYPE inventory_movement_type ADD VALUE IF NOT EXISTS 'TRANSFER';

ALTER TABLE warehouses
    ADD COLUMN seller_id uuid,
    ADD CONSTRAINT warehouses_seller_id_key UNIQUE (seller_id);
