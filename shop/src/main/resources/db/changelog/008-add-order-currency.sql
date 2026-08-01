--liquibase formatted sql

-- The shop currency switched from USD to CHF on 2026-08-01, and until now nothing
-- in shop recorded which currency an order was priced in. Without this column,
-- changing the shop currency again would silently reinterpret every historical
-- order at the new denomination.
--
-- Backfilled 'USD' because that is what every order placed before the cutover was
-- actually charged in (payment.currency defaulted to USD, see payment/001). Orders
-- placed between the cutover and this migration are the unrecoverable window the
-- refactor plan warns about — they are backfilled USD too and may be wrong.

--changeset adrian:008-add-order-currency
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'customer_order' AND column_name = 'currency'
ALTER TABLE customer_order ADD COLUMN currency VARCHAR(8);
UPDATE customer_order SET currency = 'USD' WHERE currency IS NULL;
ALTER TABLE customer_order ALTER COLUMN currency SET NOT NULL;
ALTER TABLE customer_order ALTER COLUMN currency SET DEFAULT 'CHF';
--rollback ALTER TABLE customer_order DROP COLUMN currency;
