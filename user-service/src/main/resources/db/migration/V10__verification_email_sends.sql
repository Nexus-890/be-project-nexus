CREATE TABLE verification_email_sends (
    email   varchar(255) PRIMARY KEY,
    sent_at timestamptz  NOT NULL
);
