# Commerce/catalog envelope HTTP check

This local check runs the real Spring Cloud `CatalogClient`, its configured
Spring/Jackson decoder, and `CartService` against a Postman CLI simulation.
Only the cart repositories are mocked. No catalog process, database, discovery
server, Postman account, or external credentials are needed.

Prerequisites: Java 21, Maven, PowerShell 7, Postman CLI 1.70+ (verified with
1.73.0), and installed shared libraries (`mvn -f be-project-nexus/lib/pom.xml install`
from the repository root).

```powershell
pwsh -File be-project-nexus/infra/contract-simulation/verify-catalog-envelope.ps1
```

Use `-Port 4959` if 4958 is occupied, or `-MavenCommand <path-to-mvn.cmd>`.
The runner starts a hidden local simulation on the explicit port, waits for its
`listening` event, runs `CatalogEnvelopeHttpTest`, checks the actual dependency
request log, and stops its own process tree. Logs stay under ignored `target/`.

The test is opt-in so a normal service `verify` does not require a mock server.
To run it against an already-started simulation:

```powershell
postman simulation run be-project-nexus/infra/contract-simulation/catalog-envelope.sim.yaml --port 4958 --output ndjson
# In another terminal:
$env:CATALOG_CONTRACT_SIMULATION_URL = 'http://127.0.0.1:4958/catalog'
mvn -f be-project-nexus/commerce-service/pom.xml '-Dtest=CatalogEnvelopeHttpTest' test
```

## Contract source and cases

The catalog fixture is narrowed from `postman mock generate` using
[`catalog-service.yaml`](../../docs/api/openapi/catalog-service.yaml), specifically
`GET /api/v1/skus/{id}`, its `GetSkuResponse`/`Sku` schemas, success example and
404/503 Problem Details responses. It serves only that dependency path; other
paths return 501. Owner mock discovery was skipped because the local Postman CLI
is signed out. Nothing is pushed or deployed.

| SKU UUID suffix | Dependency response | Commerce expectation |
| --- | --- | --- |
| `...000003` | 200, `{data: {id, productId, price, ...}}` | Decode UUIDs and numeric price; write quantity 2 with the returned IDs/price; return the updated item |
| `...000004` | 404, `application/problem+json`, `CATALOG_SKU_NOT_FOUND` | `COMMERCE_SKU_NOT_FOUND`, 404; no repository writes |
| `...000005` | 503, `application/problem+json`, `SERVICE_UNAVAILABLE` | `COMMERCE_CATALOG_UNAVAILABLE`, 503; no repository writes |
| `...000006` | Deliberately invalid 200 with a bare DTO (missing `data`) | `COMMERCE_INVALID_CATALOG_RESPONSE`, 502; no repository writes |

The failure IDs are test selectors for deterministic fixtures, not production
catalog behavior. The missing-data fixture intentionally violates the new
contract to prove defensive handling of an old/incorrect response. The valid
success and documented errors preserve the OpenAPI shapes and content types.

Verified locally: four tests passed without skips; the simulation log contained
five real `GET /api/v1/skus/{id}` calls (two valid 200 calls, one 404, one 503,
one malformed 200). This establishes the changed HTTP client/decoder and cart
error handling against the documented stand-in. The test does not exercise a
real catalog database, gateway security or the cart's SQL persistence.
