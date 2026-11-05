-- Do not rewrite historical rows. This fails visibly if an older importer wrote
-- conflicting event IDs; resolve those records before applying the migration.
CREATE UNIQUE INDEX uk_event_logs_event_id ON event_logs (event_id);
ALTER TABLE event_logs ADD COLUMN raw_envelope text;
ALTER TABLE event_logs ADD COLUMN source_topic varchar(80);

-- A projection of the immutable log, so recording and auditing cannot diverge.
CREATE VIEW audit_logs AS
SELECT id, event_id, operator_id AS actor_id, event_type AS action,
       aggregate_type AS entity_type, aggregate_id AS entity_id,
       trace_id, occurred_at, received_at
FROM event_logs
WHERE event_type IN ('UserUpdated', 'UserDeleted', 'UserRoleAssigned',
                     'OrderRefundRequested', 'OrderRefundCompleted', 'SystemConfigUpdated');
