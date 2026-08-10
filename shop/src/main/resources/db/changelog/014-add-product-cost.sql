--liquibase formatted sql

-- What a product costs us (docs/finance/accounting.md §14.1, D28).
--
-- product carried price and stock and no cost, so cost of goods sold was not
-- merely unrecorded, it was unmeasurable: nothing anywhere knew what a sale
-- consumed. Every gross-margin figure before this column is a guess.
--
-- 50% of price is a stated assumption, not a measurement — a 50% gross margin
-- before processor fees and shipping. It is config-adjacent by design: revising
-- it is an UPDATE, not a migration, because it will be argued about.
--
-- Costing method is weighted average (D28): one column, updated on receipt, no
-- lot tracking and no FIFO layers. Standard cost and FIFO are both defensible
-- and both need a table of layers; for a catalogue this size, average cost is
-- the honest simplification.

--changeset moldo:014-add-product-cost
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'product' AND column_name = 'unit_cost'
ALTER TABLE product ADD COLUMN unit_cost NUMERIC(10,2);

-- The 50% default cannot be a column DEFAULT: a default expression cannot
-- reference another column of the same row. So existing rows are backfilled here
-- and new products get the rule in CatalogService.createProduct. Deliberately not
-- a trigger — a trigger would hide a pricing rule from the service that appears
-- to own it, and this rule will be argued about by people reading that service.
UPDATE product SET unit_cost = ROUND(price * 0.5, 2) WHERE unit_cost IS NULL;

ALTER TABLE product ALTER COLUMN unit_cost SET NOT NULL;
ALTER TABLE product ADD CONSTRAINT product_unit_cost_nonneg CHECK (unit_cost >= 0);
--rollback ALTER TABLE product DROP COLUMN unit_cost;
