--liquibase formatted sql

--changeset adrian:001-create-processed-event
-- Consumer dedupe. Kafka delivery is at-least-once, so a rebalance or a
-- redelivered offset will re-present a message; the row is inserted BEFORE the
-- send, and a duplicate-key violation means "already handled, skip". Sending a
-- password-reset email twice is user-visible, hence this table exists from day
-- one rather than being added when duplicates are first noticed.
CREATE TABLE processed_event (
    event_id     UUID         PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_processed_event_processed_at ON processed_event(processed_at);
--rollback DROP TABLE processed_event;

--changeset adrian:001-create-notification-log
-- Delivery audit: what was sent, to whom, over which channel, and what the
-- provider said. This is what makes "did the user actually get the email?"
-- answerable. status is one of SENT, FAILED, SKIPPED_DISABLED, DROPPED_STALE.
CREATE TABLE notification_log (
    id                  BIGSERIAL    PRIMARY KEY,
    event_id            UUID,
    event_type          VARCHAR(64)  NOT NULL,
    channel             VARCHAR(32)  NOT NULL,
    recipient           VARCHAR(255),
    status              VARCHAR(32)  NOT NULL,
    provider_message_id VARCHAR(255),
    error               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_log_event_id ON notification_log(event_id);
CREATE INDEX idx_notification_log_created_at ON notification_log(created_at);
--rollback DROP TABLE notification_log;
