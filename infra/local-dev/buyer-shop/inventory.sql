BEGIN;
INSERT INTO warehouses(id,code,name,address,status) VALUES
 ('92000000-0000-4000-8000-000000000401','DEMO-SHOP-HCM','Buyer shop demo warehouse','Thu Duc, Ho Chi Minh City','ACTIVE')
ON CONFLICT DO NOTHING;
INSERT INTO inventory_balances(id,sku_id,warehouse_id,total_quantity,available_quantity)
SELECT ('92000000-0000-4000-8000-' || lpad((2000+n*10+v)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-' || lpad((1000+n*10+v)::text,12,'0'))::uuid,
 '92000000-0000-4000-8000-000000000401',100,100
FROM generate_series(1,24) p(n) CROSS JOIN generate_series(1,3) s(v)
ON CONFLICT DO NOTHING;
-- A matching intake ledger entry is required; repeat runs never replenish sales.
INSERT INTO inventory_movements(id,sku_id,warehouse_id,movement_type,quantity,reference_id)
SELECT ('92000000-0000-4000-8000-' || lpad((3000+n*10+v)::text,12,'0'))::uuid,
 ('92000000-0000-4000-8000-' || lpad((1000+n*10+v)::text,12,'0'))::uuid,
 '92000000-0000-4000-8000-000000000401','INTAKE',100,
 ('92000000-0000-4000-8000-' || lpad((2000+n*10+v)::text,12,'0'))::uuid
FROM generate_series(1,24) p(n) CROSS JOIN generate_series(1,3) s(v)
ON CONFLICT DO NOTHING;
COMMIT;
