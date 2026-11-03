# Image delete, reorder and seed — #123

Prerequisite: #122 / MR !88, already merged into develop.

## Delivered code

- `ProductImageService.delete`: lock product, check JWT seller ownership and image
  membership, delete/flush the DB row, delete the configured Supabase object.
  Storage failure throws a named 503 error and rolls back the row deletion.
  An image outside the configured project/bucket (including V7 seed URLs from
  another environment) removes only the local row, logs a warning with the
  product ID, and sends no storage request. Ownership checks still apply.
  An invalid product path or filename inside the configured bucket returns
  409 `CATALOG_IMAGE_STORAGE_PATH_INVALID` and preserves the row.
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
  foreign-product image 404. A provider failure preserves the DB row.

## Review correction — 2026-11-03

MR !97 review identified that V7 demo images could not be removed when
`SUPABASE_URL` differed from the team's seed project. The fix uses the reviewer's
second option: allow deletion of the local image row without touching storage
outside the configured project/bucket. V7 stays unchanged so existing database
checksums remain valid. Provisioning a different bucket and rewriting URLs is
only needed to host the demo images in that bucket.

Regression coverage uses isolated PostgreSQL and HTTP fixtures to verify deletion
of an actual V7 seed row with different storage configuration, row-only deletion
for another host/bucket, no outbound request to either storage, and 403 for
another seller. Invalid paths within the configured bucket return 409 without
changing any row columns or calling storage. Existing tests cover normal object
deletion and rollback when the provider fails.

Validation on the review fix with Java 21: shared-library `install` passed;
catalog-service `verify` passed **105 tests, 0 failures/errors/skips**; the contract
checker passed **38 operations**; Redocly lint passed for the catalog OpenAPI.
Storage regression tests use HTTP fixtures; the live-provider AC1 discrepancy
below was not rechecked by this run.

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
