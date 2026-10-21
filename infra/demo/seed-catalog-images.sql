-- Add illustrative photos only to the five original demo products with no photos.
-- Run after Flyway and seed-catalog.sql. Existing product photos are preserved.
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'nexus_demo_catalog_db' THEN
        RAISE EXCEPTION 'seed-catalog-images.sql requires nexus_demo_catalog_db';
    END IF;
END $$;

WITH image_sources (product_id, first_id, second_id, first_photo, second_photo) AS (
    VALUES
        ('69000000-0000-4000-8000-000000000201'::uuid,
         '69000000-0000-4000-8000-000000000401'::uuid, '69000000-0000-4000-8000-000000000501'::uuid,
         'photo-1511707171634-5f897ff02aa9', 'photo-1512499617640-c74ae3a79d37'),
        ('69000000-0000-4000-8000-000000000202'::uuid,
         '69000000-0000-4000-8000-000000000402'::uuid, '69000000-0000-4000-8000-000000000502'::uuid,
         'photo-1512499617640-c74ae3a79d37', 'photo-1511707171634-5f897ff02aa9'),
        ('69000000-0000-4000-8000-000000000203'::uuid,
         '69000000-0000-4000-8000-000000000403'::uuid, '69000000-0000-4000-8000-000000000503'::uuid,
         'photo-1511707171634-5f897ff02aa9', 'photo-1512499617640-c74ae3a79d37'),
        ('69000000-0000-4000-8000-000000000204'::uuid,
         '69000000-0000-4000-8000-000000000404'::uuid, '69000000-0000-4000-8000-000000000504'::uuid,
         'photo-1521572163474-6864f9cf17ab', 'photo-1503341504253-dff4815485f1'),
        ('69000000-0000-4000-8000-000000000205'::uuid,
         '69000000-0000-4000-8000-000000000405'::uuid, '69000000-0000-4000-8000-000000000505'::uuid,
         'photo-1549298916-b41d501d3772', 'photo-1600185365926-3a2ce3cdb9eb')
), missing_products AS MATERIALIZED (
    -- Resolve missing photos once, before inserting both images for each product.
    SELECT s.* FROM image_sources s
    JOIN products p ON p.id = s.product_id
    WHERE NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id)
)
INSERT INTO product_images (id, product_id, image_url, sort_order)
SELECT image.id, missing.product_id,
       'https://images.unsplash.com/' || image.photo || '?auto=format&fit=crop&w=1200&q=80',
       image.sort_order
FROM missing_products missing
CROSS JOIN LATERAL (VALUES
    (missing.first_id, missing.first_photo, 0),
    (missing.second_id, missing.second_photo, 1)
) AS image(id, photo, sort_order)
ON CONFLICT (id) DO NOTHING;

COMMIT;
