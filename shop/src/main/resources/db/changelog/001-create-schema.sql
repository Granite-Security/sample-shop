--liquibase formatted sql

--changeset junie:001-create-category-table
CREATE TABLE category (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE category;

--changeset junie:001-create-product-table
CREATE TABLE product (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(255)  NOT NULL,
    description TEXT,
    price       NUMERIC(10,2) NOT NULL,
    stock       INTEGER       NOT NULL DEFAULT 0,
    category_id BIGINT        NOT NULL REFERENCES category(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_category_id ON product(category_id);
--rollback DROP TABLE product;

--changeset junie:001-create-customer-order-table
CREATE TABLE customer_order (
    id         BIGSERIAL     PRIMARY KEY,
    username   VARCHAR(64)   NOT NULL,
    status     VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    total      NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_order_username ON customer_order(username);
--rollback DROP TABLE customer_order;

--changeset junie:001-create-order-item-table
CREATE TABLE order_item (
    id         BIGSERIAL     PRIMARY KEY,
    order_id   BIGINT        NOT NULL REFERENCES customer_order(id),
    product_id BIGINT        NOT NULL REFERENCES product(id),
    quantity   INTEGER       NOT NULL,
    unit_price NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_item_order_id ON order_item(order_id);
--rollback DROP TABLE order_item;

--changeset junie:001-add-image-url
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'product' AND column_name = 'image_url'
ALTER TABLE product ADD COLUMN image_url VARCHAR(512);
--rollback ALTER TABLE product DROP COLUMN image_url;
