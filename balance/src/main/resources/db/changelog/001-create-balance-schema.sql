--liquibase formatted sql

-- The central bank's accounts. USER accounts belong to people; HOUSE accounts are
-- the counterparty to money entering and leaving, so their negative balance is the
-- money supply (docs/finance/finance.md §2).
--changeset moldo:001-create-account
CREATE TABLE account (
    id            BIGSERIAL   PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,          -- the JWT subject
    kind          VARCHAR(16) NOT NULL DEFAULT 'USER',  -- USER | HOUSE
    balance_minor BIGINT      NOT NULL DEFAULT 0,       -- rappen, never a decimal type
    currency      CHAR(3)     NOT NULL DEFAULT 'CHF',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    -- Deliberately no non-negative CHECK: a user may go negative when CreditPolicy
    -- extends credit, and "was positive before this purchase" is not expressible as
    -- a constraint. The guard is the conditional UPDATE in BalanceService
    -- (docs/finance/finance.md §4.2). When a real limit lands, add
    -- credit_limit_minor and CHECK (balance_minor >= -credit_limit_minor).
);
--rollback DROP TABLE account;

-- Append-only. Never UPDATE, never DELETE (D12) — corrections are compensating
-- entries. The two legs of one movement share a transfer_id and sum to zero, which
-- is what makes "did the books balance?" a single query.
--changeset moldo:001-create-ledger-entry
CREATE TABLE ledger_entry (
    id           BIGSERIAL   PRIMARY KEY,
    transfer_id  UUID        NOT NULL,
    account_id   BIGINT      NOT NULL REFERENCES account(id),
    amount_minor BIGINT      NOT NULL,   -- signed
    currency     CHAR(3)     NOT NULL DEFAULT 'CHF',
    kind         VARCHAR(16) NOT NULL,   -- TOPUP | SPEND | REFUND | TRANSFER | GIFT
    reference    VARCHAR(128),           -- order id, payment id, acting admin
    memo         VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account ON ledger_entry(account_id, created_at DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entry(transfer_id);
--rollback DROP TABLE ledger_entry;

-- D5. The unique key is the mechanism; the stored response is what lets a retry
-- return the original answer instead of an error.
--changeset moldo:001-create-idempotency
CREATE TABLE idempotency (
    key         VARCHAR(128) PRIMARY KEY,
    transfer_id UUID         NOT NULL,
    response    TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE idempotency;

--changeset moldo:001-seed-house-accounts
INSERT INTO account (username, kind) VALUES
    ('house:topup',  'HOUSE'),
    ('house:gift',   'HOUSE'),
    ('house:shop',   'HOUSE'),
    ('house:refund', 'HOUSE');
--rollback DELETE FROM account WHERE kind = 'HOUSE';
