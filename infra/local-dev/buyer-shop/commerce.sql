BEGIN;
-- seed-buyer-shop.ps1 injects the current, validated catalog SKU/price snapshot here.
-- buyer-shop-cart-manifest

-- Keep expired/checked-out carts as history. A fresh cart/line uses the database's
-- UUID defaults so a previous demo cart expiring cannot consume its fixed ID forever.
-- A nonempty active cart belongs to the buyer and is never reset by this seed.
INSERT INTO carts(user_id,status,created_by,updated_by)
SELECT '68000000-0000-4000-8000-000000000003','ACTIVE',
 '68000000-0000-4000-8000-000000000003','68000000-0000-4000-8000-000000000003'
WHERE NOT EXISTS (
 SELECT 1 FROM carts WHERE user_id='68000000-0000-4000-8000-000000000003' AND status='ACTIVE'
)
ON CONFLICT DO NOTHING;
INSERT INTO cart_items(cart_id,product_id,sku_id,quantity,price)
SELECT c.id,m.product_id,m.sku_id,1,m.price
FROM carts c CROSS JOIN buyer_shop_cart_manifest m
WHERE c.user_id='68000000-0000-4000-8000-000000000003' AND c.status='ACTIVE'
 AND NOT EXISTS(SELECT 1 FROM cart_items ci WHERE ci.cart_id=c.id)
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE demo_orders (n int,status order_status,group_status varchar(20)) ON COMMIT DROP;
INSERT INTO demo_orders VALUES (1,'PENDING','PENDING'),(2,'PAID','PAID'),(3,'SHIPPED','PAID'),(4,'COMPLETED','PAID'),(5,'CANCELED','CANCELED');
INSERT INTO order_groups(id,buyer_id,idempotency_key,status,total_amount,currency,due_at,paid_at,created_at)
SELECT ('92000000-0000-4000-8000-'||lpad((600+n)::text,12,'0'))::uuid,
 '68000000-0000-4000-8000-000000000003','buyer-shop-demo-group-'||n,group_status,199000,'VND',
 now()+interval '24 hours',CASE WHEN group_status='PAID' THEN now()-n*interval '1 day' END,now()-n*interval '1 day'
FROM demo_orders ON CONFLICT DO NOTHING;
INSERT INTO orders(id,user_id,seller_id,order_number,order_type,status,subtotal,total_amount,shipping_info,idempotency_key,order_group_id,payment_due_at,paid_at,created_at)
SELECT ('92000000-0000-4000-8000-'||lpad((700+n)::text,12,'0'))::uuid,
 '68000000-0000-4000-8000-000000000003','68000000-0000-4000-8000-000000000002',
 'NX-DEMO-SHOP-'||lpad(n::text,4,'0'),'STANDARD',status,199000,199000,
 '{"paymentMethod":"SEPAY","shippingAddress":{"recipientName":"Demo Buyer","phoneNumber":"0901234567","addressLine":"123 Vo Van Ngan","ward":"Linh Chieu","city":"Ho Chi Minh City","countryCode":"VN"}}',
 'buyer-shop-demo-order-'||n,('92000000-0000-4000-8000-'||lpad((600+n)::text,12,'0'))::uuid,
 now()+interval '24 hours',CASE WHEN group_status='PAID' THEN now()-n*interval '1 day' END,now()-n*interval '1 day'
FROM demo_orders ON CONFLICT DO NOTHING;
-- Upgrade only unchanged address snapshots emitted by earlier versions of this seed.
-- These stored demo receipts omit the obsolete district; live checkout keeps its own contract.
UPDATE orders SET shipping_info='{"paymentMethod":"SEPAY","shippingAddress":{"recipientName":"Demo Buyer","phoneNumber":"0901234567","addressLine":"123 Vo Van Ngan","ward":"Linh Chieu","city":"Ho Chi Minh City","countryCode":"VN"}}'::jsonb
WHERE idempotency_key LIKE 'buyer-shop-demo-order-%'
 AND shipping_info IN (
  '{"fullName":"Demo Buyer","phone":"0901234567","addressLine":"123 Vo Van Ngan","provinceCode":"79","districtCode":"769","wardCode":"26815"}'::jsonb,
  '{"paymentMethod":"SEPAY","shippingAddress":{"recipientName":"Demo Buyer","phoneNumber":"0901234567","addressLine":"123 Vo Van Ngan","ward":"Linh Chieu","district":"Thu Duc","city":"Ho Chi Minh City","countryCode":"VN"}}'::jsonb
 );
INSERT INTO order_items(id,order_id,product_id,sku_id,product_name,sku_code,image_url,quantity,unit_price,total_price)
SELECT ('92000000-0000-4000-8000-'||lpad((800+n)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-'||lpad((700+n)::text,12,'0'))::uuid,
 '92000000-0000-4000-8000-000000000209','92000000-0000-4000-8000-000000001091',
 'Everyday Cotton T-Shirt','DEMO-SHOP-9-1',
 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-000000000204/69000000-0000-4000-8000-000000000304.jpg',1,199000,199000
FROM demo_orders ON CONFLICT DO NOTHING;
INSERT INTO order_status_history(id,order_id,previous_status,new_status,reason,changed_by,created_at)
SELECT ('92000000-0000-4000-8000-'||lpad((900+n)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-'||lpad((700+n)::text,12,'0'))::uuid,
 NULL,status,'Local buyer shop demonstration','68000000-0000-4000-8000-000000000003',now()-n*interval '1 day'
FROM demo_orders ON CONFLICT DO NOTHING;
COMMIT;
