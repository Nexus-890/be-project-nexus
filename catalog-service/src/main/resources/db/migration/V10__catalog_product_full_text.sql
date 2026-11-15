CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

CREATE TEXT SEARCH CONFIGURATION public.catalog_product_search (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION public.catalog_product_search
    ALTER MAPPING FOR asciiword, asciihword, hword_asciipart, word, hword, hword_part
    WITH public.unaccent, pg_catalog.simple;

ALTER TABLE products
    ADD COLUMN name_search tsvector GENERATED ALWAYS AS
        (to_tsvector('public.catalog_product_search'::regconfig, name)) STORED,
    ADD COLUMN description_search tsvector GENERATED ALWAYS AS
        (to_tsvector('public.catalog_product_search'::regconfig, coalesce(description, ''))) STORED;

CREATE INDEX idx_products_name_search ON products USING gin (name_search)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
CREATE INDEX idx_products_description_search ON products USING gin (description_search)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
