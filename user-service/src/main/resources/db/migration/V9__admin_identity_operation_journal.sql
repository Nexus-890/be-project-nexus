-- Không chứa password, credential hoặc FK; intent phải commit được khi transaction profile đang giữ khóa.
CREATE TABLE admin_identity_operations (
    operation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    event_type varchar(80) NOT NULL CHECK (event_type IN ('UserPasswordChanged', 'UserUnlocked')),
    profile_version bigint NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('PENDING', 'REMOTE_SUCCEEDED', 'COMPLETED', 'REVIEW_REQUIRED')),
    created_at timestamptz NOT NULL,
    remote_confirmed_at timestamptz,
    completed_at timestamptz,
    CHECK (state = 'PENDING' OR remote_confirmed_at IS NOT NULL),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX idx_admin_identity_operations_pending ON admin_identity_operations (created_at)
    WHERE state IN ('PENDING', 'REMOTE_SUCCEEDED', 'REVIEW_REQUIRED');
