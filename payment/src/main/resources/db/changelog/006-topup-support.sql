--liquibase formatted sql

-- Step 5 of docs/finance/finance.md: a payment that funds a user's balance rather
-- than an order. Same providers, same intents, same webhooks — only the reason
-- differs, so this widens the existing table rather than adding a parallel one.
--changeset moldo:006-payment-purpose
ALTER TABLE payment
    ADD COLUMN purpose  VARCHAR(16) NOT NULL DEFAULT 'ORDER',   -- ORDER | TOPUP
    ADD COLUMN username VARCHAR(64);                            -- who is topping up
--rollback ALTER TABLE payment DROP COLUMN purpose, DROP COLUMN username;

-- A top-up has no order. order_id therefore has to be nullable, and its unique
-- index has to stop treating "no order" as a value: Postgres allows many NULLs in
-- a plain unique index, but a partial index says the intent out loud — one payment
-- per order, and top-ups exempt.
--changeset moldo:006-order-id-nullable
ALTER TABLE payment ALTER COLUMN order_id DROP NOT NULL;
DROP INDEX IF EXISTS idx_payment_order_id;
CREATE UNIQUE INDEX idx_payment_order_id ON payment(order_id) WHERE order_id IS NOT NULL;
--rollback DROP INDEX IF EXISTS idx_payment_order_id; CREATE UNIQUE INDEX idx_payment_order_id ON payment(order_id); ALTER TABLE payment ALTER COLUMN order_id SET NOT NULL;

--changeset moldo:006-payment-username-index
CREATE INDEX idx_payment_username ON payment(username) WHERE username IS NOT NULL;
--rollback DROP INDEX idx_payment_username;
