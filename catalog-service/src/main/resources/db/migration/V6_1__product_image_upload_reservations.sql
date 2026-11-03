-- Capacity claims keep slow storage HTTP calls outside database transactions.
-- Expired claims are reclaimed under the product lock by the next upload attempt.
CREATE TABLE product_image_uploads (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '5 minutes')
);
CREATE INDEX idx_product_image_uploads_product ON product_image_uploads(product_id);
