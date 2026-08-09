--liquibase formatted sql

-- The books (docs/finance/accounting.md §4).
--
-- Five tables, and the shape of them is the argument for this being a service at
-- all. A journal entry is a fact with a posting date: written once, never amended,
-- corrected only by another entry. A query re-derives history on every page load,
-- so March's revenue changes in June and nothing records that it changed or why.

-- The chart of accounts (§4.3). Deliberately tiny — every line traces to something
-- in §2 — and a fixed list rather than a table anyone can add to: there is no
-- chart-of-accounts editor, because a new account is an accounting-policy decision
-- and belongs in a reviewed migration, not in a form.
--changeset moldo:001-create-account
CREATE TABLE account (
    code        VARCHAR(8)  PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    type        VARCHAR(16) NOT NULL,   -- ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE
    normal_side CHAR(2)     NOT NULL,   -- DR | CR
    -- A contra account sits against another and carries the opposite normal side:
    -- 4100 contra-revenue is a debit against 4000. Flagged so a report can present
    -- it as a deduction without a hard-coded list of codes.
    contra      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_type_known CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    CONSTRAINT account_side_known CHECK (normal_side IN ('DR','CR'))
);
--rollback DROP TABLE account;

--changeset moldo:001-seed-chart-of-accounts
INSERT INTO account (code, name, type, normal_side, contra) VALUES
    ('1000', 'Bank & processor cash',                'ASSET',     'DR', FALSE),
    ('1100', 'Trade receivables',                    'ASSET',     'DR', FALSE),
    ('1150', 'Allowance for expected credit losses', 'ASSET',     'CR', TRUE),
    ('1200', 'Inventory',                            'ASSET',     'DR', FALSE),
    ('1500', 'Equipment',                            'ASSET',     'DR', FALSE),
    ('1550', 'Accumulated depreciation',             'ASSET',     'CR', TRUE),
    ('2000', 'Contract liability — stored value',    'LIABILITY', 'CR', FALSE),
    ('2010', 'Contract liability — deferred revenue','LIABILITY', 'CR', FALSE),
    ('2100', 'Refund liability',                     'LIABILITY', 'CR', FALSE),
    ('2500', 'Accounts payable',                     'LIABILITY', 'CR', FALSE),
    ('2600', 'Due to staff',                         'LIABILITY', 'CR', FALSE),
    ('3000', 'Owner''s capital',                     'EQUITY',    'CR', FALSE),
    ('3900', 'Retained earnings',                    'EQUITY',    'CR', FALSE),
    ('4000', 'Revenue',                              'REVENUE',   'CR', FALSE),
    ('4100', 'Contra-revenue — gift credit redeemed','REVENUE',   'DR', TRUE),
    ('4200', 'Contra-revenue — expected returns',    'REVENUE',   'DR', TRUE),
    ('5000', 'Cost of goods sold',                   'EXPENSE',   'DR', FALSE),
    ('6100', 'Processor fees',                       'EXPENSE',   'DR', FALSE),
    ('6200', 'Inventory adjustments',                'EXPENSE',   'DR', FALSE),
    ('6300', 'Shipping expense',                     'EXPENSE',   'DR', FALSE),
    ('6400', 'Depreciation',                         'EXPENSE',   'DR', FALSE),
    ('6500', 'Impairment loss on receivables',       'EXPENSE',   'DR', FALSE),
    ('6900', 'Other operating expenses',             'EXPENSE',   'DR', FALSE);
--rollback DELETE FROM account;

-- A period can be closed, which is the thing a computed-on-demand report can never
-- do (§1.1). Closing means frozen: a fact that arrives afterwards posts to the open
-- period as a prior-period adjustment rather than quietly changing a month someone
-- has already read.
--changeset moldo:001-create-period
CREATE TABLE period (
    code      CHAR(7)     PRIMARY KEY,   -- 'YYYY-MM'
    starts_on DATE        NOT NULL,
    ends_on   DATE        NOT NULL,      -- exclusive
    status    VARCHAR(8)  NOT NULL DEFAULT 'OPEN',
    closed_at TIMESTAMPTZ,
    closed_by VARCHAR(64),
    CONSTRAINT period_status_known CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT period_range_ordered CHECK (ends_on > starts_on),
    CONSTRAINT period_closed_has_stamp CHECK (status = 'OPEN' OR closed_at IS NOT NULL)
);
--rollback DROP TABLE period;

-- One journal, one event or decision. occurred_at is the business date the entry
-- belongs to; posted_at is when we booked it. They differ whenever a fact arrives
-- late, and keeping both is what makes a prior-period adjustment visible as one
-- rather than looking like a month that changed by itself.
--changeset moldo:001-create-journal
CREATE TABLE journal (
    id           UUID         PRIMARY KEY,
    period_code  CHAR(7)      NOT NULL REFERENCES period(code),
    occurred_at  TIMESTAMPTZ  NOT NULL,   -- business date (§6)
    posted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source       VARCHAR(16)  NOT NULL,   -- EVENT | SCHEDULE | MANUAL | OPENING
    event_type   VARCHAR(64),
    reference    VARCHAR(128),            -- order id, transfer id, payment id
    memo         VARCHAR(200),
    -- From the JWT, never from a request body (§15.2). Null for machine postings.
    created_by   VARCHAR(64),
    -- Corrections are reversals that point at what they reverse. There is no
    -- endpoint that edits a posted journal, for anyone (D34).
    reverses_id  UUID         REFERENCES journal(id),
    -- The fact was dated inside a closed period and was booked into the open one.
    prior_period BOOLEAN      NOT NULL DEFAULT FALSE,
    -- An estimate, not a measurement: the ECL allowance and the return provision.
    -- assumptions carries the rate set and its asOf, so a derived figure can never
    -- be rendered as though it were measured (D21).
    estimated    BOOLEAN      NOT NULL DEFAULT FALSE,
    assumptions  TEXT,
    CONSTRAINT journal_source_known CHECK (source IN ('EVENT','SCHEDULE','MANUAL','OPENING')),
    CONSTRAINT journal_estimate_states_assumptions CHECK (NOT estimated OR assumptions IS NOT NULL)
);
CREATE INDEX idx_journal_period ON journal (period_code);
CREATE INDEX idx_journal_occurred_at ON journal (occurred_at);
CREATE INDEX idx_journal_reference ON journal (reference) WHERE reference IS NOT NULL;
--rollback DROP TABLE journal;

-- Exactly one side per line. A line carrying both a debit and a credit is not a
-- shorthand, it is two lines someone did not write, and it makes every sum over
-- this table ambiguous.
--changeset moldo:001-create-journal-line
CREATE TABLE journal_line (
    id           BIGSERIAL   PRIMARY KEY,
    journal_id   UUID        NOT NULL REFERENCES journal(id),
    account_code VARCHAR(8)  NOT NULL REFERENCES account(code),
    debit_minor  BIGINT      NOT NULL DEFAULT 0,
    credit_minor BIGINT      NOT NULL DEFAULT 0,
    -- Who we owe, on a 2600 line (D35). A column rather than an account per person,
    -- so "what do we owe Ana?" is a GROUP BY and not a migration every time someone
    -- joins.
    party        VARCHAR(64),
    memo         VARCHAR(200),
    CONSTRAINT journal_line_nonneg CHECK (debit_minor >= 0 AND credit_minor >= 0),
    CONSTRAINT journal_line_one_side CHECK ((debit_minor = 0) <> (credit_minor = 0))
);
CREATE INDEX idx_journal_line_journal ON journal_line (journal_id);
CREATE INDEX idx_journal_line_account ON journal_line (account_code);
--rollback DROP TABLE journal_line;

-- Debits = credits, enforced by the database rather than by whoever remembers to
-- check. DEFERRABLE INITIALLY DEFERRED so the lines of one journal can be inserted
-- one at a time and the assertion runs at commit — an immediate trigger would fail
-- on the first line of every entry ever written.
--
-- splitStatements:false is required, not stylistic: Liquibase splits formatted SQL
-- on ';' and would cut the function body into fragments that do not parse.
--changeset moldo:001-journal-balanced-trigger splitStatements:false
CREATE FUNCTION accounting_assert_balanced() RETURNS TRIGGER AS $$
DECLARE
    v_debits  BIGINT;
    v_credits BIGINT;
BEGIN
    SELECT COALESCE(SUM(debit_minor), 0), COALESCE(SUM(credit_minor), 0)
      INTO v_debits, v_credits
      FROM journal_line WHERE journal_id = NEW.journal_id;

    IF v_debits <> v_credits THEN
        RAISE EXCEPTION 'journal % does not balance: debits % <> credits %',
            NEW.journal_id, v_debits, v_credits;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION accounting_assert_balanced();

--changeset moldo:001-journal-balanced-trigger-attach
CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION accounting_assert_balanced();
--rollback DROP TRIGGER journal_line_balanced ON journal_line;

-- Append-only, enforced the same way. "Corrections are made by reversal" is a rule
-- that holds exactly as long as nobody reaches for an UPDATE at 2am with a
-- convincing reason; a posted journal that can be edited is not a record of
-- anything. The endpoint does not exist and now neither does the statement.
--changeset moldo:001-journal-append-only splitStatements:false
CREATE FUNCTION accounting_refuse_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'journal rows are append-only (%): correct by posting a reversing journal',
        TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION accounting_refuse_mutation();

--changeset moldo:001-journal-append-only-attach
CREATE TRIGGER journal_append_only BEFORE UPDATE OR DELETE ON journal
    FOR EACH ROW EXECUTE FUNCTION accounting_refuse_mutation();
CREATE TRIGGER journal_line_append_only BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION accounting_refuse_mutation();
--rollback DROP TRIGGER journal_append_only ON journal; DROP TRIGGER journal_line_append_only ON journal_line;

-- Idempotency, copied from notification's: the row is inserted BEFORE the journal
-- is posted, so a crash mid-post leaves the event marked and the fact unposted
-- rather than posting it twice on redelivery.
--
-- Explicitly WITHOUT notification's staleness rule (D23). notification drops events
-- past a per-type age as DROPPED_STALE, which is right for email — nobody wants a
-- week-old password reset. Accounting is the exact opposite: a late fact is still a
-- fact and must be booked, into the open period if its own is closed. The pattern
-- will be copied from notification, so this comment exists to stop the staleness
-- check being copied with it.
--changeset moldo:001-create-processed-event
CREATE TABLE processed_event (
    event_key    VARCHAR(200) PRIMARY KEY,   -- topic + the producer's own id
    topic        VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE processed_event;

-- Store the fact, then derive the journal (§6).
--
-- Four topics means out-of-order delivery is guaranteed, not unlikely: Kafka orders
-- within a partition and these are four of them, so a PaymentSucceeded can arrive
-- before its OrderPlaced. A fact whose prerequisites have not landed stays UNPOSTED
-- and is retried, instead of crashing the consumer or posting half a movement.
--changeset moldo:001-create-fact
CREATE TABLE fact (
    id           BIGSERIAL    PRIMARY KEY,
    topic        VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    aggregate_id VARCHAR(64),
    occurred_at  TIMESTAMPTZ  NOT NULL,   -- the producer's business date
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    payload      TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'UNPOSTED',
    journal_id   UUID         REFERENCES journal(id),
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT,
    CONSTRAINT fact_status_known CHECK (status IN ('UNPOSTED','POSTED','IGNORED')),
    CONSTRAINT fact_posted_has_journal CHECK (status <> 'POSTED' OR journal_id IS NOT NULL)
);
CREATE UNIQUE INDEX idx_fact_event_key ON fact (event_key);
CREATE INDEX idx_fact_unposted ON fact (occurred_at) WHERE status = 'UNPOSTED';
--rollback DROP TABLE fact;
