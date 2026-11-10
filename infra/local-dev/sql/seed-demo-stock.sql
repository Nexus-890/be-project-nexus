-- #133: executed by seed-demo-stock.ps1 after both services have migrated.
-- UUID input is a validated JSON array exported by the read-only catalog query.
-- This is not a Flyway migration: catalogue V4 UUIDs vary by database.
BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM warehouses WHERE code = 'WH_001' AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Active WH_001 is required; run inventory migrations first';
    END IF;
END $$;

WITH inserted_balances AS (
    INSERT INTO inventory_balances
        (sku_id, warehouse_id, total_quantity, available_quantity,
         reserved_quantity, unavailable_quantity)
    SELECT s.sku_id::uuid, w.id, 20, 20, 0, 0
    FROM jsonb_array_elements_text('__DEMO_SKU_IDS_JSON__'::jsonb) AS s(sku_id)
    CROSS JOIN warehouses w
    WHERE w.code = 'WH_001' AND w.status = 'ACTIVE'
    ON CONFLICT DO NOTHING
    RETURNING id, sku_id, warehouse_id
), inserted_movements AS (
    INSERT INTO inventory_movements
        (sku_id, warehouse_id, movement_type, quantity, reference_id)
    SELECT sku_id, warehouse_id, 'INTAKE', 20, id
    FROM inserted_balances
    ON CONFLICT DO NOTHING
    RETURNING id
)
SELECT jsonb_build_object(
    'demoSkus', 10,
    'newBalances', (SELECT count(*) FROM inserted_balances),
    'newMovements', (SELECT count(*) FROM inserted_movements)
)::text;

COMMIT;
