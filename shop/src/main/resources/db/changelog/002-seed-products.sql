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
