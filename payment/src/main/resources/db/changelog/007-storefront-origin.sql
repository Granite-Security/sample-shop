--liquibase formatted sql

-- Which storefront the shopper came from (docs/bugs/redirects.md §4.1).
--
-- One payment service serves several domains, and at return time the shopper arrives
-- from the provider — so the Origin header on that request is PayPal's, not the
-- storefront's. By then this column is the only record of where to send them back to.
--
-- Nullable: payments opened before this column existed have no origin, and fall back to
-- the configured FRONTEND_ORIGIN, which is exactly what they did before.
--changeset moldo:007-payment-storefront-origin
ALTER TABLE payment ADD COLUMN storefront_origin VARCHAR(255);
--rollback ALTER TABLE payment DROP COLUMN storefront_origin;
