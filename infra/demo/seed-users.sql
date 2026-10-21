-- Demo profiles for the isolated database initialized by user-service Flyway.
-- Password hashes are copied unchanged from V3__roles_and_accounts.sql and
-- verified with Spring Security BCrypt against infra/staging/.env.example.
-- Re-running this script updates only the three deterministic demo accounts.
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'nexus_demo_user_db' THEN
        RAISE EXCEPTION 'Run seed-users.sql only against nexus_demo_user_db';
    END IF;
    IF (SELECT count(*) FROM roles
        WHERE code IN ('ADMIN', 'SELLER', 'BUYER') AND deleted_at IS NULL) <> 3 THEN
        RAISE EXCEPTION 'Apply user-service Flyway migrations before demo seed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('68000000-0000-4000-8000-000000000001'::uuid, 'admin@nexus.local'),
            ('68000000-0000-4000-8000-000000000002'::uuid, 'seller@nexus.local'),
            ('68000000-0000-4000-8000-000000000003'::uuid, 'buyer@nexus.local')
        ) AS demo(id, email)
        JOIN users u ON u.email = demo.email AND u.id <> demo.id
    ) THEN
        RAISE EXCEPTION 'Demo email belongs to a different account; nothing was changed';
    END IF;
END $$;

INSERT INTO users (
    id, email, phone, password_hash, full_name, gender, address, date_of_birth, status
) VALUES
    ('68000000-0000-4000-8000-000000000001', 'admin@nexus.local', '0901000001',
     '$2a$12$oDN8hodYJ623e3Sxtn5ETu7N56a9apye2ftJgNw3Y1zoVHv7sCl5K',
     'Nguyen Minh An', 'MALE', '12 Nguyen Hue, Ben Nghe, Ho Chi Minh City', DATE '1992-03-18', 'ACTIVE'),
    ('68000000-0000-4000-8000-000000000002', 'seller@nexus.local', '0901000002',
     '$2a$12$53kXGdSfln.pTzXru.1Dm.w79Hh2aGS/ORQXUVMe2b7PadqjoFD5i',
     'Tran Thu Ha', 'FEMALE', '68 Le Loi, Ben Thanh, Ho Chi Minh City', DATE '1995-07-12', 'ACTIVE'),
    ('68000000-0000-4000-8000-000000000003', 'buyer@nexus.local', '0901000003',
     '$2a$12$KlUfMshqogOKyL2OWyFuD.qro8w8D8CS68Qm7SU0wNI5o94JLihlK',
     'Le Quoc Bao', 'MALE', '123 Vo Van Tan, Vo Thi Sau, Ho Chi Minh City', DATE '1998-11-24', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    email = EXCLUDED.email,
    phone = EXCLUDED.phone,
    password_hash = EXCLUDED.password_hash,
    full_name = EXCLUDED.full_name,
    gender = EXCLUDED.gender,
    address = EXCLUDED.address,
    date_of_birth = EXCLUDED.date_of_birth,
    status = EXCLUDED.status,
    deleted_at = NULL,
    failed_login_count = 0,
    locked_until = NULL,
    updated_at = now();

-- Keep the role-specific privilege sets defined by the existing V3 migration.
-- ADMIN manages users/categories; SELLER manages its products/SKUs; BUYER carts.
INSERT INTO user_roles (user_id, role_id)
SELECT demo.user_id, r.id
FROM (VALUES
    ('68000000-0000-4000-8000-000000000001'::uuid, 'ADMIN'),
    ('68000000-0000-4000-8000-000000000002'::uuid, 'SELLER'),
    ('68000000-0000-4000-8000-000000000003'::uuid, 'BUYER')
) AS demo(user_id, role_code)
JOIN roles r ON r.code = demo.role_code AND r.deleted_at IS NULL
ON CONFLICT (user_id, role_id) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM (VALUES
            ('ADMIN', 'PROFILE.VIEW'), ('ADMIN', 'PROFILE.UPDATE'),
            ('ADMIN', 'USER.LIST'), ('ADMIN', 'CATEGORY.CREATE'),
            ('ADMIN', 'CATEGORY.UPDATE'), ('ADMIN', 'CATEGORY.DELETE'),
            ('SELLER', 'PROFILE.VIEW'), ('SELLER', 'PROFILE.UPDATE'),
            ('SELLER', 'PRODUCT.CREATE'), ('SELLER', 'PRODUCT.LIST'),
            ('SELLER', 'PRODUCT.UPDATE'), ('SELLER', 'PRODUCT.DELETE'),
            ('BUYER', 'PROFILE.VIEW'), ('BUYER', 'PROFILE.UPDATE'),
            ('BUYER', 'CART.VIEW'), ('BUYER', 'CART.UPDATE')
        ) AS required(role_code, permission_code)
        WHERE NOT EXISTS (
            SELECT 1 FROM roles r
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE r.code = required.role_code AND p.code = required.permission_code
        )
    ) THEN
        RAISE EXCEPTION 'Required V3 demo role permissions are missing';
    END IF;
END $$;

-- Reputation/password-change/penalty cards currently have no live FE contract.
-- This seed does not fabricate ratings, transactions, or reputation summaries.
COMMIT;
