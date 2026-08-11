--liquibase formatted sql

-- The two boxes we offer today, and the one group that needs them.
--
-- Free is a price, not a state: FREE is an ordinary row priced 0.00 with a real
-- cost. A cart of truffles always needs a box — choosing FREE means that box
-- adds nothing to the total, not that no box exists. That is what keeps the
-- fulfilment cost visible to accounting.
--
-- Guarded per code, not "any row exists": a later changelog adding a third
-- option must not make this one skip.

--changeset moldo:016-option-free
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM packaging_option WHERE code = 'FREE'
INSERT INTO packaging_option (code, name, description, price, unit_cost, sort_order) VALUES
('FREE', 'Plain box', 'Our standard box — protects everything in transit, included in the price.', 0.00, 0.40, 0);
--rollback DELETE FROM packaging_option WHERE code = 'FREE';

--changeset moldo:016-option-premium
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM packaging_option WHERE code = 'PREMIUM'
INSERT INTO packaging_option (code, name, description, price, unit_cost, sort_order) VALUES
('PREMIUM', 'Premium gift box', 'Rigid keepsake box with ribbon and tissue — made to be handed over.', 6.00, 2.20, 1);
--rollback DELETE FROM packaging_option WHERE code = 'PREMIUM';

--changeset moldo:016-group-truffle
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM packaging_group WHERE code = 'TRUFFLE'
INSERT INTO packaging_group (code, name, description) VALUES
('TRUFFLE', 'Truffles', 'Loose truffles and pralines. Twelve to a box; they travel together.');
--rollback DELETE FROM packaging_group WHERE code = 'TRUFFLE';

-- Same capacity for both boxes here because both hold twelve. They are two rows
-- rather than one number on the group precisely so a future box that holds
-- twenty-four does not have to change anything else.
--changeset moldo:016-capacity-truffle
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM packaging_group_option go JOIN packaging_group g ON g.id = go.packaging_group_id WHERE g.code = 'TRUFFLE'
INSERT INTO packaging_group_option (packaging_group_id, packaging_option_id, capacity)
SELECT g.id, o.id, 12
FROM packaging_group g, packaging_option o
WHERE g.code = 'TRUFFLE' AND o.code IN ('FREE', 'PREMIUM');
--rollback DELETE FROM packaging_group_option WHERE packaging_group_id = (SELECT id FROM packaging_group WHERE code = 'TRUFFLE');
