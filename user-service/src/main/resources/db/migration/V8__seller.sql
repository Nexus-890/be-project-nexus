CREATE TYPE seller_status AS ENUM ('PENDING', 'ACTIVE', 'REJECTED');

-- #257 tạo bảng hồ sơ/đăng ký; #210 dùng chung các cột đăng ký và xét duyệt.
-- Giữ UUID user/Keycloak hiện có, không sinh seller ID riêng.
CREATE TABLE seller (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE NO ACTION,
    company_name varchar(200) NOT NULL CHECK (length(btrim(company_name)) > 0),
    contact_phone varchar(20) NOT NULL CHECK (length(btrim(contact_phone)) > 0),
    status seller_status NOT NULL DEFAULT 'PENDING',
    application_note text,
    rejection_reason text,
    applied_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by uuid,
    reviewed_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    updated_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    CONSTRAINT seller_review_check CHECK (
        (status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND rejection_reason IS NULL)
        OR (status = 'ACTIVE' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND rejection_reason IS NULL)
        OR (status = 'REJECTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0)
    )
);

CREATE INDEX idx_seller_applications ON seller(status, applied_at, user_id);
