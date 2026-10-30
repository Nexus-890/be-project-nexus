# Image delete, reorder and seed — #123

Prerequisite: #122 / MR !88. The corrected upload branch is merged into this
feature branch for development; MR !88 still needs review and merge before #123.
#108 is already merged, allowing #109 to proceed independently.

## Delivered code

- `ProductImageService.delete`: lock product, check JWT seller ownership and image
  membership, delete/flush the DB row, delete the configured Supabase object.
  Storage failure throws a named 503 error and rolls back the row deletion.
- `reorder`: validate that imageIds is exactly the current image set, including
  cardinality and duplicate checks, before assigning sortOrder 0..n-1. Upload,
  delete and reorder use the same product row lock.
- DELETE returns bodyless 204. PUT returns ApiResponse with data and page.
  Both require SELLER and PRODUCT.UPDATE in the service; gateway requires
  PRODUCT.UPDATE. Errors are Problem Details with named CATALOG_* business codes.
- V7 seeds one image per V3 demo product. V5 and V6 already exist, so their applied
  checksums are preserved. `infra/local-dev/seed-images` contains the JPEGs,
  deterministic manifest and sources; `provision-product-images.ps1` provisions
  matching bytes without overwriting different existing objects.

## Verification — 2026-10-30, Java 21, UTC

- catalog-service `mvnw.cmd -Duser.timezone=UTC verify`: **76 passed, 0 skipped**.
  Includes 13 real PostgreSQL/full application/filter image persistence tests.
- api-gateway same command: **40 passed, 0 skipped**. Its RS256/JWKS fixture checks
  every configured protected operation, including both new image operations.
- `node docs/api/check-contract.mjs`: **35 operations**, pass on this branch.
- Redocly CLI 2.63.0 lint of catalog-service.yaml: pass.
- Provisioning public GET: all five seed object URLs returned image bytes exactly
  matching the checked-in JPEGs (73,365 bytes for each phone, 43,722 for shirt,
  59,825 for sneaker). These JPEGs were visually inspected. Fresh Flyway migration
  tests verify exactly one image with sortOrder 0 on each of the five V3 products.
- Reordering two uploads changes the DB and detail response order. Missing,
  duplicate, foreign, null and malformed IDs return 400 and do not change order.
  Another seller returns 403; no token 401; missing privilege 403; missing or
  foreign-product image 404. A provider failure preserves the DB row. A foreign
  storage URL is refused without sending a deletion to that host.

## Explicit acceptance discrepancy: public GET after real Supabase deletion

The strict live test expected HTTP 404 and **failed**: actual HTTP status was 400.
The service DELETE itself returned 204 and the DB row was gone. A separately
requested provider diagnostic verifies public GET is HTTP 400 with JSON
`statusCode: "404"`, rather than image bytes. This does **not** prove the issue's
literal HTTP 404 criterion. That criterion remains unverified/failed on the team's
current provider and needs reviewer agreement; do not mark it passed.

Supabase documents a legacy error format alongside its newer error codes:
https://supabase.com/docs/guides/storage/debugging/error-codes#legacy-error-codes
The observed response is live evidence, not an inference from the documentation.

To reproduce, export SUPABASE_URL, SUPABASE_SERVICE_KEY and SUPABASE_BUCKET, then
run from catalog-service with Docker active (random test product/object only):

```powershell
# Strict criterion; currently fails 400 != 404 on the configured provider.
.\mvnw.cmd -Duser.timezone=UTC -Dcatalog.supabase.live=true `
  '-Dtest=ProductImagePersistenceTest#deleteRemovesRowAndPublicObject' test

# Explicit diagnostic of the provider's actual 400 + statusCode 404 behavior.
.\mvnw.cmd -Duser.timezone=UTC -Dcatalog.supabase.live=true `
  -Dcatalog.supabase.legacyNotFound=true `
  '-Dtest=ProductImagePersistenceTest#deleteRemovesRowAndPublicObject' test
```

Default tests use isolated PostgreSQL and an HTTP storage fixture. JWT decoding
is mocked there; Spring security filters, method authorization, ownership, JPA,
transactions, locking and storage HTTP requests are real. The strict 404 check
remains the default; the diagnostic requires an explicit opt-in property.

## Transaction boundary

PostgreSQL and Supabase do not share a transaction. A storage rejection rolls the
DB deletion back. A process/commit failure after successful storage deletion can
still leave a row whose file is gone. Upload compensation is best effort too.
This implementation does not claim distributed atomicity or automatic durable
cleanup retries. Keep this limitation explicit in review.
