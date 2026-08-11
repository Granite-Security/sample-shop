--liquibase formatted sql

-- The truffles that are actually on sale.
--
-- 017 assigned the group by product name, and took those names from
-- 005-seed-choco-products.sql. The live catalogue does not have them: the SI
-- Chocolate range was created through the admin UI with its own names, so of
-- 017's three only 'White Chocolate Truffles' existed, and it sits in Food &
-- Sweets — a category the sichocolate storefront filters out. Every truffle a
-- shopper could actually buy was therefore unassigned, the quote correctly
-- answered packagingRequired:false, and no box was ever offered.
--
-- 017 is already applied and checksummed, so this corrects it rather than
-- editing it.
--
-- Still an explicit name list, and still not a pattern match — in both
-- directions. 'Truffle Collection Box' (id 12) has "Truffle" in its name and is
-- already a box, so it must not be caught; 'Espresso Ganache Collection' has
-- neither "truffle" nor "praline" in its name and *is* a loose truffle, so it
-- must be. Nothing derivable from the name would have got both right.
--
-- Left out for now, as pieces we are not yet sure of: 'Salted Caramel &
-- Hazelnut Rocher' and the three CHF 20.00 items. Adding one later is an
-- UPDATE, not a migration.

--changeset moldo:018-assign-si-truffles
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM packaging_group WHERE code = 'TRUFFLE'
UPDATE product SET packaging_group_id = (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'),
                   updated_at = now()
WHERE name IN ('Pine Nut Truffle',
               'ROSE & RASPBERRY TRUFFLE',
               'Callebaut Coconut Velvet Truffle',
               'Dubai Style Pistachio & Kataifi Truffle',
               'Espresso Ganache Collection');
--rollback UPDATE product SET packaging_group_id = NULL WHERE name IN ('Pine Nut Truffle', 'ROSE & RASPBERRY TRUFFLE', 'Callebaut Coconut Velvet Truffle', 'Dubai Style Pistachio & Kataifi Truffle', 'Espresso Ganache Collection');
