--liquibase formatted sql

-- 4300, the voucher discount line (docs/finance/vouchers.md §3.2, V2).
--
-- A voucher discount reduces the transaction price at inception (IFRS 15.47) —
-- simpler than gifted credit, which needed IFRS 15.70-72 and an argument about
-- when the reduction lands. Measured revenue is the net amount either way.
--
-- We nevertheless credit 4000 gross and debit the discount here, because netting
-- destroys the discount irrecoverably: no report can reconstruct a number that was
-- never booked, and "did we sell?" and "what did we give away?" are two different
-- management questions (accounting.md §1). It sits beside 4100 so that a reader
-- does not have to know which mechanism produced a discount in order to find it.
--
-- This is a presentation gross-up, not a measurement position: 4000 less 4300 is
-- the IFRS revenue, always.

--changeset moldo:002-add-voucher-contra-account
INSERT INTO account (code, name, type, normal_side, contra) VALUES
    ('4300', 'Contra-revenue — voucher discounts', 'REVENUE', 'DR', TRUE);
--rollback DELETE FROM account WHERE code = '4300';
