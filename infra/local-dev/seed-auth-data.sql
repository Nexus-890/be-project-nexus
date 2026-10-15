-- =========================================================================
-- SEED DATA CHO USER_DB (Dùng để test Login, Roles và Permissions)
-- Chạy script này trên database: user_db (PostgreSQL port 5432)
-- Mật khẩu mặc định cho các tài khoản bên dưới: Password123@
-- Hash BCrypt chuẩn: $2a$10$Fexry5gKr9/9nw5DQVvYeeLnGOXBaBouRe1qdol1PzUL9XvktAA5.
-- =========================================================================

-- =========================================================================
-- BƯỚC 0: XÓA DỮ LIỆU CŨ (CLEANUP TRƯỚC KHI SEED)
-- Đảm bảo xóa sạch dữ liệu cũ/lỗi để mỗi lần chạy đều ra kết quả nhất quán
-- =========================================================================

-- 0.1 Xóa phân quyền user cũ
DELETE FROM user_roles 
WHERE user_id IN (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    '33333333-3333-3333-3333-333333333333'
) OR user_id IN (
    SELECT id FROM users WHERE lower(email) IN ('sellera@nexus.local', 'sellerb@nexus.local', 'buyer@nexus.local')
);

-- 0.2 Xóa token đăng nhập / reset pass cũ nếu có
DELETE FROM refresh_tokens 
WHERE user_id IN (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    '33333333-3333-3333-3333-333333333333'
) OR user_id IN (
    SELECT id FROM users WHERE lower(email) IN ('sellera@nexus.local', 'sellerb@nexus.local', 'buyer@nexus.local')
);

DELETE FROM password_reset_tokens 
WHERE user_id IN (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    '33333333-3333-3333-3333-333333333333'
) OR user_id IN (
    SELECT id FROM users WHERE lower(email) IN ('sellera@nexus.local', 'sellerb@nexus.local', 'buyer@nexus.local')
);

DELETE FROM user_reputations 
WHERE user_id IN (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    '33333333-3333-3333-3333-333333333333'
) OR user_id IN (
    SELECT id FROM users WHERE lower(email) IN ('sellera@nexus.local', 'sellerb@nexus.local', 'buyer@nexus.local')
);

-- 0.3 Xóa người dùng cũ (kể cả trường hợp hoa hay thường: sellera, sellerA, ...)
DELETE FROM users 
WHERE id IN (
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    '33333333-3333-3333-3333-333333333333'
) OR lower(email) IN ('sellera@nexus.local', 'sellerb@nexus.local', 'buyer@nexus.local');

-- 0.4 Xóa liên kết role và permission cũ
DELETE FROM role_permissions 
WHERE role_id IN (SELECT id FROM roles WHERE code IN ('SELLER', 'BUYER', 'ADMIN'))
   OR permission_id IN (SELECT id FROM permissions WHERE code IN ('PRODUCT.LIST', 'PRODUCT.CREATE', 'PRODUCT.UPDATE', 'PRODUCT.DELETE', 'PROFILE.VIEW', 'PROFILE.UPDATE'));

-- 0.5 Xóa các Role và Permission cũ
DELETE FROM roles WHERE code IN ('SELLER', 'BUYER', 'ADMIN');

DELETE FROM permissions WHERE code IN (
  'PRODUCT.LIST', 'PRODUCT.CREATE', 'PRODUCT.UPDATE', 'PRODUCT.DELETE',
  'PROFILE.VIEW', 'PROFILE.UPDATE'
);


-- =========================================================================
-- BƯỚC 1: TẠO CÁC ROLES CƠ BẢN
-- =========================================================================
INSERT INTO roles (id, code, name, description, is_system)
VALUES 
  ('11111111-0000-0000-0000-000000000001', 'SELLER', 'Seller', 'Nhà bán hàng', true),
  ('11111111-0000-0000-0000-000000000002', 'BUYER', 'Buyer', 'Người mua hàng', true),
  ('11111111-0000-0000-0000-000000000003', 'ADMIN', 'Admin', 'Quản trị viên', true);

-- =========================================================================
-- BƯỚC 2: TẠO CÁC PERMISSIONS THEO SRS 2.7
-- =========================================================================
INSERT INTO permissions (id, code, name, description)
VALUES
  ('22222222-0000-0000-0000-000000000001', 'PRODUCT.LIST', 'Xem danh sách sản phẩm của tôi', 'Permission xem /api/v1/products/mine'),
  ('22222222-0000-0000-0000-000000000002', 'PRODUCT.CREATE', 'Tạo sản phẩm', 'Permission tạo sản phẩm'),
  ('22222222-0000-0000-0000-000000000003', 'PRODUCT.UPDATE', 'Cập nhật sản phẩm', 'Permission sửa sản phẩm'),
  ('22222222-0000-0000-0000-000000000004', 'PRODUCT.DELETE', 'Xóa mềm sản phẩm', 'Permission xóa sản phẩm'),
  ('22222222-0000-0000-0000-000000000005', 'PROFILE.VIEW', 'Xem hồ sơ cá nhân', 'Permission xem profile'),
  ('22222222-0000-0000-0000-000000000006', 'PROFILE.UPDATE', 'Cập nhật hồ sơ cá nhân', 'Permission sửa profile');

-- =========================================================================
-- BƯỚC 3: GÁN PERMISSIONS CHO ROLES
-- =========================================================================
-- Gán quyền cho Role SELLER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'SELLER' AND p.code IN ('PRODUCT.LIST', 'PRODUCT.CREATE', 'PRODUCT.UPDATE', 'PRODUCT.DELETE');

-- Gán quyền cho Role BUYER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'BUYER' AND p.code IN ('PROFILE.VIEW', 'PROFILE.UPDATE');

-- =========================================================================
-- BƯỚC 4: TẠO USERS (TOÀN BỘ EMAIL DÙNG CHỮ THƯỜNG LOWERCASE)
-- =========================================================================

-- 4.1 Tạo User Seller A (ID: 11111111-1111-1111-1111-111111111111)
INSERT INTO users (id, email, phone, password_hash, full_name, status)
VALUES (
  '11111111-1111-1111-1111-111111111111',
  'sellera@nexus.local',
  '0901234567',
  '$2a$10$Fexry5gKr9/9nw5DQVvYeeLnGOXBaBouRe1qdol1PzUL9XvktAA5.',
  'Nguyen Van Seller A',
  'ACTIVE'
);

-- Gán role SELLER cho Seller A
INSERT INTO user_roles (user_id, role_id)
SELECT '11111111-1111-1111-1111-111111111111', r.id FROM roles r WHERE r.code = 'SELLER';

-- 4.2 Tạo User Seller B (ID: 22222222-2222-2222-2222-222222222222)
INSERT INTO users (id, email, phone, password_hash, full_name, status)
VALUES (
  '22222222-2222-2222-2222-222222222222',
  'sellerb@nexus.local',
  '0907654321',
  '$2a$10$Fexry5gKr9/9nw5DQVvYeeLnGOXBaBouRe1qdol1PzUL9XvktAA5.',
  'Tran Van Seller B',
  'ACTIVE'
);

-- Gán role SELLER cho Seller B
INSERT INTO user_roles (user_id, role_id)
SELECT '22222222-2222-2222-2222-222222222222', r.id FROM roles r WHERE r.code = 'SELLER';

-- 4.3 Tạo User Buyer (ID: 33333333-3333-3333-3333-333333333333)
INSERT INTO users (id, email, phone, password_hash, full_name, status)
VALUES (
  '33333333-3333-3333-3333-333333333333',
  'buyer@nexus.local',
  '0909998888',
  '$2a$10$Fexry5gKr9/9nw5DQVvYeeLnGOXBaBouRe1qdol1PzUL9XvktAA5.',
  'Le Thi Buyer',
  'ACTIVE'
);

-- Gán role BUYER cho Buyer
INSERT INTO user_roles (user_id, role_id)
SELECT '33333333-3333-3333-3333-333333333333', r.id FROM roles r WHERE r.code = 'BUYER';
