ALTER TABLE skus
ALTER COLUMN price TYPE numeric(19,2)
    USING price::numeric(19,2);

CREATE UNIQUE INDEX idx_skus_variant
    ON skus (product_id, color, size) NULLS NOT DISTINCT;