BEGIN;
INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,current_price,start_at,end_at,original_end_at,status)
SELECT ('93000000-0000-4000-8000-'||lpad((1000+n)::text,12,'0'))::uuid,
 '93000000-0000-4000-8000-000000000201','93000000-0000-4000-8000-000000000301',
 '68000000-0000-4000-8000-000000000002','Local review studio auction '||n,
 160000,10000,160000,CASE WHEN n=1 THEN now()-interval '1 hour' ELSE now()+interval '7 days' END,
 now()+interval '8 days',now()+interval '8 days',CASE WHEN n=1 THEN 'ACTIVE'::auction_status ELSE 'SCHEDULED'::auction_status END
FROM generate_series(1,2) n ON CONFLICT DO NOTHING;
COMMIT;
