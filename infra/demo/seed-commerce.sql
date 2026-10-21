-- Real cart data for the isolated commerce-service Flyway database.
-- Cross-service UUIDs match seed-users.sql and seed-catalog.sql; no cross-DB FK.
-- Re-running adds missing demo rows and preserves quantities changed in the UI.
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'nexus_demo_commerce_db' THEN
        RAISE EXCEPTION 'Run seed-commerce.sql only against nexus_demo_commerce_db';
    END IF;
END $$;

-- Reuse an ACTIVE cart if login/browser checks already created one for the buyer.
-- The schema generates a fresh cart ID if an older demo cart was checked out.
INSERT INTO carts (user_id, status)
SELECT '68000000-0000-4000-8000-000000000003'::uuid, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM carts
    WHERE user_id = '68000000-0000-4000-8000-000000000003' AND status = 'ACTIVE'
)
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM carts
        WHERE user_id = '68000000-0000-4000-8000-000000000003' AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Demo buyer has no active cart; inspect existing demo cart state';
    END IF;
END $$;

-- iPhone 15 Black 128GB: 1 x 16,990,000 VND.
-- Cotton T-shirt White M: 2 x 199,000 VND.
-- Default item IDs avoid collisions with items retained in historical carts.
INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price)
SELECT c.id, demo.product_id, demo.sku_id, demo.quantity, demo.price
FROM (VALUES
    ('6d260021-0000-4000-8000-000000000201'::uuid,
     '6d260021-0000-4000-8000-000000000301'::uuid, 1, 16990000.00::numeric),
    ('6d260021-0000-4000-8000-000000000207'::uuid,
     '6d260021-0000-4000-8000-000000000313'::uuid, 2, 199000.00::numeric)
) AS demo(product_id, sku_id, quantity, price)
JOIN carts c ON c.user_id = '68000000-0000-4000-8000-000000000003'
            AND c.status = 'ACTIVE'
ON CONFLICT (cart_id, sku_id) DO NOTHING;

COMMIT;
