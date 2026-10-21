-- Run after catalog-service Flyway V1-V4 against the isolated demo database.
-- Re-running restores only the explicit 6d260021 demo IDs below. Other rows stay intact.
-- psql -v ON_ERROR_STOP=1 -d nexus_demo_catalog_db -f seed-catalog.sql
-- Prices are VND. Every ACTIVE demo product has priced ACTIVE SKUs and images.
-- Ten root categories match the home category navigation; older demo rows stay intact.

BEGIN;

DO $$
BEGIN
    IF current_database() <> 'nexus_demo_catalog_db' THEN
        RAISE EXCEPTION 'seed-catalog.sql requires nexus_demo_catalog_db, connected to %', current_database();
    END IF;
END $$;

CREATE TEMP TABLE nexus_demo_categories (
    id uuid PRIMARY KEY,
    parent_id uuid,
    name varchar(150),
    slug varchar(180),
    description text,
    level smallint,
    path varchar(500)
) ON COMMIT DROP;

INSERT INTO nexus_demo_categories VALUES
('6d260021-0000-4000-8000-000000000101', NULL, 'Điện tử demo', 'demo-dien-tu', 'Điện thoại, máy tính và âm thanh', 1, '/demo-dien-tu/'),
('6d260021-0000-4000-8000-000000000102', NULL, 'Thời trang demo', 'demo-thoi-trang', 'Trang phục và giày dép hằng ngày', 1, '/demo-thoi-trang/'),
('6d260021-0000-4000-8000-000000000103', NULL, 'Nhà cửa demo', 'demo-nha-cua', 'Đồ dùng cho góc làm việc và căn bếp', 1, '/demo-nha-cua/'),
('6d260021-0000-4000-8000-000000000104', '6d260021-0000-4000-8000-000000000101', 'Điện thoại demo', 'demo-dien-thoai', 'Thiết bị di động', 2, '/demo-dien-tu/demo-dien-thoai/'),
('6d260021-0000-4000-8000-000000000105', '6d260021-0000-4000-8000-000000000101', 'Laptop demo', 'demo-laptop', 'Máy tính xách tay', 2, '/demo-dien-tu/demo-laptop/'),
('6d260021-0000-4000-8000-000000000106', '6d260021-0000-4000-8000-000000000101', 'Âm thanh demo', 'demo-am-thanh', 'Tai nghe và thiết bị âm thanh', 2, '/demo-dien-tu/demo-am-thanh/'),
('6d260021-0000-4000-8000-000000000107', '6d260021-0000-4000-8000-000000000102', 'Áo demo', 'demo-ao', 'Áo thun, sơ mi và áo khoác', 2, '/demo-thoi-trang/demo-ao/'),
('6d260021-0000-4000-8000-000000000108', '6d260021-0000-4000-8000-000000000102', 'Giày demo', 'demo-giay', 'Sneaker và giày thể thao', 2, '/demo-thoi-trang/demo-giay/'),
('6d260021-0000-4000-8000-000000000109', '6d260021-0000-4000-8000-000000000103', 'Góc làm việc demo', 'demo-goc-lam-viec', 'Đèn bàn và phụ kiện làm việc', 2, '/demo-nha-cua/demo-goc-lam-viec/'),
('6d260021-0000-4000-8000-000000000110', '6d260021-0000-4000-8000-000000000103', 'Đồ bếp demo', 'demo-do-bep', 'Ly, cốc và đồ dùng nhà bếp', 2, '/demo-nha-cua/demo-do-bep/'),
('6d260021-0000-4000-8000-000000000111', '6d260021-0000-4000-8000-000000000104', 'Smartphone demo', 'demo-smartphone', 'Danh mục cấp 3 được giữ lại cho dữ liệu cũ', 3, '/demo-dien-tu/demo-dien-thoai/demo-smartphone/'),
('6d260021-0000-4000-8000-000000000112', '6d260021-0000-4000-8000-000000000105', 'Ultrabook demo', 'demo-ultrabook', 'Laptop mỏng nhẹ cho học tập và làm việc', 3, '/demo-dien-tu/demo-laptop/demo-ultrabook/'),
('6d260021-0000-4000-8000-000000000113', '6d260021-0000-4000-8000-000000000107', 'Áo cotton demo', 'demo-ao-cotton', 'Áo thun cotton nhiều màu và kích cỡ', 3, '/demo-thoi-trang/demo-ao/demo-ao-cotton/'),
('6d260021-0000-4000-8000-000000000114', '6d260021-0000-4000-8000-000000000108', 'Giày chạy bộ demo', 'demo-giay-chay-bo', 'Giày thể thao nhẹ', 3, '/demo-thoi-trang/demo-giay/demo-giay-chay-bo/'),
('6d260021-0000-4000-8000-000000000115', '6d260021-0000-4000-8000-000000000101', 'Phụ kiện demo', 'demo-phu-kien', 'Phụ kiện đang chuẩn bị bán', 2, '/demo-dien-tu/demo-phu-kien/'),
('6d260021-0000-4000-8000-000000000116', '6d260021-0000-4000-8000-000000000103', 'Danh mục trống demo', 'demo-danh-muc-trong', 'Danh mục không có sản phẩm hoặc danh mục con để thử xóa', 2, '/demo-nha-cua/demo-danh-muc-trong/'),
('6d260021-0000-4000-8000-000000000701', NULL, 'Phones', 'phones', 'Điện thoại thông minh và thiết bị di động', 1, '/phones/'),
('6d260021-0000-4000-8000-000000000702', NULL, 'Laptops', 'laptops', 'Máy tính xách tay cho học tập và làm việc', 1, '/laptops/'),
('6d260021-0000-4000-8000-000000000703', NULL, 'Cameras', 'cameras', 'Máy ảnh và thiết bị nhiếp ảnh', 1, '/cameras/'),
('6d260021-0000-4000-8000-000000000704', NULL, 'Watches', 'watches', 'Đồng hồ đeo tay và đồng hồ thông minh', 1, '/watches/'),
('6d260021-0000-4000-8000-000000000705', NULL, 'Audio', 'audio', 'Tai nghe và thiết bị âm thanh', 1, '/audio/'),
('6d260021-0000-4000-8000-000000000706', NULL, 'Fashion', 'fashion', 'Trang phục, giày dép và phụ kiện', 1, '/fashion/'),
('6d260021-0000-4000-8000-000000000707', NULL, 'Gaming', 'gaming', 'Tay cầm và phụ kiện chơi game', 1, '/gaming/'),
('6d260021-0000-4000-8000-000000000708', NULL, 'Home', 'home', 'Đồ dùng cho nhà cửa và góc làm việc', 1, '/home/'),
('6d260021-0000-4000-8000-000000000709', NULL, 'Books', 'books', 'Sách kỹ năng và sách đọc hằng ngày', 1, '/books/'),
('6d260021-0000-4000-8000-000000000710', NULL, 'Collectibles', 'collectibles', 'Mô hình và bộ sưu tập', 1, '/collectibles/');

INSERT INTO categories (id, parent_id, name, slug, description, level, path)
SELECT id, parent_id, name, slug, description, level, path
FROM nexus_demo_categories
ORDER BY level, id
ON CONFLICT (id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    name = EXCLUDED.name,
    slug = EXCLUDED.slug,
    description = EXCLUDED.description,
    level = EXCLUDED.level,
    path = EXCLUDED.path,
    deleted_at = NULL,
    updated_at = now();

CREATE TEMP TABLE nexus_demo_products (
    id uuid PRIMARY KEY,
    category_id uuid,
    name varchar(255),
    slug varchar(300),
    description text,
    status varchar(20),
    auction_enable boolean,
    image_photo varchar(100),
    gallery_photo varchar(100)
) ON COMMIT DROP;

INSERT INTO nexus_demo_products VALUES
('6d260021-0000-4000-8000-000000000201', '6d260021-0000-4000-8000-000000000701', 'iPhone 15 128GB', 'demo-iphone-15-128gb', 'Điện thoại màn hình 6,1 inch, bộ nhớ 128GB, có màu đen và xanh dương.', 'ACTIVE', false, 'photo-1511707171634-5f897ff02aa9', 'photo-1512499617640-c74ae3a79d37'),
('6d260021-0000-4000-8000-000000000202', '6d260021-0000-4000-8000-000000000701', 'Samsung Galaxy S24 256GB', 'demo-samsung-galaxy-s24-256gb', 'Điện thoại Android nhỏ gọn, màn hình sáng, bộ nhớ 256GB.', 'ACTIVE', false, 'photo-1512499617640-c74ae3a79d37', 'photo-1511707171634-5f897ff02aa9'),
('6d260021-0000-4000-8000-000000000203', '6d260021-0000-4000-8000-000000000701', 'Xiaomi Redmi Note 13', 'demo-xiaomi-redmi-note-13', 'Điện thoại pin 5000mAh cho nhu cầu học tập và giải trí hằng ngày.', 'ACTIVE', false, 'photo-1511707171634-5f897ff02aa9', 'photo-1512499617640-c74ae3a79d37'),
('6d260021-0000-4000-8000-000000000204', '6d260021-0000-4000-8000-000000000702', 'MacBook Air M2 13 inch', 'demo-macbook-air-m2-13', 'Laptop mỏng nhẹ với bộ nhớ 8GB và SSD 256GB, phù hợp học tập và công việc văn phòng.', 'ACTIVE', false, 'photo-1517336714731-489689fd1ca8', 'photo-1496181133206-80ce9b88a853'),
('6d260021-0000-4000-8000-000000000205', '6d260021-0000-4000-8000-000000000702', 'Lenovo IdeaPad Slim 5', 'demo-lenovo-ideapad-slim-5', 'Laptop 14 inch, RAM 16GB, SSD 512GB cho nhiều tác vụ cùng lúc.', 'ACTIVE', false, 'photo-1496181133206-80ce9b88a853', 'photo-1525547719571-a2d4ac8945e2'),
('6d260021-0000-4000-8000-000000000206', '6d260021-0000-4000-8000-000000000705', 'Tai nghe Sony WH-1000XM5', 'demo-sony-wh-1000xm5', 'Tai nghe không dây chống ồn chủ động, đệm tai êm và thiết kế nhẹ cho nghe nhạc mỗi ngày.', 'ACTIVE', false, 'photo-1505740420928-5e560c06d30e', 'photo-1546435770-a3e426bf472b'),
('6d260021-0000-4000-8000-000000000207', '6d260021-0000-4000-8000-000000000706', 'Áo thun cotton basic', 'demo-ao-thun-cotton-basic', 'Áo thun unisex 100% cotton, phom dễ mặc, có màu trắng và đen với nhiều kích cỡ.', 'ACTIVE', false, 'photo-1521572163474-6864f9cf17ab', 'photo-1503341504253-dff4815485f1'),
('6d260021-0000-4000-8000-000000000208', '6d260021-0000-4000-8000-000000000706', 'Áo sơ mi linen dài tay', 'demo-ao-so-mi-linen', 'Áo sơ mi linen thoáng mát cho đi làm và dạo phố, hai màu trung tính.', 'ACTIVE', false, 'photo-1596755094514-f87e34085b2c', 'photo-1603252109303-2751441dd157'),
('6d260021-0000-4000-8000-000000000209', '6d260021-0000-4000-8000-000000000706', 'Giày sneaker trắng', 'demo-giay-sneaker-trang', 'Sneaker đế cao su, thiết kế tối giản, phù hợp phối đồ hằng ngày.', 'ACTIVE', false, 'photo-1549298916-b41d501d3772', 'photo-1600185365926-3a2ce3cdb9eb'),
('6d260021-0000-4000-8000-000000000210', '6d260021-0000-4000-8000-000000000706', 'Giày chạy bộ Flex Run', 'demo-giay-chay-bo-flex-run', 'Giày chạy bộ nhẹ với lớp đệm mềm, có hai phối màu và kích cỡ.', 'ACTIVE', false, 'photo-1542291026-7eec264c27ff', 'photo-1549298916-b41d501d3772'),
('6d260021-0000-4000-8000-000000000211', '6d260021-0000-4000-8000-000000000708', 'Đèn bàn LED tối giản', 'demo-den-ban-led', 'Đèn bàn ánh sáng ấm, kiểu dáng gọn cho góc học tập và làm việc.', 'ACTIVE', false, 'photo-1507473885765-e6ed057f782c', 'photo-1513506003901-1e6a229e2d15'),
('6d260021-0000-4000-8000-000000000212', '6d260021-0000-4000-8000-000000000708', 'Cốc gốm thủ công', 'demo-coc-gom-thu-cong', 'Cốc gốm thủ công dung tích 350ml, lớp men mịn, có màu trắng và be cho bàn ăn hoặc góc cà phê.', 'ACTIVE', false, 'photo-1514228742587-6b1558fcca3d', 'photo-1513558161293-cdaf765edfd7'),
('6d260021-0000-4000-8000-000000000213', '6d260021-0000-4000-8000-000000000704', 'Đồng hồ thông minh — bản nháp', 'demo-dong-ho-thong-minh-draft', 'Bản nháp của seller để thử sửa, xuất bản ACTIVE hoặc xóa. Không hiển thị trên trang mua hàng.', 'DRAFT', false, 'photo-1523275335684-37898b6baf30', 'photo-1508685096489-7aacd43bd3b1'),
('6d260021-0000-4000-8000-000000000214', '6d260021-0000-4000-8000-000000000706', 'Balo đi học — bản nháp', 'demo-balo-di-hoc-draft', 'Bản nháp thứ hai để thử cập nhật tên, danh mục và cờ đấu giá rồi xuất bản.', 'DRAFT', true, 'photo-1553062407-98eeb64c6a62', 'photo-1471286174890-9c112ffca5b4'),
('6d260021-0000-4000-8000-000000000215', '6d260021-0000-4000-8000-000000000706', 'Áo hoodie nỉ — ngừng bán', 'demo-ao-hoodie-inactive', 'Sản phẩm INACTIVE của seller để thử kích hoạt lại. Không hiển thị qua API chi tiết công khai.', 'INACTIVE', false, 'photo-1556821840-3a63f95609a7', 'photo-1578681994506-b8f463449011'),
('6d260021-0000-4000-8000-000000000216', '6d260021-0000-4000-8000-000000000707', 'Bàn phím cơ compact — ngừng bán', 'demo-ban-phim-co-inactive', 'Sản phẩm INACTIVE có SKU để thử kích hoạt, chỉnh sửa hoặc xóa khỏi danh sách seller.', 'INACTIVE', false, 'photo-1587829741301-dc798b83add3', 'photo-1541140532154-b024d705b90a'),
('6d260021-0000-4000-8000-000000000217', '6d260021-0000-4000-8000-000000000703', 'Máy ảnh Sony Alpha A6400', 'demo-sony-alpha-a6400', 'Máy ảnh mirrorless nhỏ gọn kèm ống kính 16–50mm, phù hợp chụp du lịch và chân dung.', 'ACTIVE', false, 'photo-1502920917128-1aa500764cbd', 'photo-1516035069371-29a1b244cc32'),
('6d260021-0000-4000-8000-000000000218', '6d260021-0000-4000-8000-000000000704', 'Đồng hồ Seiko 5 Automatic', 'demo-seiko-5-automatic', 'Đồng hồ cơ tự động mặt tròn 40mm, dây thép không gỉ, hai lựa chọn màu mặt số.', 'ACTIVE', false, 'photo-1523275335684-37898b6baf30', 'photo-1508685096489-7aacd43bd3b1'),
('6d260021-0000-4000-8000-000000000219', '6d260021-0000-4000-8000-000000000707', 'Tay cầm Xbox Wireless', 'demo-xbox-wireless-controller', 'Tay cầm không dây kết nối Bluetooth cho máy tính và console, có hai phối màu.', 'ACTIVE', false, 'photo-1592840496694-26d035b52b48', 'photo-1612287230202-1ff1d85d1bdf'),
('6d260021-0000-4000-8000-000000000220', '6d260021-0000-4000-8000-000000000709', 'Atomic Habits — Thay đổi tí hon', 'demo-atomic-habits', 'Sách về xây dựng thói quen bền vững và cải thiện các hoạt động nhỏ mỗi ngày.', 'ACTIVE', false, 'photo-1544947950-fa07a98d237f', 'photo-1512820790803-83ca734da794'),
('6d260021-0000-4000-8000-000000000221', '6d260021-0000-4000-8000-000000000710', 'Bộ mô hình LEGO Botanical', 'demo-lego-botanical', 'Bộ lắp ráp hoa trang trí dành cho người yêu mô hình và sưu tập, có hai phiên bản màu.', 'ACTIVE', false, 'photo-1587654780291-39c9404d746b', 'photo-1587654780291-39c9404d746b'),
('6d260021-0000-4000-8000-000000000222', '6d260021-0000-4000-8000-000000000708', 'Vỏ gối sofa cotton', 'demo-vo-goi-sofa-cotton', 'Vỏ gối cotton dệt mềm kích thước 45×45cm, khóa kéo kín và hai màu dễ phối nội thất.', 'ACTIVE', false, 'photo-1584100936595-c0654b55a2e2', 'photo-1584100936595-c0654b55a2e2');

INSERT INTO products (id, category_id, seller_id, name, slug, description, status, auction_enable, has_order, in_auction)
SELECT id, category_id, '68000000-0000-4000-8000-000000000002'::uuid,
       name, slug, description, status, auction_enable, false, false
FROM nexus_demo_products
ON CONFLICT (id) DO UPDATE SET
    category_id = EXCLUDED.category_id,
    seller_id = EXCLUDED.seller_id,
    name = EXCLUDED.name,
    slug = EXCLUDED.slug,
    description = EXCLUDED.description,
    status = EXCLUDED.status,
    auction_enable = EXCLUDED.auction_enable,
    has_order = EXCLUDED.has_order,
    in_auction = EXCLUDED.in_auction,
    deleted_at = NULL,
    updated_at = now();

-- The first phone SKU and white size-M T-shirt SKU are used by seed-commerce.sql.
INSERT INTO skus (id, product_id, sku_code, color, size, price, currency, status) VALUES
('6d260021-0000-4000-8000-000000000301', '6d260021-0000-4000-8000-000000000201', 'DEMO-IP15-BLK-128', 'Black', '128GB', 16990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000302', '6d260021-0000-4000-8000-000000000201', 'DEMO-IP15-BLU-128', 'Blue', '128GB', 17490000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000303', '6d260021-0000-4000-8000-000000000202', 'DEMO-S24-GRY-256', 'Gray', '256GB', 13990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000304', '6d260021-0000-4000-8000-000000000202', 'DEMO-S24-VIO-256', 'Violet', '256GB', 14490000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000305', '6d260021-0000-4000-8000-000000000203', 'DEMO-RN13-BLK-128', 'Black', '128GB', 4990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000306', '6d260021-0000-4000-8000-000000000203', 'DEMO-RN13-BLU-256', 'Blue', '256GB', 5490000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000307', '6d260021-0000-4000-8000-000000000204', 'DEMO-MBA-MID-256', 'Midnight', '8GB/256GB', 21990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000308', '6d260021-0000-4000-8000-000000000204', 'DEMO-MBA-SLV-512', 'Silver', '8GB/512GB', 24990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000309', '6d260021-0000-4000-8000-000000000205', 'DEMO-IPS5-GRY-512', 'Gray', '16GB/512GB', 16990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000310', '6d260021-0000-4000-8000-000000000205', 'DEMO-IPS5-BLU-1TB', 'Blue', '16GB/1TB', 18490000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000311', '6d260021-0000-4000-8000-000000000206', 'DEMO-XM5-BLK-STD', 'Black', 'Standard', 6990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000312', '6d260021-0000-4000-8000-000000000206', 'DEMO-XM5-BLU-STD', 'Blue', 'Standard', 7190000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000313', '6d260021-0000-4000-8000-000000000207', 'DEMO-TEE-WHT-M', 'White', 'M', 199000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000314', '6d260021-0000-4000-8000-000000000207', 'DEMO-TEE-BLK-L', 'Black', 'L', 219000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000315', '6d260021-0000-4000-8000-000000000208', 'DEMO-LINEN-BGE-M', 'Beige', 'M', 449000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000316', '6d260021-0000-4000-8000-000000000208', 'DEMO-LINEN-WHT-L', 'White', 'L', 479000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000317', '6d260021-0000-4000-8000-000000000209', 'DEMO-SNK-WHT-40', 'White', '40', 799000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000318', '6d260021-0000-4000-8000-000000000209', 'DEMO-SNK-WHT-42', 'White', '42', 849000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000319', '6d260021-0000-4000-8000-000000000210', 'DEMO-RUN-RED-41', 'Red', '41', 1099000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000320', '6d260021-0000-4000-8000-000000000210', 'DEMO-RUN-BLK-43', 'Black', '43', 1199000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000321', '6d260021-0000-4000-8000-000000000211', 'DEMO-LAMP-WHT-STD', 'White', 'Standard', 599000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000322', '6d260021-0000-4000-8000-000000000211', 'DEMO-LAMP-BLK-STD', 'Black', 'Standard', 649000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000323', '6d260021-0000-4000-8000-000000000213', 'DEMO-WATCH-DRAFT', 'Black', '42mm', 1299000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000324', '6d260021-0000-4000-8000-000000000214', 'DEMO-BAG-DRAFT', 'Navy', '20L', 499000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000325', '6d260021-0000-4000-8000-000000000215', 'DEMO-HOODIE-OFF', 'Gray', 'L', 599000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000326', '6d260021-0000-4000-8000-000000000216', 'DEMO-KEYBOARD-OFF', 'White', '75%', 1299000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000327', '6d260021-0000-4000-8000-000000000206', 'DEMO-XM5-SLV-OFF', 'Silver', 'Standard', 6890000.00, 'VND', 'INACTIVE'),
('6d260021-0000-4000-8000-000000000328', '6d260021-0000-4000-8000-000000000212', 'DEMO-MUG-WHT-350', 'White', '350ml', 249000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000329', '6d260021-0000-4000-8000-000000000212', 'DEMO-MUG-BGE-350', 'Beige', '350ml', 269000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000330', '6d260021-0000-4000-8000-000000000217', 'DEMO-A6400-BLK-KIT', 'Black', '16-50mm Kit', 17990000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000331', '6d260021-0000-4000-8000-000000000217', 'DEMO-A6400-SLV-KIT', 'Silver', '16-50mm Kit', 18490000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000332', '6d260021-0000-4000-8000-000000000218', 'DEMO-SEIKO-BLU-40', 'Blue', '40mm', 4299000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000333', '6d260021-0000-4000-8000-000000000218', 'DEMO-SEIKO-BLK-40', 'Black', '40mm', 4499000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000334', '6d260021-0000-4000-8000-000000000219', 'DEMO-XBOX-WHT-STD', 'White', 'Standard', 1499000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000335', '6d260021-0000-4000-8000-000000000219', 'DEMO-XBOX-BLK-STD', 'Black', 'Standard', 1599000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000336', '6d260021-0000-4000-8000-000000000220', 'DEMO-BOOK-AH-PAPER', 'Paperback', 'Vietnamese', 179000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000337', '6d260021-0000-4000-8000-000000000220', 'DEMO-BOOK-AH-HARD', 'Hardcover', 'English', 329000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000338', '6d260021-0000-4000-8000-000000000221', 'DEMO-LEGO-PINK-STD', 'Pink', 'Flower Bouquet', 1399000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000339', '6d260021-0000-4000-8000-000000000221', 'DEMO-LEGO-YLW-STD', 'Yellow', 'Wildflower Bouquet', 1499000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000340', '6d260021-0000-4000-8000-000000000222', 'DEMO-CUSHION-BGE-45', 'Beige', '45x45cm', 159000.00, 'VND', 'ACTIVE'),
('6d260021-0000-4000-8000-000000000341', '6d260021-0000-4000-8000-000000000222', 'DEMO-CUSHION-GRN-45', 'Green', '45x45cm', 179000.00, 'VND', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    product_id = EXCLUDED.product_id,
    sku_code = EXCLUDED.sku_code,
    color = EXCLUDED.color,
    size = EXCLUDED.size,
    price = EXCLUDED.price,
    currency = EXCLUDED.currency,
    status = EXCLUDED.status,
    updated_at = now();

-- Deterministic image IDs: product suffix + 200 for main and + 300 for gallery.
-- Photos are illustrative public images. Loading the demo does not fetch them.
INSERT INTO product_images (id, product_id, image_url, sort_order)
SELECT ('6d260021-0000-4000-8000-' || lpad((right(p.id::text, 12)::integer + i.offset_id)::text, 12, '0'))::uuid,
       p.id,
       'https://images.unsplash.com/' || i.photo || '?auto=format&fit=crop&w=1200&q=80',
       i.sort_order::smallint
FROM nexus_demo_products p
CROSS JOIN LATERAL (VALUES
    (200, p.image_photo, 0),
    (300, p.gallery_photo, 1)
) AS i(offset_id, photo, sort_order)
ON CONFLICT (id) DO UPDATE SET
    product_id = EXCLUDED.product_id,
    image_url = EXCLUDED.image_url,
    sort_order = EXCLUDED.sort_order;

INSERT INTO product_stats (product_id, order_count, view_count)
SELECT id, 0, 100 + right(id::text, 12)::integer
FROM nexus_demo_products
ON CONFLICT (product_id) DO UPDATE SET
    order_count = EXCLUDED.order_count,
    view_count = EXCLUDED.view_count,
    updated_at = now();

UPDATE categories c
SET product_count = (SELECT count(*) FROM products p WHERE p.category_id = c.id AND p.deleted_at IS NULL),
    updated_at = now()
WHERE c.id IN (SELECT id FROM nexus_demo_categories);

-- Validate the seed before commit without deleting user-created records.
DO $$
BEGIN
    IF (SELECT count(*) FROM products p JOIN nexus_demo_products d USING (id) WHERE p.status = 'ACTIVE' AND p.deleted_at IS NULL) <> 18 THEN
        RAISE EXCEPTION 'Expected 18 ACTIVE catalog demo products';
    END IF;
    IF EXISTS (
        SELECT 1 FROM nexus_demo_products d
        WHERE (SELECT count(*) FROM product_images i WHERE i.product_id = d.id) < 2
    ) THEN
        RAISE EXCEPTION 'Expected at least two images for each catalog demo product';
    END IF;
    IF EXISTS (
        SELECT 1 FROM products p JOIN nexus_demo_products d USING (id)
        WHERE p.status = 'ACTIVE' AND NOT EXISTS (
            SELECT 1 FROM skus s WHERE s.product_id = p.id AND s.status = 'ACTIVE' AND s.price > 0
        )
    ) THEN
        RAISE EXCEPTION 'Every ACTIVE demo product must have a priced ACTIVE SKU';
    END IF;
    IF EXISTS (
        SELECT 1 FROM nexus_demo_categories c
        WHERE c.id BETWEEN '6d260021-0000-4000-8000-000000000701'::uuid AND '6d260021-0000-4000-8000-000000000710'::uuid
          AND NOT EXISTS (
              SELECT 1 FROM products p JOIN skus s ON s.product_id = p.id
              WHERE p.category_id = c.id AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
                AND s.status = 'ACTIVE' AND s.price > 0
          )
    ) THEN
        RAISE EXCEPTION 'Each of the ten home categories must contain an addable product';
    END IF;
END $$;

COMMIT;

SELECT 'categories' AS kind, count(*) AS seeded_rows FROM categories WHERE id::text LIKE '6d260021-%'
UNION ALL
SELECT 'products', count(*) FROM products WHERE id::text LIKE '6d260021-%'
UNION ALL
SELECT 'skus', count(*) FROM skus WHERE id::text LIKE '6d260021-%'
UNION ALL
SELECT 'images', count(*) FROM product_images WHERE id::text LIKE '6d260021-%';
