--liquibase formatted sql

-- Which topic a row is destined for. Until now OutboxRelay hardcoded
-- "orders.events", so the outbox could carry exactly one stream; the admin order
-- notice needs a second one that payment and delivery are not subscribed to.
--
-- The default is what every existing row was implicitly: backfilling is the
-- DEFAULT clause doing its job, so no UPDATE is needed and rows written by the
-- previous build keep publishing where they always did.
--changeset moldo:009-add-outbox-topic
ALTER TABLE outbox ADD COLUMN topic VARCHAR(128) NOT NULL DEFAULT 'orders.events';
--rollback ALTER TABLE outbox DROP COLUMN topic;
