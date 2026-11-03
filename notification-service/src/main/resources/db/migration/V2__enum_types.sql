CREATE TYPE notification_channel AS ENUM ('EMAIL', 'PUSH', 'SMS');
CREATE TYPE notification_delivery_status AS ENUM ('PENDING', 'SENT', 'FAILED', 'SKIPPED');
CREATE TYPE notification_attempt_status AS ENUM ('STARTED', 'SUCCEEDED', 'FAILED');

ALTER TABLE notification_templates DROP CONSTRAINT notification_templates_channel_check;
ALTER TABLE notification_templates
    ALTER COLUMN channel TYPE notification_channel USING channel::notification_channel;
ALTER TABLE notification_preferences DROP CONSTRAINT notification_preferences_channel_check;
ALTER TABLE notification_preferences
    ALTER COLUMN channel TYPE notification_channel USING channel::notification_channel;
ALTER TABLE notification_deliveries
    DROP CONSTRAINT notification_deliveries_channel_check,
    DROP CONSTRAINT notification_deliveries_status_check,
    ALTER COLUMN status DROP DEFAULT;
ALTER TABLE notification_deliveries
    ALTER COLUMN channel TYPE notification_channel USING channel::notification_channel,
    ALTER COLUMN status TYPE notification_delivery_status USING status::notification_delivery_status;
ALTER TABLE notification_deliveries ALTER COLUMN status SET DEFAULT 'PENDING'::notification_delivery_status;
ALTER TABLE notification_delivery_attempts DROP CONSTRAINT notification_delivery_attempts_status_check;
ALTER TABLE notification_delivery_attempts
    ALTER COLUMN status TYPE notification_attempt_status USING status::notification_attempt_status;
