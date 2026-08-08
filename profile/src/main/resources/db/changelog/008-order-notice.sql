--liquibase formatted sql

-- Idempotency for the admin order notice. shop.notifications is at-least-once, so
-- without this a redelivery — a consumer restarting before its offset commits, a
-- relay retry — puts a second identical message in admin's inbox. The claim is
-- inserted before the message is written, so a crash between the two loses a
-- notice rather than duplicating one: the same trade notification makes with
-- processed_event, and the right way round for something that is a courtesy.
--
-- Keyed on order_id, not on an event id, because "one notice per order" is the
-- rule being enforced; a re-sent event for the same order is the case to swallow.
--changeset moldo:008-create-processed-order-notice
CREATE TABLE processed_order_notice (
    order_id     BIGINT      PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE processed_order_notice;
