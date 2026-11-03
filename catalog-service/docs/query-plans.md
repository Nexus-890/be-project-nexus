# Catalog query plan

`CatalogPersistenceTest.categoryQueryUsesTheExistingCategoryIndex` runs the product
category query against PostgreSQL 18 with the demo rows plus 10,000 products in a
different category. It runs `ANALYZE` before `EXPLAIN (ANALYZE, BUFFERS)`.

The measured plan on 2026-10-23 uses the existing index:

```text
Index Scan using idx_products_category on products p
  Index Cond: ((category_id = '69000000-0000-4000-8000-000000000102'::uuid)
               AND (status = ('ACTIVE'::cstring)::product_status))
  Filter: (deleted_at IS NULL)
```

The query returned the three matching products in 0.144 ms in this test run. This
is a local observation, not a production latency target. The SKU join used a
sequential scan for the ten demo SKUs, which is appropriate for that tiny table.
`idx_skus_product (product_id)` already supports a larger SKU join. The existing
`idx_products_category (category_id, status)` covers the category/status filter,
so this change adds no duplicate catalog index.

The native query casts its bound status with `CAST(? AS product_status)`. Current
catalog repositories use JPQL for enum filters, so Hibernate binds those values
with the entity's `NAMED_ENUM` mapping.

Run from `catalog-service` with Docker available:

```powershell
.\mvnw.cmd -Dtest=CatalogPersistenceTest test
```
