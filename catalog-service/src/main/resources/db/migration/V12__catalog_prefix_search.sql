-- Let PostgreSQL quote/normalize literal input before adding the final lexeme prefix.
-- Empty or punctuation-only queries stay empty rather than becoming invalid tsquery syntax.
CREATE FUNCTION public.catalog_product_prefix_query(search_text text)
RETURNS tsquery
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$
    SELECT CASE WHEN numnode(phrase) = 0 THEN phrase
                ELSE (phrase::text || ':*')::tsquery END
    FROM (SELECT phraseto_tsquery('public.catalog_product_search', search_text) AS phrase) normalized
$$;
