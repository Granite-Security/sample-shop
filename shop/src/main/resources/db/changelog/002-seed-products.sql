--liquibase formatted sql

--changeset junie:002-seed-categories
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM category
INSERT INTO category (name, description) VALUES
('Electronics', 'Electronic gadgets and accessories'),
('Clothing', 'Apparel and fashion items'),
('Books', 'Books and publications');

--changeset junie:002-seed-products-electronics
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE category_id = (SELECT id FROM category WHERE name = 'Electronics')
INSERT INTO product (name, description, price, stock, category_id) VALUES
('Wireless Headphones', 'Bluetooth 5.2 noise-canceling headphones', 79.99, 50, (SELECT id FROM category WHERE name = 'Electronics')),
('USB-C Hub', '7-in-1 USB-C hub with HDMI, USB 3.0, SD card', 34.99, 100, (SELECT id FROM category WHERE name = 'Electronics')),
('Mechanical Keyboard', 'RGB backlit mechanical keyboard with Cherry MX switches', 119.99, 30, (SELECT id FROM category WHERE name = 'Electronics'));

--changeset junie:002-seed-products-clothing
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE category_id = (SELECT id FROM category WHERE name = 'Clothing')
INSERT INTO product (name, description, price, stock, category_id) VALUES
('Cotton T-Shirt', 'Premium cotton crew-neck t-shirt', 24.99, 200, (SELECT id FROM category WHERE name = 'Clothing')),
('Denim Jacket', 'Classic blue denim jacket', 89.99, 40, (SELECT id FROM category WHERE name = 'Clothing')),
('Running Shoes', 'Lightweight running shoes with cushioned sole', 129.99, 60, (SELECT id FROM category WHERE name = 'Clothing'));

--changeset junie:002-seed-products-books
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE category_id = (SELECT id FROM category WHERE name = 'Books')
INSERT INTO product (name, description, price, stock, category_id) VALUES
('Spring in Action', 'Comprehensive guide to the Spring framework', 49.99, 25, (SELECT id FROM category WHERE name = 'Books')),
('Reactive Spring', 'Building reactive systems with Spring', 44.99, 20, (SELECT id FROM category WHERE name = 'Books')),
('Design Patterns', 'Elements of reusable object-oriented software', 39.99, 35, (SELECT id FROM category WHERE name = 'Books'));

--changeset junie:002-set-product-images
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE image_url IS NOT NULL
UPDATE product SET image_url = 'https://picsum.photos/seed/headphones/400/400' WHERE name = 'Wireless Headphones';
UPDATE product SET image_url = 'https://picsum.photos/seed/usbchub/400/400' WHERE name = 'USB-C Hub';
UPDATE product SET image_url = 'https://picsum.photos/seed/keyboard/400/400' WHERE name = 'Mechanical Keyboard';
UPDATE product SET image_url = 'https://picsum.photos/seed/tshirt/400/400' WHERE name = 'Cotton T-Shirt';
UPDATE product SET image_url = 'https://picsum.photos/seed/jacket/400/400' WHERE name = 'Denim Jacket';
UPDATE product SET image_url = 'https://picsum.photos/seed/shoes/400/400' WHERE name = 'Running Shoes';
UPDATE product SET image_url = 'https://picsum.photos/seed/springinaction/400/400' WHERE name = 'Spring in Action';
UPDATE product SET image_url = 'https://picsum.photos/seed/reactivespring/400/400' WHERE name = 'Reactive Spring';
UPDATE product SET image_url = 'https://picsum.photos/seed/designpatterns/400/400' WHERE name = 'Design Patterns';

--changeset junie:002-seed-category-food
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM category WHERE name = 'Food & Sweets'
INSERT INTO category (name, description) VALUES ('Food & Sweets', 'Delicious chocolates, truffles and gourmet treats');

--changeset junie:002-seed-products-food
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM product WHERE category_id = (SELECT id FROM category WHERE name = 'Food & Sweets')
INSERT INTO product (name, description, price, stock, category_id, image_url) VALUES
('Dark Chocolate Bar', 'Rich 72% cacao dark chocolate with hints of vanilla', 6.99, 150, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/darkchoc/400/400'),
('Milk Chocolate Bar', 'Creamy Belgian milk chocolate', 5.99, 200, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/milkchoc/400/400'),
('Truffle Collection Box', 'Assorted 12-piece champagne and dark chocolate truffles', 24.99, 80, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/truffles/400/400'),
('Hazelnut Chocolate', 'Milk chocolate with whole hazelnuts', 8.99, 120, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/hazelnut/400/400'),
('White Chocolate Truffles', 'Velvety white chocolate truffles with raspberry center', 19.99, 60, (SELECT id FROM category WHERE name = 'Food & Sweets'), 'https://picsum.photos/seed/whitetruffle/400/400');
