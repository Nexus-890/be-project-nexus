BEGIN;
-- account-seller-snapshot
-- A consumed/expired cart never blocks a later seed: IDs use database defaults.
-- account-seller-cart:start
-- Seed a line only in a cart created here. An existing empty cart is user state.
WITH added_cart AS (
 INSERT INTO carts(user_id,status) SELECT '68000000-0000-4000-8000-000000000003','ACTIVE'
 WHERE NOT EXISTS(SELECT 1 FROM carts WHERE user_id='68000000-0000-4000-8000-000000000003' AND status='ACTIVE')
 ON CONFLICT DO NOTHING RETURNING id
)
INSERT INTO cart_items(cart_id,product_id,sku_id,quantity,price)
SELECT c.id,s.product_id,s.sku_id,1,s.price FROM added_cart c CROSS JOIN account_seller_snapshot s
ON CONFLICT DO NOTHING;
-- account-seller-cart:end
CREATE TEMP TABLE account_seller_states(n int,status order_status,group_status text) ON COMMIT DROP;
INSERT INTO account_seller_states VALUES (1,'PENDING','PENDING'),(2,'PAID','PAID'),(3,'SHIPPED','PAID'),(4,'DELIVERED','PAID'),(5,'COMPLETED','PAID'),(6,'CANCELED','CANCELED'),(7,'REFUND_REQUESTED','PAID'),(8,'REFUNDED','PAID');
INSERT INTO order_groups(id,buyer_id,idempotency_key,status,total_amount,currency,due_at,paid_at,created_at)
SELECT ('93000000-0000-4000-8000-'||lpad((500+n)::text,12,'0'))::uuid,
 '68000000-0000-4000-8000-000000000003','account-seller-group-'||n,group_status,s.price,'VND',
 now()+interval '7 days',CASE WHEN group_status='PAID' THEN now()-n*interval '1 day' END,now()-n*interval '1 day'
FROM account_seller_states CROSS JOIN account_seller_snapshot s ON CONFLICT DO NOTHING;
INSERT INTO orders(id,user_id,seller_id,order_number,order_type,status,subtotal,total_amount,shipping_info,idempotency_key,order_group_id,payment_due_at,paid_at,created_at)
SELECT ('93000000-0000-4000-8000-'||lpad((600+n)::text,12,'0'))::uuid,
 '68000000-0000-4000-8000-000000000003','68000000-0000-4000-8000-000000000002',
 'NX-ACCOUNT-'||lpad(n::text,4,'0'),'STANDARD',status,s.price,s.price,
 '{"paymentMethod":"SEPAY","shippingAddress":{"recipientName":"Demo Buyer","phoneNumber":"0901234567","addressLine":"123 Vo Van Ngan","ward":"Linh Chieu","district":"Thu Duc","city":"Ho Chi Minh City","countryCode":"VN"}}',
 'account-seller-order-'||n,('93000000-0000-4000-8000-'||lpad((500+n)::text,12,'0'))::uuid,
 now()+interval '7 days',CASE WHEN group_status='PAID' THEN now()-n*interval '1 day' END,now()-n*interval '1 day'
FROM account_seller_states CROSS JOIN account_seller_snapshot s ON CONFLICT DO NOTHING;
INSERT INTO order_items(id,order_id,product_id,sku_id,product_name,sku_code,image_url,quantity,unit_price,total_price)
SELECT ('93000000-0000-4000-8000-'||lpad((700+n)::text,12,'0'))::uuid,
 ('93000000-0000-4000-8000-'||lpad((600+n)::text,12,'0'))::uuid,
 s.product_id,s.sku_id,s.product_name,s.sku_code,s.image_url,1,s.price,s.price
FROM account_seller_states CROSS JOIN account_seller_snapshot s ON CONFLICT DO NOTHING;
INSERT INTO order_status_history(id,order_id,previous_status,new_status,reason,changed_by,created_at)
SELECT ('93000000-0000-4000-8000-'||lpad((800+n)::text,12,'0'))::uuid,
 ('93000000-0000-4000-8000-'||lpad((600+n)::text,12,'0'))::uuid,NULL,status,
 'Local account/seller UI review snapshot; no external payment or shipment was performed.',
 '68000000-0000-4000-8000-000000000003',now()-n*interval '1 day'
FROM account_seller_states ON CONFLICT DO NOTHING;
COMMIT;
