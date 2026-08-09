--liquibase formatted sql

-- The three moments a sale can be bucketed by (docs/finance/accounting.md D4).
--
-- Until now customer_order carried only created_at (submission) and updated_at
-- (which moves on every transition, including the ones that walk backwards:
-- REIMBURSED -> RETURNED is legal, see OrderStatus). Neither can bucket a
-- revenue report — created_at counts a sale in the month it was attempted, and
-- updated_at silently moves March's revenue into June the next time anything
-- touches the row.
--
-- delivered_at is the accrual recognition point: control of the goods passes to
-- the customer on delivery, not on payment (IFRS 15.31, accounting.md §2.1).
-- refunded_at is REIMBURSED, not RETURNED — the latter is a request, and only
-- the former means money actually left (D6).
--
-- Backfill: the best available approximation, knowingly wrong where payment
-- landed in a different month from placement, and documented rather than hidden
-- exactly as 008 documented its USD backfill. delivered_at is the weakest of the
-- three: updated_at for a RETURNED order is the return, not the delivery. Every
-- report over pre-migration orders is an estimate for that reason, and the UI
-- says so under the table.

--changeset moldo:013-add-order-money-dates
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'customer_order' AND column_name = 'paid_at'
ALTER TABLE customer_order
    ADD COLUMN paid_at      TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN refunded_at  TIMESTAMPTZ;

UPDATE customer_order SET paid_at = created_at
    WHERE paid_at IS NULL
      AND status IN ('PAID','SHIPPED','DELIVERED','RETURNED','REIMBURSED');
UPDATE customer_order SET delivered_at = updated_at
    WHERE delivered_at IS NULL AND status IN ('DELIVERED','RETURNED','REIMBURSED');
UPDATE customer_order SET refunded_at = updated_at
    WHERE refunded_at IS NULL AND status = 'REIMBURSED';
--rollback ALTER TABLE customer_order DROP COLUMN paid_at, DROP COLUMN delivered_at, DROP COLUMN refunded_at;

-- Partial indexes: the revenue queries filter on "IS NOT NULL AND in range", and
-- the null rows (never paid, never delivered, never refunded) are exactly the
-- ones no bucket ever wants.
--changeset moldo:013-add-order-money-dates-indexes
CREATE INDEX idx_customer_order_paid_at      ON customer_order(paid_at)      WHERE paid_at      IS NOT NULL;
CREATE INDEX idx_customer_order_delivered_at ON customer_order(delivered_at) WHERE delivered_at IS NOT NULL;
CREATE INDEX idx_customer_order_refunded_at  ON customer_order(refunded_at)  WHERE refunded_at  IS NOT NULL;
--rollback DROP INDEX idx_customer_order_paid_at; DROP INDEX idx_customer_order_delivered_at; DROP INDEX idx_customer_order_refunded_at;
