--liquibase formatted sql

-- Percentage discount codes (docs/finance/vouchers.md).
--
-- A voucher is deliberately NOT money. It is never issued, held or transferred,
-- and it must never reach `balance` (V1) — routing it there would mean minting
-- CHF at redemption only to destroy it, which breaks the single-writer mandate
-- and puts discounts nobody was entitled to hold into the money supply.
--
-- What lands on customer_order is a snapshot, not a foreign key to the voucher's
-- current state (V5): editing or revoking a voucher must not reach back into an
-- order that was already placed, exactly as repricing a box must not (015).

--changeset moldo:020-add-voucher
CREATE TABLE voucher (
    id           BIGSERIAL PRIMARY KEY,
    -- Upper-case and trimmed on the way in (V12), so SPRING25 and spring25 are one
    -- voucher and two vouchers differing only by case cannot exist.
    code         TEXT        NOT NULL UNIQUE,
    percent_off  SMALLINT    NOT NULL CHECK (percent_off BETWEEN 1 AND 100),
    valid_from   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Mandatory (V10). A voucher without an expiry is a permanent price cut
    -- wearing a code, and nothing in this system would ever retire it.
    valid_until  TIMESTAMPTZ NOT NULL,
    -- Revoked, never deleted (V13): placed orders reference this row by code, and
    -- the admin list has to keep showing what was withdrawn and when.
    revoked_at   TIMESTAMPTZ,
    description  TEXT,
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT voucher_window_valid CHECK (valid_until > valid_from)
);
--rollback DROP TABLE voucher;

--changeset moldo:020-add-voucher-redemption
-- The primary key IS the once-per-user rule (V8). A SELECT-then-INSERT in Java
-- loses the race between two checkouts submitted together; a unique violation
-- cannot, and it costs one constraint instead of a lock.
--
-- order_id records which order consumed it, so an admin looking at a redemption
-- can find the sale. A refund does not delete this row (V9) — the code was used.
CREATE TABLE voucher_redemption (
    voucher_id  BIGINT      NOT NULL REFERENCES voucher(id),
    username    TEXT        NOT NULL,
    order_id    BIGINT      NOT NULL REFERENCES customer_order(id),
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (voucher_id, username)
);
CREATE INDEX idx_voucher_redemption_order ON voucher_redemption(order_id);
--rollback DROP TABLE voucher_redemption;

--changeset moldo:020-add-order-discount
-- discount_total is the stored truth and the percentage is a label: the rounding
-- happens once, here, at placement, and every consumer subtracts the stored
-- amount rather than recomputing items x percent. That is what lets the
-- reconciliation in vouchers.md §12.1 be an exact equality instead of a tolerance.
--
-- Defaulting to 0 means every pre-existing order reads as an undiscounted one,
-- which is what it is. No backfill and no estimate, unlike 008 and 013.
ALTER TABLE customer_order
    ADD COLUMN voucher_code     TEXT,
    ADD COLUMN discount_percent SMALLINT,
    ADD COLUMN discount_total   NUMERIC(19,2) NOT NULL DEFAULT 0;
--rollback ALTER TABLE customer_order DROP COLUMN voucher_code, DROP COLUMN discount_percent, DROP COLUMN discount_total;
