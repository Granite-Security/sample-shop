--liquibase formatted sql

-- Which storefront the order was placed from (docs/bugs/redirects.md §4.1).
--
-- shop is the only service in the order→payment chain that runs inside the shopper's
-- HTTP request, so it is the only one that can know this. payment opens the intent from
-- a Kafka event with no request in flight, and needs the value to send the shopper back
-- to the right domain after a redirect payment.
--
-- Stored as well as published: the event is gone by the time a retry or refund happens.
-- Nullable — orders placed before this column fall back to payment's configured origin.
--changeset moldo:010-order-storefront-origin
ALTER TABLE customer_order ADD COLUMN storefront_origin VARCHAR(255);
--rollback ALTER TABLE customer_order DROP COLUMN storefront_origin;
