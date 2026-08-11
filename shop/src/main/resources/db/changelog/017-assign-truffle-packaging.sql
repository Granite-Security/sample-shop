--liquibase formatted sql

-- Which products are loose truffles.
--
-- An explicit name list, not LIKE '%Truffle%'. Three of the products with
-- "Truffle" in the name or description are already boxed — Truffle Collection
-- Box, The Signature Gift Box and Espresso Ganache Collection ship in their own
-- packaging — and a pattern match would charge a shopper for a second box
-- around a box. Everything not named here stays NULL, which reads as "needs no
-- packaging".
--
-- Pistachio & Rose Praline is in the list: it is a loose praline, and the group
-- is about what can share a box, not about the word in the name.

--changeset moldo:017-assign-truffle-packaging
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM packaging_group WHERE code = 'TRUFFLE'
UPDATE product SET packaging_group_id = (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'),
                   updated_at = now()
WHERE name IN ('Sea Salt Caramel Truffles',
               'White Chocolate Truffles',
               'Pistachio & Rose Praline');
--rollback UPDATE product SET packaging_group_id = NULL WHERE packaging_group_id = (SELECT id FROM packaging_group WHERE code = 'TRUFFLE');
