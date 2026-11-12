-- Opt-in LOCAL demo data, never an automatic Flyway migration.
-- Namespace 92000000 is reserved for this demo; repeat runs preserve edits/orders.
BEGIN;
CREATE TEMP TABLE shop_products (
    n int, name text, slug text, category_id uuid, price bigint, image_product int, image_id int
) ON COMMIT DROP;
INSERT INTO shop_products VALUES
 (1,'Apple iPhone 15 128GB','apple-iphone-15-128gb', '69000000-0000-4000-8000-000000000102',15990000,201,301),
 (2,'Apple iPhone 15 Plus 256GB','apple-iphone-15-plus-256gb','69000000-0000-4000-8000-000000000102',20990000,201,301),
 (3,'Samsung Galaxy S24 256GB','samsung-galaxy-s24-256gb','69000000-0000-4000-8000-000000000102',17990000,202,302),
 (4,'Samsung Galaxy S24 Ultra 512GB','samsung-galaxy-s24-ultra-512gb','69000000-0000-4000-8000-000000000102',28990000,202,302),
 (5,'Xiaomi Redmi Note 13 128GB','xiaomi-redmi-note-13-128gb','69000000-0000-4000-8000-000000000102',4990000,203,303),
 (6,'Xiaomi Redmi Note 13 Pro 256GB','xiaomi-redmi-note-13-pro-256gb','69000000-0000-4000-8000-000000000102',7990000,203,303),
 (7,'Apple iPhone 14 128GB','apple-iphone-14-128gb','69000000-0000-4000-8000-000000000102',12990000,201,301),
 (8,'Samsung Galaxy A55 256GB','samsung-galaxy-a55-256gb','69000000-0000-4000-8000-000000000102',8990000,202,302),
 (9,'Everyday Cotton T-Shirt','everyday-cotton-t-shirt','92000000-0000-4000-8000-000000000101',199000,204,304),
 (10,'Premium Oversized T-Shirt','premium-oversized-t-shirt','92000000-0000-4000-8000-000000000101',299000,204,304),
 (11,'Essential Crew Neck T-Shirt','essential-crew-neck-t-shirt','92000000-0000-4000-8000-000000000101',249000,204,304),
 (12,'Organic Cotton Pocket T-Shirt','organic-cotton-pocket-t-shirt','92000000-0000-4000-8000-000000000101',349000,204,304),
 (13,'Classic Unisex T-Shirt','classic-unisex-t-shirt','92000000-0000-4000-8000-000000000101',179000,204,304),
 (14,'Relaxed Fit Weekend T-Shirt','relaxed-fit-weekend-t-shirt','92000000-0000-4000-8000-000000000101',279000,204,304),
 (15,'Sport Performance T-Shirt','sport-performance-t-shirt','92000000-0000-4000-8000-000000000101',399000,204,304),
 (16,'Minimal Everyday T-Shirt','minimal-everyday-t-shirt','92000000-0000-4000-8000-000000000101',219000,204,304),
 (17,'Classic White Sneakers','classic-white-sneakers','92000000-0000-4000-8000-000000000102',699000,205,305),
 (18,'Everyday Court Sneakers','everyday-court-sneakers','92000000-0000-4000-8000-000000000102',899000,205,305),
 (19,'Urban Low Top Sneakers','urban-low-top-sneakers','92000000-0000-4000-8000-000000000102',799000,205,305),
 (20,'Lightweight Walking Sneakers','lightweight-walking-sneakers','92000000-0000-4000-8000-000000000102',1099000,205,305),
 (21,'Retro Leather Sneakers','retro-leather-sneakers','92000000-0000-4000-8000-000000000102',1299000,205,305),
 (22,'Canvas Weekend Sneakers','canvas-weekend-sneakers','92000000-0000-4000-8000-000000000102',599000,205,305),
 (23,'Comfort Running Sneakers','comfort-running-sneakers','92000000-0000-4000-8000-000000000102',1499000,205,305),
 (24,'Essential Slip On Sneakers','essential-slip-on-sneakers','92000000-0000-4000-8000-000000000102',749000,205,305);

INSERT INTO categories (id,parent_id,name,slug,description,level,path) VALUES
 ('92000000-0000-4000-8000-000000000101','69000000-0000-4000-8000-000000000103','T-Shirts','demo-t-shirts','Local buyer shop demo',2,'/thoi-trang/demo-t-shirts/'),
 ('92000000-0000-4000-8000-000000000102','69000000-0000-4000-8000-000000000103','Sneakers','demo-sneakers','Local buyer shop demo',2,'/thoi-trang/demo-sneakers/')
ON CONFLICT DO NOTHING;

INSERT INTO products (id,category_id,seller_id,name,slug,description,status,auction_enable,created_by,updated_by)
SELECT ('92000000-0000-4000-8000-' || lpad((200+n)::text,12,'0'))::uuid,
 category_id,'68000000-0000-4000-8000-000000000002',name,
 slug || '-92000000-0000-4000-8000-' || lpad((200+n)::text,12,'0'),
 'Local demo. ' || name || E'\nThree available variants. Carefully selected quality, secure checkout and tracked delivery. Images reuse the public demo catalogue assets.',
 'ACTIVE',n IN (4,8,17,21),'68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'
FROM shop_products ON CONFLICT DO NOTHING;

INSERT INTO skus (id,product_id,sku_code,color,size,price,currency,status,created_by,updated_by)
SELECT ('92000000-0000-4000-8000-' || lpad((1000+p.n*10+v.n)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-' || lpad((200+p.n)::text,12,'0'))::uuid,
 'DEMO-SHOP-' || p.n || '-' || v.n,
 CASE v.n WHEN 1 THEN 'Black' WHEN 2 THEN 'White' ELSE 'Blue' END,
 CASE WHEN p.n<=8 THEN CASE v.n WHEN 1 THEN '128GB' WHEN 2 THEN '256GB' ELSE '512GB' END
      WHEN p.n<=16 THEN CASE v.n WHEN 1 THEN 'S' WHEN 2 THEN 'M' ELSE 'L' END
      ELSE (39+v.n)::text END,
 p.price+(v.n-1)*CASE WHEN p.n<=8 THEN 1000000 ELSE 20000 END,'VND','ACTIVE',
 '68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000002'
FROM shop_products p CROSS JOIN generate_series(1,3) v(n) ON CONFLICT DO NOTHING;

-- Reuse already provisioned public assets. No external writes or fabricated URLs.
INSERT INTO product_images (id,product_id,image_url,sort_order)
SELECT ('92000000-0000-4000-8000-' || lpad((300+p.n)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-' || lpad((200+p.n)::text,12,'0'))::uuid,
 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-' || lpad(image_product::text,12,'0') || '/69000000-0000-4000-8000-' || lpad(image_id::text,12,'0') || '.jpg',0
FROM shop_products p
WHERE NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id=('92000000-0000-4000-8000-' || lpad((200+p.n)::text,12,'0'))::uuid)
ON CONFLICT DO NOTHING;

-- Once provisioned through BE, retain owned objects and remove only shared fallback rows.
DELETE FROM product_images fallback
WHERE fallback.id IN (SELECT ('92000000-0000-4000-8000-' || lpad((300+n)::text,12,'0'))::uuid FROM shop_products)
 AND EXISTS (SELECT 1 FROM product_images owned WHERE owned.product_id=fallback.product_id
   AND owned.image_url LIKE '%/products/' || fallback.product_id::text || '/%');

INSERT INTO product_stats (product_id,order_count,view_count)
SELECT ('92000000-0000-4000-8000-' || lpad((200+n)::text,12,'0'))::uuid,20+n*7,100+n*37
FROM shop_products ON CONFLICT DO NOTHING;
WITH category_counts AS (
 SELECT c.id,count(p.id) AS product_count FROM categories c
 LEFT JOIN products p ON p.category_id=c.id AND p.status='ACTIVE' AND p.deleted_at IS NULL
 WHERE c.id IN (SELECT category_id FROM shop_products) GROUP BY c.id
)
UPDATE categories c SET product_count=counts.product_count
FROM category_counts counts
WHERE c.id=counts.id AND c.product_count IS DISTINCT FROM counts.product_count;
COMMIT;
