-- Bổ sung hành động quản trị user; giữ nguyên mọi audit action đã được ghi trước đây.
CREATE OR REPLACE VIEW audit_logs AS
SELECT id, event_id, operator_id AS actor_id, event_type AS action,
       aggregate_type AS entity_type, aggregate_id AS entity_id,
       trace_id, occurred_at, received_at
FROM event_logs
WHERE event_type IN ('UserRegistered', 'UserUpdated', 'UserDeleted', 'UserRoleAssigned',
                     'UserPasswordChanged', 'UserUnlocked',
                     'OrderRefundRequested', 'OrderRefundCompleted', 'SystemConfigUpdated');
