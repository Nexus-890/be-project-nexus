BEGIN;
WITH added AS (
 INSERT INTO inventory_balances(id,sku_id,warehouse_id,total_quantity,available_quantity,reserved_quantity,unavailable_quantity)
 SELECT ('93000000-0000-4000-8000-'||lpad((910+n)::text,12,'0'))::uuid,
 ('93000000-0000-4000-8000-'||lpad((300+n)::text,12,'0'))::uuid,
 (SELECT id FROM warehouses WHERE code='WH_001' AND status='ACTIVE'),CASE WHEN n=6 THEN 0 ELSE n*2 END,CASE WHEN n=6 THEN 0 ELSE n*2 END,0,0
 FROM generate_series(1,6) n ON CONFLICT DO NOTHING RETURNING id,sku_id,warehouse_id,total_quantity
)
INSERT INTO inventory_movements(id,sku_id,warehouse_id,movement_type,quantity,reference_id)
SELECT ('93000000-0000-4000-8000-'||lpad((650+right(sku_id::text,12)::bigint)::text,12,'0'))::uuid,
 sku_id,warehouse_id,'INTAKE',total_quantity,id FROM added ON CONFLICT DO NOTHING;
COMMIT;
