--liquibase formatted sql

-- User-to-user messages. A row one user writes and another queries — no Kafka,
-- no outbox, no event (docs/users/messaging.md D1). Keyed on username strings
-- like every other table in this schema, not on user_profile.id, so a message
-- from a since-deleted sender still renders instead of breaking a join.
--changeset moldo:007-create-user-message
CREATE TABLE user_message (
    id                 BIGSERIAL    PRIMARY KEY,
    sender_username    VARCHAR(64)  NOT NULL,
    recipient_username VARCHAR(64)  NOT NULL,
    subject            VARCHAR(200),              -- optional: NULL, never '' (D4b)
    body               TEXT         NOT NULL,     -- stored raw, escaped at render
    read_at            TIMESTAMPTZ,               -- null = unread; "when" is free
    sender_deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    recipient_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Both list queries are "one participant, newest first", and the unread badge is
-- a count over the inbox index.
CREATE INDEX idx_user_message_inbox ON user_message(recipient_username, created_at DESC);
CREATE INDEX idx_user_message_sent ON user_message(sender_username, created_at DESC);
--rollback DROP TABLE user_message;
