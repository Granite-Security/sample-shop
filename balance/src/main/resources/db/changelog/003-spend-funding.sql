--liquibase formatted sql

-- Which spend was gift money (docs/finance/accounting.md §5).
--
-- The ledger records that a user spent CHF 60. Nothing in it says whether that was
-- the francs an admin conjured for them or the francs they paid for, because there
-- is no such fact — money is fungible and the answer does not exist until a rule is
-- chosen and its result recorded. The rule is gift-first drawdown (D12): every debit
-- draws from the account's gift pool before it touches backed money.
--
-- This is not dashboard colour. Gifted credit is consideration payable to a customer
-- (IFRS 15.70) and reduces the transaction price, so this split is the input to the
-- contra-revenue line. Without it the accrual books overstate revenue by exactly the
-- amount of gifted credit redeemed.
--
-- Gift-first is the conservative direction: it maximises contra-revenue and minimises
-- recognised revenue. It is also the only ordering that needs no per-franc history.

--changeset moldo:003-account-gift-pool
ALTER TABLE account
    ADD COLUMN gift_pool_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN negative_since  TIMESTAMPTZ;
--rollback ALTER TABLE account DROP COLUMN gift_pool_minor, DROP COLUMN negative_since;

-- negative_since is the age of a receivable, which IFRS 9's provision matrix buckets
-- by (accounting.md §2.6) and which nothing currently records. Deriving it by
-- replaying the ledger is cheap today and wrong to build on, so it is a column.
--
-- Only USER accounts carry either. A house account's negative balance IS the money
-- supply, not a receivable, and it has no pool: house:gift is the source of conjured
-- money, not a holder of it.
--changeset moldo:003-account-gift-pool-constraints
ALTER TABLE account ADD CONSTRAINT account_gift_pool_nonneg CHECK (gift_pool_minor >= 0);
ALTER TABLE account ADD CONSTRAINT account_house_has_no_pool
    CHECK (kind <> 'HOUSE' OR (gift_pool_minor = 0 AND negative_since IS NULL));
--rollback ALTER TABLE account DROP CONSTRAINT account_gift_pool_nonneg, DROP CONSTRAINT account_house_has_no_pool;

-- The split, written on the ledger entry itself so it is auditable from the books
-- rather than recomputed from a running estimate.
--
-- gift_funded_minor is the conjured portion of THIS leg, in both directions: drawn
-- out on a debit, restored or carried in on the credit leg of a GIFT, TRANSFER or
-- REFUND. Recording both directions is what makes the pool provable from the ledger
-- alone (§12.1) — a refund that puts gifted money back would otherwise look, to any
-- check, like conjured money that had been spent twice.
--
-- credit_funded_minor is money we lent rather than money the user had, and exists
-- only on debits. backed_funded is deliberately NOT stored: it is
-- amount - gift - credit, and a third stored column is a third thing that can
-- disagree with the other two.
--changeset moldo:003-ledger-entry-funding
ALTER TABLE ledger_entry
    ADD COLUMN gift_funded_minor   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN credit_funded_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_funding_nonneg
    CHECK (gift_funded_minor >= 0 AND credit_funded_minor >= 0);
-- A split can never exceed the movement it splits (invariant 1, §12.1). Enforced
-- here as well as checked by /reconcile, because a violation is not an anomaly to
-- report — it is a write that must not be allowed to land.
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_funding_within_amount
    CHECK (gift_funded_minor + credit_funded_minor <= abs(amount_minor));
--rollback ALTER TABLE ledger_entry DROP COLUMN gift_funded_minor, DROP COLUMN credit_funded_minor;

-- Backfill by replaying the ledger in id order through the rule above (§5.4).
--
-- In SQL rather than application code for two reasons: a fresh database and an
-- existing one must converge on the same numbers, and balance must contain no
-- .block() — a reactive replay loop is exactly the code that ends up with one
-- (finance.md §7.2).
--
-- The replay is deterministic because gift-first needs no history beyond the running
-- pool: id order is the order the movements happened in, and both legs of a movement
-- are written debit-first, so a transfer's recipient is credited from a split that
-- has already been computed.
--
-- If this ever becomes too slow to run in a migration, that is the signal from §12.2,
-- and the honest answer is to zero the historical splits and report "from <date>" —
-- not to guess.
-- splitStatements:false is required, not stylistic: Liquibase splits formatted SQL on
-- ';' and would cut this PL/pgSQL block into fragments that do not parse.
--changeset moldo:003-backfill-funding-split splitStatements:false
DO $$
DECLARE
    e            RECORD;
    v_amt        BIGINT;
    v_bal        BIGINT;
    v_pool       BIGINT;
    v_gift       BIGINT;
    v_credit     BIGINT;
BEGIN
    CREATE TEMP TABLE replay_state (
        account_id      BIGINT PRIMARY KEY,
        balance_minor   BIGINT NOT NULL DEFAULT 0,
        gift_pool_minor BIGINT NOT NULL DEFAULT 0,
        negative_since  TIMESTAMPTZ
    ) ON COMMIT DROP;

    INSERT INTO replay_state (account_id)
        SELECT id FROM account WHERE kind = 'USER';

    FOR e IN
        SELECT le.id, le.account_id, le.amount_minor, le.kind, le.reference,
               le.transfer_id, le.created_at
          FROM ledger_entry le
          JOIN account a ON a.id = le.account_id
         WHERE a.kind = 'USER'          -- house accounts have no pool and no receivable age
         ORDER BY le.id
    LOOP
        SELECT balance_minor, gift_pool_minor INTO v_bal, v_pool
          FROM replay_state WHERE account_id = e.account_id;

        IF e.amount_minor < 0 THEN
            v_amt    := -e.amount_minor;
            v_gift   := LEAST(v_pool, v_amt);                        -- conjured money, spent
            v_credit := GREATEST(0, v_amt - GREATEST(v_bal, 0));     -- money we lent, not money we had

            UPDATE ledger_entry
               SET gift_funded_minor = v_gift, credit_funded_minor = v_credit
             WHERE id = e.id;

            UPDATE replay_state
               SET balance_minor   = v_bal - v_amt,
                   gift_pool_minor = v_pool - v_gift,
                   negative_since  = CASE
                       WHEN v_bal - v_amt >= 0 THEN NULL
                       WHEN negative_since IS NULL THEN e.created_at
                       ELSE negative_since END
             WHERE account_id = e.account_id;
        ELSE
            v_amt := e.amount_minor;
            -- How much of this credit is conjured money. A TRANSFER carries the
            -- sender's drawdown, or one user-to-user hop would launder every gifted
            -- franc into apparently-backed money. A REFUND returns gift to the pool
            -- it came from, read off the original SPEND by its order reference.
            v_gift := CASE e.kind
                WHEN 'GIFT' THEN v_amt
                WHEN 'TRANSFER' THEN COALESCE((
                        SELECT gift_funded_minor FROM ledger_entry
                         WHERE transfer_id = e.transfer_id AND amount_minor < 0), 0)
                WHEN 'REFUND' THEN LEAST(v_amt, COALESCE((
                        SELECT SUM(gift_funded_minor) FROM ledger_entry
                         WHERE kind = 'SPEND' AND amount_minor < 0
                           AND account_id = e.account_id
                           AND reference IS NOT NULL AND reference = e.reference), 0))
                ELSE 0 END;

            UPDATE ledger_entry SET gift_funded_minor = v_gift WHERE id = e.id;

            UPDATE replay_state
               SET balance_minor   = v_bal + v_amt,
                   gift_pool_minor = v_pool + v_gift,
                   negative_since  = CASE WHEN v_bal + v_amt >= 0 THEN NULL ELSE negative_since END
             WHERE account_id = e.account_id;
        END IF;
    END LOOP;

    UPDATE account a
       SET gift_pool_minor = s.gift_pool_minor,
           negative_since  = s.negative_since
      FROM replay_state s
     WHERE a.id = s.account_id;
END $$;
--rollback UPDATE ledger_entry SET gift_funded_minor = 0, credit_funded_minor = 0; UPDATE account SET gift_pool_minor = 0, negative_since = NULL;

-- The reports group spend by bucket and filter on the funded columns; the ECL
-- provision matrix buckets receivables by the age of negative_since.
--changeset moldo:003-funding-indexes
CREATE INDEX idx_ledger_entry_spend_funding ON ledger_entry (kind, created_at)
    WHERE amount_minor < 0;
CREATE INDEX idx_account_negative_since ON account (negative_since)
    WHERE negative_since IS NOT NULL;
--rollback DROP INDEX idx_ledger_entry_spend_funding; DROP INDEX idx_account_negative_since;
