--liquibase formatted sql

-- The real SI Chocolate range, so a rebuilt cluster comes back with the products
-- we actually sell.
--
-- Until now it existed only in the running database. 005 seeded eight
-- placeholder chocolates, and those eight rows were then renamed, repriced and
-- rewritten in place through the admin UI — the picsum image_urls below still
-- carry 005's seeds ("ecuador72bar", "seasaltcaramel", …) in matching order,
-- which is the fingerprint of that history. So `kubectl delete namespace granite`
-- would have brought back Ecuador 72% Single-Origin Bar and Sea Salt Caramel
-- Truffles, and nothing else: every real product would be gone with no error
-- anywhere, because as far as Liquibase is concerned 005 succeeded.
--
-- Values are a snapshot taken 2026-08-11 from the live shopdb. Prices and
-- descriptions are stable; `stock` is not — it moves with every order and every
-- admin edit, so treat the numbers here as a starting point after a rebuild, not
-- as an inventory record.
--
-- packaging_group_id is set here rather than left to 018. On a fresh cluster 018
-- runs *before* this file and matches by name, so it would find nothing: these
-- rows do not exist yet at that point. 018 stays for the cluster it was written
-- to fix; this is what makes a rebuild come back correct.
--
-- IMAGES ARE NOT PRESERVED BY THIS FILE. The media URLs point at
-- media.granite-security.org, served by garage out of `garage-pvc` in the same
-- namespace. Deleting the namespace deletes that PVC and the photographs with
-- it, and these rows would come back pointing at URLs that 404. Back the bucket
-- up first — see README.
--changeset moldo:019-si-sour-cherry-cinnamon-delice
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Sour Cherry Cinnamon Delice'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Sour Cherry Cinnamon Delice', 'Sour cherry gel paired with a dark chocolate sour cherry and cinnamon ganache.', 20.00, 6.10, 13, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/ecuador72bar/400/400', '[{"key":"products/b8f00853-db0d-4d80-9ee5-dee1fc4cd869/Screenshot_20260810_184357_Google.jpg","url":"https://media.granite-security.org/products/b8f00853-db0d-4d80-9ee5-dee1fc4cd869/Screenshot_20260810_184357_Google.jpg","contentType":"image/jpeg","isDefault":true},{"key":"products/bb0ef783-8654-4ef1-8ac3-4c1bc545c514/granite.png","url":"https://media.granite-security.org/products/bb0ef783-8654-4ef1-8ac3-4c1bc545c514/granite.png","contentType":"image/png","isDefault":false}]', FALSE, NULL, now(), now());
--rollback DELETE FROM product WHERE name = 'Sour Cherry Cinnamon Delice';

--changeset moldo:019-si-pine-nut-truffle
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Pine Nut Truffle'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Pine Nut Truffle', 'Crafted with decadent 70% dark chocolate and subtly infused with the distinct character of Don Papa Rum.', 4.00, 12.00, 71, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/seasaltcaramel/400/400', '[{"key":"products/0c90a364-4d84-47cc-811f-8e01d756f9ac/20241130_124022.jpg","url":"https://media.granite-security.org/products/0c90a364-4d84-47cc-811f-8e01d756f9ac/20241130_124022.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'), now(), now());
--rollback DELETE FROM product WHERE name = 'Pine Nut Truffle';

--changeset moldo:019-si-wilda-blueberry-dark-chocolate-bonbon
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Wilda Blueberry & Dark Chocolate Bonbon'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Wilda Blueberry & Dark Chocolate Bonbon', 'Blueberry gel and dark chocolate blueberry ganache, topped with freeze-dried blueberries.', 20.00, 6.75, 89, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/madagascar85/400/400', '[{"key":"products/27118bda-160d-4188-968c-560bc7ed7658/Screenshot_20260810_190335_Gallery.jpg","url":"https://media.granite-security.org/products/27118bda-160d-4188-968c-560bc7ed7658/Screenshot_20260810_190335_Gallery.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, NULL, now(), now());
--rollback DELETE FROM product WHERE name = 'Wilda Blueberry & Dark Chocolate Bonbon';

--changeset moldo:019-si-orange-orange-chocolate-marmalade
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Orange & Orange Chocolate Marmalade'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Orange & Orange Chocolate Marmalade', '
Zesty orange marmalade layered with orange-infused chocolate.', 20.00, 24.00, 45, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/signaturegiftbox/400/400', '[{"key":"products/f549d711-f753-4c49-b378-467d60bc2bc6/Screenshot_20260810_190354_Gallery.jpg","url":"https://media.granite-security.org/products/f549d711-f753-4c49-b378-467d60bc2bc6/Screenshot_20260810_190354_Gallery.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, NULL, now(), now());
--rollback DELETE FROM product WHERE name = 'Orange & Orange Chocolate Marmalade';

--changeset moldo:019-si-salted-caramel-hazelnut-rocher
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Salted Caramel & Hazelnut Rocher'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Salted Caramel & Hazelnut Rocher', '
Salted caramel with roasted hazelnuts and milk chocolate hazelnut gianduja.', 15.00, 13.00, 11, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/pistachiorose/400/400', '[{"key":"products/9cc2b0c7-04f6-49ba-ae7b-734244e024b3/1000056027.jpg","url":"https://media.granite-security.org/products/9cc2b0c7-04f6-49ba-ae7b-734244e024b3/1000056027.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, NULL, now(), now());
--rollback DELETE FROM product WHERE name = 'Salted Caramel & Hazelnut Rocher';

--changeset moldo:019-si-rose-raspberry-truffle
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'ROSE & RASPBERRY TRUFFLE'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('ROSE & RASPBERRY TRUFFLE', 'Callebaut Velvet 32% cocoa white chocolate, freeze-dried raspberries, raspberry syrup, agar-agar, rose extract', 2.50, 5.75, 140, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/ghana65bar/400/400', '[{"key":"products/8b66e9f7-a59f-40e3-bf3e-42e3b2bc372d/20241130_123956.jpg","url":"https://media.granite-security.org/products/8b66e9f7-a59f-40e3-bf3e-42e3b2bc372d/20241130_123956.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'), now(), now());
--rollback DELETE FROM product WHERE name = 'ROSE & RASPBERRY TRUFFLE';

--changeset moldo:019-si-callebaut-coconut-velvet-truffle
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Callebaut Coconut Velvet Truffle'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Callebaut Coconut Velvet Truffle', 'A luxurious white chocolate truffle crafted with rich Callebaut white chocolate and velvet coconut, finished with a fine dusting of toasted coconut flakes for a smooth, tropical finish.', 1.00, 9.00, 71, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/hotchocflakes/400/400', '[{"key":"products/92f94685-88f7-425b-9414-179c4546c7f7/20241130_124039.jpg","url":"https://media.granite-security.org/products/92f94685-88f7-425b-9414-179c4546c7f7/20241130_124039.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'), now(), now());
--rollback DELETE FROM product WHERE name = 'Callebaut Coconut Velvet Truffle';

--changeset moldo:019-si-espresso-ganache-collection
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Espresso Ganache Collection'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Espresso Ganache Collection', 'Nine dark ganaches infused with single-estate arabica and a gold-dusted top.', 3.40, 16.00, 50, (SELECT id FROM category WHERE name = 'SI Chocolate'), 'https://picsum.photos/seed/espressoganache/400/400', '[{"key":"products/9d0a82c5-7738-49f9-a066-fa906ea553b7/20241130_124010.jpg","url":"https://media.granite-security.org/products/9d0a82c5-7738-49f9-a066-fa906ea553b7/20241130_124010.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'), now(), now());
--rollback DELETE FROM product WHERE name = 'Espresso Ganache Collection';

--changeset moldo:019-si-dubai-style-pistachio-kataifi-truffle
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE name = 'Dubai Style Pistachio & Kataifi Truffle'
INSERT INTO product (name, description, price, unit_cost, stock, category_id, image_url, media, discontinued, packaging_group_id, created_at, updated_at) VALUES
('Dubai Style Pistachio & Kataifi Truffle', 'A crisp, indulgence-packed truffle featuring velvety milk chocolate filled with crunchy toasted kataifi pastry and rich pistachio cream.
Pairing: An espresso, a glass of Prosecco/Champagne, or a smooth Aged Rum.', 3.50, 0.50, 29, (SELECT id FROM category WHERE name = 'SI Chocolate'), '', '[{"key":"products/b8daff38-d7f4-46e1-ac6f-3416d87880c1/20241130_124043.jpg","url":"https://media.granite-security.org/products/b8daff38-d7f4-46e1-ac6f-3416d87880c1/20241130_124043.jpg","contentType":"image/jpeg","isDefault":false}]', FALSE, (SELECT id FROM packaging_group WHERE code = 'TRUFFLE'), now(), now());
--rollback DELETE FROM product WHERE name = 'Dubai Style Pistachio & Kataifi Truffle';

-- Retire 005's placeholders on a cluster that rebuilt from scratch.
--
-- No-op on the cluster this was written from: those eight rows were renamed
-- years of edits ago and no product answers to these names any more. On a fresh
-- cluster they are exactly the eight rows 005 just inserted, and leaving them
-- would put editorial fiction next to the real catalogue.
--
-- Discontinued rather than deleted, following 012: order_item has a NO ACTION
-- foreign key to product, so a hard delete of anything ever ordered fails at the
-- database, and order history is the one thing a catalogue decision must not
-- reach back into. Discontinued rows leave the storefront and stay resolvable.
--changeset moldo:019-retire-005-placeholders
UPDATE product SET discontinued = TRUE, updated_at = now()
WHERE name IN ('Ecuador 72% Single-Origin Bar',
               'Sea Salt Caramel Truffles',
               'Madagascar 85% Intense',
               'The Signature Gift Box',
               'Pistachio & Rose Praline',
               'Ghana 65% Velvet Bar',
               'Hot Chocolate Flakes');
--rollback UPDATE product SET discontinued = FALSE WHERE name IN ('Ecuador 72% Single-Origin Bar', 'Sea Salt Caramel Truffles', 'Madagascar 85% Intense', 'The Signature Gift Box', 'Pistachio & Rose Praline', 'Ghana 65% Velvet Bar', 'Hot Chocolate Flakes');
