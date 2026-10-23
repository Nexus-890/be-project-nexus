CREATE TYPE product_status AS ENUM ('DRAFT', 'ACTIVE', 'INACTIVE');
CREATE TYPE sku_status AS ENUM ('DRAFT', 'ACTIVE', 'INACTIVE');

ALTER TABLE products DROP CONSTRAINT products_status_check;
ALTER TABLE products ALTER COLUMN status DROP DEFAULT;
ALTER TABLE products ALTER COLUMN status TYPE product_status USING status::product_status;
ALTER TABLE products ALTER COLUMN status SET DEFAULT 'DRAFT'::product_status;

ALTER TABLE skus DROP CONSTRAINT skus_status_check;
ALTER TABLE skus ALTER COLUMN status DROP DEFAULT;
ALTER TABLE skus ALTER COLUMN status TYPE sku_status USING status::sku_status;
ALTER TABLE skus ALTER COLUMN status SET DEFAULT 'ACTIVE'::sku_status;
