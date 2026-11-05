DROP TABLE user_roles, role_permissions, roles, permissions, refresh_tokens, password_reset_tokens;

ALTER TABLE users
    DROP COLUMN password_hash,
    DROP COLUMN failed_login_count,
    DROP COLUMN locked_until,
    DROP COLUMN last_login_at;
