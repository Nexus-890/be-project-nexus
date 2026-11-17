CREATE TABLE user_addresses (
    id             uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL REFERENCES users (id),
    recipient_name varchar(150) NOT NULL,
    phone          varchar(20)  NOT NULL,
    address_line   varchar(255) NOT NULL,
    province_code  varchar(9)   NOT NULL,
    province_name  varchar(100) NOT NULL,
    ward_code      varchar(9)   NOT NULL,
    ward_name      varchar(100) NOT NULL,
    is_default     boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    created_by     uuid         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    updated_by     uuid         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    version        bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_user_addresses_user ON user_addresses (user_id, created_at);
CREATE UNIQUE INDEX uq_user_addresses_one_default ON user_addresses (user_id) WHERE is_default;
