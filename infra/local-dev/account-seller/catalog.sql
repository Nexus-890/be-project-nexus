BEGIN;
-- account-seller-image-manifest
INSERT INTO products(id,category_id,seller_id,name,slug,description,status,created_by,updated_by) VALUES
('93000000-0000-4000-8000-000000000201','69000000-0000-4000-8000-000000000103','68000000-0000-4000-8000-000000000002','Studio Wireless Headphones','account-seller-studio-headphones','Local review product with two variants.','ACTIVE','68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'),
('93000000-0000-4000-8000-000000000202','69000000-0000-4000-8000-000000000103','68000000-0000-4000-8000-000000000002','Everyday Cotton Shirt','account-seller-everyday-shirt','Local review product with image and variants.','ACTIVE','68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'),
('93000000-0000-4000-8000-000000000203','69000000-0000-4000-8000-000000000103','68000000-0000-4000-8000-000000000002','Inactive Sneaker Collection With An Intentionally Long Product Name For Responsive Seller Table Review','account-seller-inactive-sneakers','Local review inactive listing without an image.','INACTIVE','68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'),
('93000000-0000-4000-8000-000000000204','69000000-0000-4000-8000-000000000103','68000000-0000-4000-8000-000000000002','Draft Without Price Or Image','account-seller-draft-no-price','Local review draft intentionally has no variants.','DRAFT','68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002')
ON CONFLICT DO NOTHING;
INSERT INTO skus(id,product_id,sku_code,color,size,price,currency,status,created_by,updated_by)
SELECT ('93000000-0000-4000-8000-'||lpad((300+n)::text,12,'0'))::uuid,
 ('93000000-0000-4000-8000-'||lpad((201+(n-1)/2)::text,12,'0'))::uuid,
 'ACCOUNT-SELLER-'||n,CASE WHEN n%2=1 THEN 'Black' ELSE 'White' END,'M',
 (150000+n*10000)::numeric(19,2),'VND','ACTIVE','68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'
FROM generate_series(1,6) n ON CONFLICT DO NOTHING;
-- Each seeded image has its own product-scoped storage object so BE removal
-- and reorder ownership checks are valid. Never share another product's URL.
INSERT INTO product_images(id,product_id,image_url,sort_order,created_by,updated_by)
SELECT image_id,product_id,image_url,0,
 '68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'
FROM account_seller_images ON CONFLICT DO NOTHING;
-- Correct only the exact borrowed URL from this task's original fixture.
-- The ID/product pair and old URL must all match; retain all other edits/sort.
-- account-seller-image-correction:start
UPDATE product_images image SET image_url=manifest.image_url
FROM account_seller_images manifest
WHERE image.id=manifest.image_id AND image.product_id=manifest.product_id
 AND image.image_url=manifest.legacy_image_url;
-- account-seller-image-correction:end
COMMIT;
