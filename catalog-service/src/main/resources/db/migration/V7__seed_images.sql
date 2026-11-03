-- V5 and V6 are already allocated; keep applied migration checksums intact.
-- Objects are provisioned by infra/local-dev/provision-product-images.ps1.
INSERT INTO product_images (id, product_id, image_url, sort_order) VALUES
    ('69000000-0000-4000-8000-000000000301', '69000000-0000-4000-8000-000000000201', 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-000000000201/69000000-0000-4000-8000-000000000301.jpg', 0),
    ('69000000-0000-4000-8000-000000000302', '69000000-0000-4000-8000-000000000202', 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-000000000202/69000000-0000-4000-8000-000000000302.jpg', 0),
    ('69000000-0000-4000-8000-000000000303', '69000000-0000-4000-8000-000000000203', 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-000000000203/69000000-0000-4000-8000-000000000303.jpg', 0),
    ('69000000-0000-4000-8000-000000000304', '69000000-0000-4000-8000-000000000204', 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-000000000204/69000000-0000-4000-8000-000000000304.jpg', 0),
    ('69000000-0000-4000-8000-000000000305', '69000000-0000-4000-8000-000000000205', 'https://nzjvjvyqxcisjgvglwvj.supabase.co/storage/v1/object/public/product-images/products/69000000-0000-4000-8000-000000000205/69000000-0000-4000-8000-000000000305.jpg', 0)
ON CONFLICT (id) DO NOTHING;
