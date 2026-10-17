INSERT INTO categories (id, parent_id, name, slug, description, level, path) VALUES
    ('69000000-0000-4000-8000-000000000101', NULL, 'Điện tử', 'dien-tu', 'Thiết bị điện tử', 1, '/dien-tu/'),
    ('69000000-0000-4000-8000-000000000103', NULL, 'Thời trang', 'thoi-trang', 'Quần áo và giày dép', 1, '/thoi-trang/')
ON CONFLICT DO NOTHING;

INSERT INTO categories (id, parent_id, name, slug, description, level, path) VALUES
    ('69000000-0000-4000-8000-000000000102', '69000000-0000-4000-8000-000000000101', 'Điện thoại', 'dien-thoai', 'Điện thoại di động', 2, '/dien-tu/dien-thoai/')
ON CONFLICT DO NOTHING;

INSERT INTO products (id, category_id, seller_id, name, slug, description, status) VALUES
    ('69000000-0000-4000-8000-000000000201', '69000000-0000-4000-8000-000000000102', '68000000-0000-4000-8000-000000000002',
     'iPhone 15 128GB', 'iphone-15-128gb-69000000-0000-4000-8000-000000000201', 'Điện thoại Apple iPhone 15, bộ nhớ 128GB', 'ACTIVE'),
    ('69000000-0000-4000-8000-000000000202', '69000000-0000-4000-8000-000000000102', '68000000-0000-4000-8000-000000000002',
     'Samsung Galaxy S24', 'samsung-galaxy-s24-69000000-0000-4000-8000-000000000202', 'Điện thoại Samsung Galaxy S24, màn hình 6.2 inch', 'ACTIVE'),
    ('69000000-0000-4000-8000-000000000203', '69000000-0000-4000-8000-000000000102', '68000000-0000-4000-8000-000000000002',
     'Xiaomi Redmi Note 13', 'xiaomi-redmi-note-13-69000000-0000-4000-8000-000000000203', 'Điện thoại Xiaomi Redmi Note 13, pin 5000mAh', 'ACTIVE'),
    ('69000000-0000-4000-8000-000000000204', '69000000-0000-4000-8000-000000000103', '68000000-0000-4000-8000-000000000002',
     'Áo thun cotton basic', 'ao-thun-cotton-basic-69000000-0000-4000-8000-000000000204', 'Áo thun unisex 100% cotton', 'ACTIVE'),
    ('69000000-0000-4000-8000-000000000205', '69000000-0000-4000-8000-000000000103', '68000000-0000-4000-8000-000000000002',
     'Giày sneaker trắng', 'giay-sneaker-trang-69000000-0000-4000-8000-000000000205', 'Giày sneaker màu trắng, đế cao su', 'ACTIVE')
ON CONFLICT DO NOTHING;
