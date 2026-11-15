CREATE TABLE catalog_auction_flags (
    auction_id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    is_open boolean NOT NULL
);

CREATE INDEX idx_catalog_auction_flags_open_product
    ON catalog_auction_flags (product_id) WHERE is_open;
