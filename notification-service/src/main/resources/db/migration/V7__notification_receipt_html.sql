-- Retain the receipt snapshot for asynchronous email retries without changing in-app content.
ALTER TABLE notifications ADD COLUMN email_html text;
