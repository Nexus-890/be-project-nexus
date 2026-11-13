ALTER TABLE skus
    ADD COLUMN cost_price numeric(19,2),
    ADD CONSTRAINT ck_skus_cost_price CHECK (cost_price >= 0);

COMMENT ON COLUMN skus.cost_price IS
    'Seller purchase cost for one SKU unit; private to the owner, never used for the selling price';
