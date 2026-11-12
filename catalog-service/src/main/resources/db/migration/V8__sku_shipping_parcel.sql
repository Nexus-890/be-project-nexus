ALTER TABLE skus
    ADD COLUMN shipping_weight_grams integer,
    ADD COLUMN shipping_length_cm integer,
    ADD COLUMN shipping_width_cm integer,
    ADD COLUMN shipping_height_cm integer,
    ADD CONSTRAINT ck_skus_shipping_parcel CHECK (
        (shipping_weight_grams IS NULL AND shipping_length_cm IS NULL
            AND shipping_width_cm IS NULL AND shipping_height_cm IS NULL)
        OR
        (shipping_weight_grams IS NOT NULL AND shipping_length_cm IS NOT NULL
            AND shipping_width_cm IS NOT NULL AND shipping_height_cm IS NOT NULL
            AND shipping_weight_grams BETWEEN 1 AND 50000
            AND shipping_length_cm BETWEEN 1 AND 200
            AND shipping_width_cm BETWEEN 1 AND 200
            AND shipping_height_cm BETWEEN 1 AND 200)
    );

COMMENT ON COLUMN skus.shipping_weight_grams IS
    'Seller supplied shipping package weight for one SKU unit; NULL until verified';
