BEGIN;
INSERT INTO notifications(id,user_id,type,title,content,read_at,created_at)
SELECT ('93000000-0000-4000-8000-'||lpad((1100+n)::text,12,'0'))::uuid,
 CASE WHEN n=6 THEN '68000000-0000-4000-8000-000000000002'::uuid ELSE '68000000-0000-4000-8000-000000000003'::uuid END,
 CASE n WHEN 1 THEN 'ORDER_PLACED' WHEN 2 THEN 'AUCTION_OUTBID' WHEN 3 THEN 'PAYMENT_SUCCESS' WHEN 4 THEN 'TIER_UPGRADE' ELSE 'SYSTEM' END,
 CASE n WHEN 1 THEN 'Your local review order is ready' WHEN 2 THEN 'You were outbid on Studio Wireless Headphones' WHEN 3 THEN 'Local review payment snapshot' WHEN 4 THEN 'Review your account profile' WHEN 5 THEN 'Welcome to Nexus' ELSE 'Your seller portal is ready to review' END,
 'Persistent local review data. No external payment, shipment, email or SMS was sent.',
 CASE WHEN n IN(4,5) THEN now()-interval '1 day' END,now()-n*interval '1 hour'
FROM generate_series(1,6) n ON CONFLICT DO NOTHING;
COMMIT;
