--liquibase formatted sql

-- Give sichocolate.com (ui-demo) its own category instead of sharing
-- "Food & Sweets" with the generic shop.
--
-- 005 filed the 8 curated pieces under "Food & Sweets" (id 4), which also
-- holds the 5 generic chocolates seeded by 002. ui-demo could only tell the
-- two apart with a hardcoded allowlist of the 8 names in
-- ui-demo/src/api.ts — so a product an admin created through ui-demo's own
-- back-of-house never appeared anywhere in ui-demo, not even in the admin
-- list (see docs/plans/add-chocolates.md). Membership of the storefront is a
-- property of the data, so it belongs here, not in a client-side Set.
--
-- ui-demo now resolves this category by its exact name and shows everything
-- in it. The name is therefore load-bearing: renaming it empties the
-- storefront back to the editorial fallback catalog. It is matched in
-- ui-demo/src/api.ts (SICHOCOLATE_CATEGORY_NAME).
--
-- The 5 generic chocolates stay in "Food & Sweets" untouched, as
-- docs/plans/add-chocolates.md requires — ui-shop keeps showing them where
-- it always did, and simply gains one more category.

--changeset moldo:011-seed-sichocolate-category
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM category WHERE name = 'SI Chocolate'
INSERT INTO category (name, description) VALUES
('SI Chocolate', 'Hand-finished bars, truffles and pralines from the SI Chocolate boutique');
--rollback DELETE FROM category WHERE name = 'SI Chocolate';

-- Everything in "Food & Sweets" moves to SI Chocolate *except* the 5 generic
-- chocolates seeded by 002 — those stay, so ui-shop's Food & Sweets keeps the
-- products it has always shown, as docs/plans/add-chocolates.md requires.
--
-- Exclusion rather than an allowlist of the 8 names from 005, because the
-- category also accumulated products that admins created through ui-demo's own
-- back of house while the name filter was live (they were saved, then filtered
-- out of every view — that is the bug this changeset closes). Those were
-- created for this storefront and belong in it; naming the 8 explicitly would
-- strand them exactly where they were.
--
-- The trade-off: a genuinely generic food product added to Food & Sweets after
-- 002 also moves. Nothing distinguishes it in the data, and the count of such
-- rows here is zero — the alternative silently loses real boutique products.
--changeset moldo:011-move-choco-products
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM category WHERE name = 'SI Chocolate'
UPDATE product SET category_id = (SELECT id FROM category WHERE name = 'SI Chocolate'), updated_at = now()
WHERE category_id = (SELECT id FROM category WHERE name = 'Food & Sweets')
  AND name NOT IN (
    'Dark Chocolate Bar',
    'Milk Chocolate Bar',
    'Truffle Collection Box',
    'Hazelnut Chocolate',
    'White Chocolate Truffles'
  );
--rollback UPDATE product SET category_id = (SELECT id FROM category WHERE name = 'Food & Sweets') WHERE category_id = (SELECT id FROM category WHERE name = 'SI Chocolate');
