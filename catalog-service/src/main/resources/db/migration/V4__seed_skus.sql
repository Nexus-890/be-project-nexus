INSERT INTO skus (
    product_id,
    sku_code,
    color,
    size,
    price
)
SELECT
    p.id,
    'SKU-' || replace(p.id::text, '-', '') || '-01',
    'Red',
    'M',
    150000.00::numeric(19,2)
FROM products p

UNION ALL

SELECT
    p.id,
    'SKU-' || replace(p.id::text, '-', '') || '-02',
    'Blue',
    'L',
    180000.00::numeric(19,2)
FROM products p;