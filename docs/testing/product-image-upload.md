# Product image upload audit — #122

## MR !88 review correction — 2026-11-02

The upload orchestration no longer holds a transaction while calling Supabase.
`ProductImageService.upload` uses propagation NEVER, rejecting ambient transactions.
A separate proxied `ProductImageUploadTransactions` bean owns three short transactions:

1. `reserve`: lock/check product ownership, reclaim expired claims, count persisted
   images plus pending claims and commit a slot reservation before storage I/O.
2. `complete`: after storage returns, lock/recheck product ownership, consume the
   unexpired reservation, calculate sort order and commit image metadata.
3. `release`: remove the reservation after a failed upload/finalization.

Storage upload and compensation DELETE both run outside those transactions.
If finalization (including commit) fails, the orchestration discards the uploaded
file after the transaction exits and releases the reservation. Compensation is
best effort, not distributed atomicity. A process crash after upload can still
leave an orphan file and requires operational cleanup. Failed/crashed reservations
expire after five minutes and are reclaimed by the next attempt on that product;
an expired upload cannot finalize. No background cleanup guarantee is claimed.

`V6_1__product_image_upload_reservations.sql` adds the indexed reservation table
with product FK/cascade. Version 6.1 follows the current V6 without occupying V7,
which the unmerged #123 seed branch already uses. Existing migrations are unchanged.

Regression evidence is in `ProductImagePersistenceTest`:

- Deliberately block storage HTTP. Shrink the pool to one connection after Flyway
  starts, then read the committed reservation and acquire the same product's
  `FOR UPDATE` lock in a separate transaction with a 500 ms lock timeout. Both work
  before releasing HTTP. This fails with the old transaction-around-upload design.
- Five active claims reject upload before any storage POST; expired claims free capacity.
- A real PostgreSQL image INSERT constraint failure rolls back metadata and removes
  both the uploaded object and the reservation.
- Concurrent uploads at four images still result in one 201, one 400, five rows
  and five storage uploads total.

The develop merge preserves both PostgreSQL upload tests and Kafka/schema outbox
dependencies. The contract inventory also includes the delivered cart PUT/DELETE
operations from develop; no commerce runtime behavior is changed.

Final Java 21 / UTC verification on 2026-11-02:

- catalog-service `mvnw.cmd -Duser.timezone=UTC verify`: **91 passed, 0 skipped**,
  including all 18 upload unit cases and 7 full-application image persistence cases.
- api-gateway verify: **40 passed, 0 skipped**; config-service verify: **3 passed**.
- `node docs/api/check-contract.mjs`: **36 delivered operations**, pass.
- Redocly CLI 2.63.0 lint: catalog and commerce specifications pass.
- Refactored live Supabase JPEG upload: **1 passed**; 201, persisted image row,
  public GET 200, matching bytes and successful ImageIO decode. The isolated
  product/object were cleaned afterwards; JWT decoding remains a test fixture.
- Configured service key absent from tracked files and the seven review logs scanned.
- The full run exposed an existing PostgreSQL fixture readiness race in
  CatalogPersistenceTest; it now waits for PostgreSQL readiness after initdb,
  rather than relying only on a listening port. The four persistence tests pass.

Rechecked on 2026-10-30 against the current develop API contract.

Corrections: upload returns `ApiResponse<ImageResponse>`; failures use named
`CATALOG_*` codes; the delivered OpenAPI inventory includes ProductImageController.
Public and seller product lists now include images ordered by sortOrder and ID,
loaded in one batch query for the returned products rather than one query per row.
The existing content-based MIME validation, pre-upload count limit, product row
lock and rollback cleanup remain in place.

Verification with Java 21 and JVM timezone UTC:

- catalog-service `mvnw.cmd -Duser.timezone=UTC verify`: **67 tests passed**.
- ProductImageUploadTest: 18 cases, including 200 KB JPEG, PNG/WebP, fake .jpg,
  count/size limits, owner/role/privilege checks, provider failures and compensation.
- ProductImagePersistenceTest: 4 cases using PostgreSQL, real security filters and
  the real application. Two concurrent uploads at four existing images produce
  one 201 and one 400, with exactly five rows/uploads. List/detail ordering passes.
- Live Supabase run of `ProductImagePersistenceTest#jpeg200KbIsPersistedAndItsPublicUrlRenders`
  with `-Dcatalog.supabase.live=true`: **1 passed**. The configured public bucket
  accepts a 200 KB JPEG through the service, the image row persists, public GET is
  200, bytes match and ImageIO decodes it. Cleanup removes the random test object's
  storage file and DB row; seeded/development products are not used.
- Bucket metadata was checked: public `product-images`, 5 MB provider limit.
- Contract inventory: 33 operations on this branch; Redocly 2.63.0 catalog lint passed.

Live test credentials come only from the backend environment (`SUPABASE_URL`,
`SUPABASE_SERVICE_KEY`, optional `SUPABASE_BUCKET`); they are not command-line
arguments or checked-in test values. Default tests use an HTTP storage fixture.
The JWT decoder is a fixture in the DB tests; ownership, authorization, JPA, row
locking and storage HTTP calls are real. No live test is silently substituted for
the default isolated test run.

MR !88 remains the prerequisite for #123 and still requires human merge/review.
