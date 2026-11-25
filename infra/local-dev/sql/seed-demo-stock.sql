-- #133: executed by seed-demo-stock.ps1 after both services have migrated.
-- UUID input is a validated JSON array exported by the read-only catalog query.
-- This is not a Flyway migration: catalogue V4 UUIDs vary by database.
BEGIN;

INSERT INTO warehouses (code, name, address, province_code, ward_code, seller_id, created_by, updated_by)
VALUES ('SELLER-68000000000040008000000000000002', 'Nexus Seller warehouse', '12 Lê Lợi', '1000001', '1003504',
        '68000000-0000-4000-8000-000000000002', '68000000-0000-4000-8000-000000000002',
        '68000000-0000-4000-8000-000000000002')
ON CONFLICT DO NOTHING;

UPDATE warehouses
SET address = '12 Lê Lợi', province_code = '1000001', ward_code = '1003504', updated_at = now()
WHERE seller_id = '68000000-0000-4000-8000-000000000002' AND province_code IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM warehouses
                   WHERE seller_id = '68000000-0000-4000-8000-000000000002' AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'The demo seller warehouse is not active; no stock was seeded';
    END IF;
END $$;

CREATE TEMP TABLE seed_skus ON COMMIT DROP AS
SELECT DISTINCT s.sku_id::uuid AS sku_id
FROM jsonb_array_elements_text('__DEMO_SKU_IDS_JSON__'::jsonb) AS s(sku_id);

CREATE TEMP TABLE seed_target ON COMMIT DROP AS
SELECT id FROM warehouses WHERE seller_id = '68000000-0000-4000-8000-000000000002';

CREATE TEMP TABLE seed_new_balances (balance_id uuid PRIMARY KEY, sku_id uuid NOT NULL) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO inventory_balances (sku_id, warehouse_id)
    SELECT s.sku_id, t.id FROM seed_skus s CROSS JOIN seed_target t
    ON CONFLICT (sku_id, warehouse_id) DO NOTHING
    RETURNING id, sku_id
)
INSERT INTO seed_new_balances (balance_id, sku_id)
SELECT id, sku_id FROM inserted;

CREATE TEMP TABLE seed_moves (from_balance uuid, from_warehouse uuid, sku_id uuid, quantity int) ON COMMIT DROP;
WITH shared AS (
    SELECT b.id, b.warehouse_id, b.sku_id, b.available_quantity AS quantity
    FROM inventory_balances b
    JOIN warehouses w ON w.id = b.warehouse_id
    WHERE w.seller_id IS NULL AND b.available_quantity > 0
      AND b.sku_id IN (SELECT sku_id FROM seed_skus)
    FOR UPDATE OF b
), taken AS (
    UPDATE inventory_balances b
    SET total_quantity = b.total_quantity - shared.quantity,
        available_quantity = b.available_quantity - shared.quantity,
        updated_at = now(), version = b.version + 1
    FROM shared
    WHERE b.id = shared.id
    RETURNING shared.id, shared.warehouse_id, shared.sku_id, shared.quantity
)
INSERT INTO seed_moves (from_balance, from_warehouse, sku_id, quantity)
SELECT id, warehouse_id, sku_id, quantity FROM taken;

UPDATE inventory_balances b
SET total_quantity = b.total_quantity + moved.quantity,
    available_quantity = b.available_quantity + moved.quantity,
    updated_at = now(), version = b.version + 1
FROM (SELECT sku_id, sum(quantity) AS quantity FROM seed_moves GROUP BY sku_id) moved
WHERE b.sku_id = moved.sku_id AND b.warehouse_id = (SELECT id FROM seed_target);

INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reference_id, reason)
SELECT m.sku_id, m.from_warehouse, 'TRANSFER'::inventory_movement_type, -m.quantity, b.id, 'Moved to the seller warehouse'
FROM seed_moves m
JOIN inventory_balances b ON b.sku_id = m.sku_id AND b.warehouse_id = (SELECT id FROM seed_target)
UNION ALL
SELECT m.sku_id, (SELECT id FROM seed_target), 'TRANSFER'::inventory_movement_type, m.quantity, m.from_balance, 'Moved to the seller warehouse'
FROM seed_moves m;

CREATE TEMP TABLE seed_intakes (balance_id uuid, sku_id uuid) ON COMMIT DROP;
WITH filled AS (
    UPDATE inventory_balances b
    SET total_quantity = 20, available_quantity = 20, updated_at = now(), version = b.version + 1
    FROM seed_new_balances n
    WHERE b.id = n.balance_id AND n.sku_id NOT IN (SELECT sku_id FROM seed_moves)
    RETURNING b.id, b.sku_id
)
INSERT INTO seed_intakes (balance_id, sku_id)
SELECT id, sku_id FROM filled;

INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reference_id)
SELECT i.sku_id, (SELECT id FROM seed_target), 'INTAKE', 20, i.balance_id
FROM seed_intakes i;

SELECT jsonb_build_object(
    'demoSkus', (SELECT count(*) FROM seed_skus),
    'sellerWarehouse', (SELECT code FROM warehouses WHERE id = (SELECT id FROM seed_target)),
    'newBalances', (SELECT count(*) FROM seed_new_balances),
    'movedFromSharedWarehouses', (SELECT count(DISTINCT sku_id) FROM seed_moves),
    'newIntakes', (SELECT count(*) FROM seed_intakes)
)::text;

COMMIT;
