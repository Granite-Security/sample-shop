--liquibase formatted sql

-- The 8 curated pieces sichocolate.com (ui-demo) actually markets, matching
-- ui-demo/src/api.ts's FALLBACK_PRODUCTS by name/description/price/stock
-- exactly (see docs/plans/add-chocolates.md). Until now these only existed as
-- client-side placeholders with negative ids, which ui-demo's checkout
-- refuses to order — this makes them real, orderable rows.
--
-- Filed under the existing "Food & Sweets" category (id 4, seeded in
-- 002-seed-products.sql) alongside the 5 generic products already there —
-- this is the same shop/DB ui-shop uses, so nothing here removes or renames
-- those 5; ui-shop's catalog just gains a bigger Food & Sweets category.
--
-- Guarded per-product-name, not per-category like 002's first insert: Food &
-- Sweets already has rows, so a category-wide "any rows exist" guard would
-- skip this whole insert.

--changeset junie:005-seed-choco-ecuador
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Ecuador 72% Single-Origin Bar'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Ecuador 72% Single-Origin Bar', 'Arriba Nacional cacao with notes of dried fig, jasmine and toasted hazelnut.', 12.50, 120, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/ecuador72bar/400/400');

--changeset junie:005-seed-choco-seasaltcaramel
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Sea Salt Caramel Truffles'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Sea Salt Caramel Truffles', 'Slow-simmered caramel enrobed in dark couverture, finished with fleur de sel.', 24.00, 80, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/seasaltcaramel/400/400');

--changeset junie:005-seed-choco-madagascar
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Madagascar 85% Intense'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Madagascar 85% Intense', 'Bright red-berry acidity and deep cocoa — our boldest single-origin pour.', 13.50, 90, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/madagascar85/400/400');

--changeset junie:005-seed-choco-signaturegiftbox
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'The Signature Gift Box'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('The Signature Gift Box', 'Sixteen hand-finished pralines and truffles in our espresso keepsake box.', 48.00, 45, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/signaturegiftbox/400/400');

--changeset junie:005-seed-choco-pistachiorose
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Pistachio & Rose Praline'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Pistachio & Rose Praline', 'Sicilian pistachio gianduja layered with a whisper of Damask rose.', 26.00, 60, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/pistachiorose/400/400');

--changeset junie:005-seed-choco-ghana65
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Ghana 65% Velvet Bar'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Ghana 65% Velvet Bar', 'Round, warm and chocolatey — brown butter, honey and a long cocoa finish.', 11.50, 140, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/ghana65bar/400/400');

--changeset junie:005-seed-choco-hotchocflakes
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Hot Chocolate Flakes'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Hot Chocolate Flakes', 'Shaved 70% couverture for the thickest European-style drinking chocolate.', 18.00, 75, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/hotchocflakes/400/400');

--changeset junie:005-seed-choco-espressoganache
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Espresso Ganache Collection'
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Espresso Ganache Collection', 'Nine dark ganaches infused with single-estate arabica and a gold-dusted top.', 32.00, 50, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/espressoganache/400/400');
