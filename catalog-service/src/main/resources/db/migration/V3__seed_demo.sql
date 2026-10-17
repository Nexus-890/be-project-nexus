INSERT INTO categories (id, parent_id, name, slug, description, level, path) VALUES
    ('69000000-0000-4000-8000-000000000101', NULL, 'Điện tử', 'dien-tu', 'Thiết bị điện tử', 1, '/dien-tu/'),
    ('69000000-0000-4000-8000-000000000103', NULL, 'Thời trang', 'thoi-trang', 'Quần áo và giày dép', 1, '/thoi-trang/')
ON CONFLICT DO NOTHING;

INSERT INTO categories (id, parent_id, name, slug, description, level, path) VALUES
    ('69000000-0000-4000-8000-000000000102', '69000000-0000-4000-8000-000000000101', 'Điện thoại', 'dien-thoai', 'Điện thoại di động', 2, '/dien-tu/dien-thoai/')
ON CONFLICT DO NOTHING;
