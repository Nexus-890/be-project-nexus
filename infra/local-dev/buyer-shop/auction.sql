BEGIN;
INSERT INTO auctions(id,product_id,sku_id,seller_id,title,image_url,starting_price,bid_increment,current_price,currency,start_at,end_at,original_end_at,status)
SELECT ('92000000-0000-4000-8000-'||lpad((4000+n)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-'||lpad((200+p)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-'||lpad((1000+p*10+1)::text,12,'0'))::uuid,
 '68000000-0000-4000-8000-000000000002',title,
 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-'||lpad(ip::text,12,'0')||'/69000000-0000-4000-8000-'||lpad(ii::text,12,'0')||'.jpg',
 price,10000,price,'VND',
 CASE WHEN n<=2 THEN now()-n*interval '1 hour' ELSE now()+n*interval '1 day' END,
 now()+(n+1)*interval '1 day',now()+(n+1)*interval '1 day',
 CASE WHEN n<=2 THEN 'ACTIVE'::auction_status ELSE 'SCHEDULED'::auction_status END
FROM (VALUES(1,4,'Samsung Galaxy S24 Ultra 512GB',19990000,202,302),(2,8,'Samsung Galaxy A55 256GB',5990000,202,302),
 (3,17,'Classic White Sneakers',499000,205,305),(4,21,'Retro Leather Sneakers',899000,205,305)) d(n,p,title,price,ip,ii)
ON CONFLICT DO NOTHING;
-- Upgrade the first two original, untouched demo fixtures to populate Live Auctions.
-- Do not reopen ended/cancelled auctions, change bids or overwrite owner edits.
UPDATE auctions SET status='ACTIVE',start_at=now()-interval '1 hour',
 end_at=now()+interval '2 days',original_end_at=now()+interval '2 days',updated_at=now()
WHERE id IN ('92000000-0000-4000-8000-000000004001','92000000-0000-4000-8000-000000004002')
 AND status='SCHEDULED' AND bid_count=0 AND version=0 AND updated_at=created_at
 AND current_price=starting_price AND leader_bidder_id IS NULL AND winner_id IS NULL
 AND NOT EXISTS (SELECT 1 FROM bids WHERE auction_id=auctions.id);
COMMIT;
