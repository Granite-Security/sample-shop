--liquibase formatted sql

--changeset adrian:003-refactor-delivery
ALTER TABLE delivery ADD COLUMN payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID';
ALTER TABLE delivery ADD COLUMN items TEXT;
ALTER TABLE delivery DROP COLUMN IF EXISTS provider;
ALTER TABLE delivery DROP COLUMN IF EXISTS tracking_number;
DROP TABLE IF EXISTS shipping_provider;
DROP TABLE IF EXISTS webhook_event;
--rollback ALTER TABLE delivery DROP COLUMN payment_status; ALTER TABLE delivery DROP COLUMN items; ALTER TABLE delivery ADD COLUMN provider VARCHAR(64); ALTER TABLE delivery ADD COLUMN tracking_number VARCHAR(128); CREATE TABLE shipping_provider (...); CREATE TABLE webhook_event (...);
