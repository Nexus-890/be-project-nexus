# Auction read endpoints — #143

`GET /api/v1/auctions/{id}` and `GET /api/v1/auctions` require no bearer token at
Gateway or auction-service. Both read only the local auctions table.

## Contract

- Only PUBLIC, non-deleted auctions are visible. A hidden detail returns the same
  404 `AUCTION_NOT_FOUND` as an unknown UUID, including for signed-in callers.
- `status` is an optional, uppercase enum filter. It defaults to ACTIVE when omitted.
  An explicit status overrides the default. Filtering uses the stored status.
- Pagination is zero-based: page defaults to 0, size to 10, size range 1–100.
  Results sort by `endAt ASC, id ASC`; a client `sort` query does not override it.
- Lists return `{code,message,data:[],page:{number,size,totalElements,totalPages}}`.
  Empty and out-of-range pages return 200 with actual totals. Detail has no page.
- Unknown status or unparseable page/size/UUID uses `INVALID_PARAMETER` (400).
  Negative page or size outside 1–100 uses `VALIDATION_FAILED` (400).
  The Gateway UUID matcher rejects anonymous non-UUID detail paths with 401 before
  they reach the service; direct service UUID conversion returns 400.
- A supplied invalid bearer remains rejected. POST still requires SELLER and
  AUCTION.CREATE. Other auction methods and nested paths are not made public.
- `title` and nullable `imageUrl` are read from the saved snapshot. GET never calls
  Catalog to fill missing values, exposes no winner/leader bidder identity, and
  performs no save or event publication.

Visibility filtering and omission of status follow the implementation choices
above. The SRS §3.5.1 source was not accessible during implementation; these choices
are explicit for reviewer confirmation, not a claim that the SRS was verified.
Catalog snapshot population at creation belongs to #141; #140 alone leaves imageUrl null.

## Automated checks

Use JDK 21 and Docker Desktop (Linux containers). Build shared libraries from
`lib/` with `mvn -pl common-core,common-web,common-security -am -DskipTests install`.
Then run in each module:

```powershell
cd ../auction-service
mvn '-Duser.timezone=UTC' verify
cd ../api-gateway
mvn '-Duser.timezone=UTC' verify
cd ../config-service
mvn '-Duser.timezone=UTC' verify
cd ..
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/auction-service.yaml
```

`AuctionReadIntegrationTest` starts PostgreSQL 18 with real Flyway/JPA/security,
loads the shipped auction security YAML, and seeds seven isolated rows. It covers
snapshot reads without Catalog, state filtering, visibility/deletion, tied sort
keys across pages, defaults/totals/empty pages, validation, nullable images, 404,
unchanged database rows and protected POST. Existing token and create tests remain.
Gateway tests load the shipped policy and verify all nine public method/path
pairs, other methods, UUID matching, privileges and invalid tokens.

UTC avoids the Windows Java `Asia/Saigon` alias not recognized by some PostgreSQL
images. A locked stale `target/*.jar.original` may block Boot repackage after tests
pass; `'-Dspring-boot.repackage.skip=true'` skips only executable-jar repackaging.
It does not skip tests. The runtime check below executes current compiled classes.

## Real HTTP check with Catalog absent

From each of `auction-service`, `api-gateway`, `config-service`, after compiling
the modules, prepare dependency paths:

```powershell
mvn dependency:build-classpath '-Dmdep.outputFile=target/smoke-classpath.txt'
```

From `be-project-nexus` run:

```powershell
node docs/testing/auction-read-smoke.mjs
```

The script creates a new, throwaway PostgreSQL 18 container, launches the real
Config Server/Gateway/auction-service on loopback ports, and never starts Catalog.
It checks anonymous detail/list through Gateway, filter/paging/404/errors and
protected methods, then compares every stored row before/after. It stops only its
own Java processes and container. Logs and `http-evidence.json` go to a new temp
folder. Optional `AUCTION_SMOKE_OUTPUT` chooses a folder and can supply module
classpath files named `<module>-classpath.txt` there.

This is real HTTP against the application and database, using **seeded snapshots**.
Redis follows the fail-open path; no Keycloak login is required for anonymous
reads. It proves reads are independent of Catalog, not that #141 has populated
the snapshot through a real creation flow.

## Manual demonstration after #141

1. With Catalog running and #141 available, create an auction as Seller and record
   the returned ID. Confirm the stored title/imageUrl match the source product.
2. Stop Catalog. Call detail through Gateway without Authorization; expect 200
   with the same saved title/imageUrl. Check list without Authorization too.
3. Query `?status=ACTIVE&page=0&size=2`; check every status, ordering and page totals
   against the isolated DB. Use test data or the scheduling flow to obtain ACTIVE
   rows; #143 itself never changes an auction's status.
4. Query an unknown UUID; expect 404 `AUCTION_NOT_FOUND`. Anonymous POST remains 401.

Do not describe the seeded runtime check as this creation integration passing.
