--liquibase formatted sql

-- Packaging (docs/packaging/packaging.md).
--
-- Some things we sell arrive already packaged — a gift box is its own box —
-- and some do not: loose truffles need something to go in before they can be
-- shipped. Nothing in the schema could tell those two apart, so checkout could
-- not price a box and fulfilment could not know one was needed.
--
-- "Requires packaging" is product.packaging_group_id IS NOT NULL. One nullable
-- column rather than a boolean plus a compatibility column, because those two
-- can disagree: a product flagged as needing packaging with no group is an
-- order nobody can pack.
--
-- The group is also the compatibility axis: truffles share a box with other
-- truffles, never with a chocolate rabbit. Deliberately not category —
-- category already decides storefront membership by name (see 011), and
-- overloading it would tie what shares a box to what appears on which domain.
--
-- Capacity lives on the (group, option) pair, not on the product and not on
-- the option: a box holds 12 truffles or 4 rabbits, and that is a fact about
-- the pairing, not about either side alone.

--changeset moldo:015-packaging-group
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'packaging_group'
CREATE TABLE packaging_group (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE packaging_group;

-- unit_cost is stored, not derived from price, and the free option is exactly
-- why: it charges 0.00 and still costs us 0.40. A box we give away is a
-- fulfilment cost that has to be expensed even though no revenue line names it
-- (docs/finance/accounting.md D44).
--
-- Options are retired with active = false, never DELETE: order_packaging
-- points at them, and an order's history must keep resolving after the box it
-- shipped in stops being offered.
--changeset moldo:015-packaging-option
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'packaging_option'
CREATE TABLE packaging_option (
    id          BIGSERIAL     PRIMARY KEY,
    code        VARCHAR(64)   NOT NULL UNIQUE,
    name        VARCHAR(255)  NOT NULL,
    description TEXT,
    price       NUMERIC(10,2) NOT NULL,
    unit_cost   NUMERIC(10,2) NOT NULL,
    image_url   VARCHAR(512),
    active      BOOLEAN       NOT NULL DEFAULT true,
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT packaging_option_price_nonneg CHECK (price >= 0),
    CONSTRAINT packaging_option_cost_nonneg  CHECK (unit_cost >= 0)
);
--rollback DROP TABLE packaging_option;

--changeset moldo:015-packaging-group-option
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'packaging_group_option'
CREATE TABLE packaging_group_option (
    packaging_group_id  BIGINT  NOT NULL REFERENCES packaging_group(id),
    packaging_option_id BIGINT  NOT NULL REFERENCES packaging_option(id),
    capacity            INTEGER NOT NULL,
    PRIMARY KEY (packaging_group_id, packaging_option_id),
    CONSTRAINT packaging_capacity_positive CHECK (capacity > 0)
);
--rollback DROP TABLE packaging_group_option;

--changeset moldo:015-product-packaging-group
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'product' AND column_name = 'packaging_group_id'
ALTER TABLE product ADD COLUMN packaging_group_id BIGINT REFERENCES packaging_group(id);
CREATE INDEX idx_product_packaging_group_id ON product(packaging_group_id);
--rollback ALTER TABLE product DROP COLUMN packaging_group_id;

-- Split out of total rather than folded into it silently: total is what the
-- shopper is charged and what payment collects, and packaging_total is how
-- much of that was boxes. Without the split, a reconciliation of line items
-- against the order total never balances once a premium box is chosen.
--changeset moldo:015-order-packaging-total
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'customer_order' AND column_name = 'packaging_total'
ALTER TABLE customer_order ADD COLUMN packaging_total NUMERIC(10,2) NOT NULL DEFAULT 0;
--rollback ALTER TABLE customer_order DROP COLUMN packaging_total;

-- unit_price and unit_cost are frozen copies taken at placement (D26), for the
-- same reason order_item freezes the product price: repricing a box must not
-- reach back and change what an order that already shipped cost or charged.
--
-- One row per (order, group). Which truffle went in which box is not recorded
-- — nothing downstream asks, and storing an assignment we never verify would
-- be a fiction in the database.
--changeset moldo:015-order-packaging
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'order_packaging'
CREATE TABLE order_packaging (
    id                  BIGSERIAL     PRIMARY KEY,
    order_id            BIGINT        NOT NULL REFERENCES customer_order(id),
    packaging_group_id  BIGINT        NOT NULL REFERENCES packaging_group(id),
    packaging_option_id BIGINT        NOT NULL REFERENCES packaging_option(id),
    quantity            INTEGER       NOT NULL,
    unit_price          NUMERIC(10,2) NOT NULL,
    unit_cost           NUMERIC(10,2) NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT order_packaging_qty_positive CHECK (quantity > 0),
    CONSTRAINT order_packaging_one_per_group UNIQUE (order_id, packaging_group_id)
);
CREATE INDEX idx_order_packaging_order_id ON order_packaging(order_id);
--rollback DROP TABLE order_packaging;
