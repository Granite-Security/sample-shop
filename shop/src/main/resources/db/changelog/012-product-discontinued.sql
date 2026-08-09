--liquibase formatted sql

-- Soft delete for products.
--
-- DELETE /api/shop/products/{id} was a bare deleteById, and order_item has a
-- NO ACTION foreign key to product, so deleting anything that had ever been
-- ordered raised order_item_product_id_fkey and surfaced as a 500 (nothing
-- maps DataIntegrityViolationException, so GlobalErrorHandler falls through).
--
-- The refusal was right even though the error was not: order_item carries
-- unit_price but not the product name, so a hard delete would leave order
-- history pointing at a row that no longer exists. Retiring a product is a
-- catalog decision and must not reach back into orders that already happened.
--
-- So DELETE now sets this flag, listings hide it, and GET by id still resolves
-- it — that last part is what keeps the links from OrderDetailPage working.
--
-- (Comment lines must not start with a Liquibase directive word such as
-- "property" — the formatted-SQL parser reads them as a malformed directive
-- and fails the whole changelog. See 011.)

--changeset moldo:012-product-discontinued
ALTER TABLE product ADD COLUMN discontinued BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE product DROP COLUMN discontinued;

-- Listings filter on this column on every catalog request, which is the
-- hottest read path in the service. Partial index: the discontinued rows are
-- the rare ones and are never what the storefront asks for.
--changeset moldo:012-product-discontinued-index
CREATE INDEX idx_product_active ON product (id) WHERE discontinued = FALSE;
--rollback DROP INDEX idx_product_active;
