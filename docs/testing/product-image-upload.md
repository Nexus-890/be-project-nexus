# Product image upload audit — #122

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
