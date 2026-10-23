# Issue #248 verification

Verified on 2026-10-23 with Java 21, Maven and the local Postman CLI 1.73.0.

## Build and runtime contracts

Shared libraries passed `mvn clean install` and subsequent targeted verification.
Every changed service passed `mvn clean verify`:

| Module | Executed test cases | Result |
| --- | ---: | --- |
| common-web | 8 | Passed, including negative ArchUnit fixtures and HTTP problem/envelope serialization |
| common-security | 14 | Passed, including 401/403 symbolic codes and active trace propagation |
| common-events | 18 | Passed |
| user-service | 186 | Passed, including the separately enabled 17 PostgreSQL HTTP integration cases |
| catalog-service | 41 | Passed, including all 16 delivered operations and list metadata |
| commerce-service | 59 | 57 passed; two existing provider-dependent captured-webhook cases skipped |
| api-gateway | 39 | Passed, including authorization, rate-limit and unavailable-service errors |
| notification-service | 17 | Passed, including internal list envelopes |
| auction-service | 1 | Passed; architecture enforcement is ready for future controllers |
| fulfillment-service | 2 | Passed; architecture enforcement is ready for future controllers |

The user PostgreSQL integration suite was enabled against a disposable PostgreSQL 18
database using its existing `REGISTRATION_TEST_DB_URL` and
`REGISTRATION_TEST_DB_PASSWORD` settings. It starts the complete application with
`ddl-auto=validate` and verifies registration/login HTTP behavior. Its standard
build remains opt-in when those settings are absent.

Four commerce HTTP cases use the real Feign client and `CartService` against a local
Postman simulation: wrapped SKU decoding and cart update, upstream 404, upstream
503, and rejection of the old bare SKU payload. The repository collaborators are
test doubles; this proves the HTTP client and envelope handling, not database or
catalog deployment behavior. The mock comes from the delivered catalog OpenAPI
contract. A published owner mock was not searched because the CLI session was not
signed in. See [the simulation instructions](../../infra/contract-simulation/README.md).

## Specification and architecture

From `be-project-nexus`:

```powershell
node docs/api/check-contract.mjs
npx --yes @redocly/cli lint docs/api/openapi/*.yaml
```

The inventory check matches all 24 implemented operations across the six named
controllers. Redocly validates all three specifications without errors or warnings.
The issue's count of 25 differs from this checkout; no extra endpoint was invented.
JWKS keeps its standard protocol format and explicit architecture exemption.

The legacy-class-name acceptance search returns no matches. ArchUnit self-tests
prove that both bare `List` and `ResponseEntity<List>` fail the rule. All six
business services run the shared rule; services without controllers allow an empty
selection until an endpoint is added. Whitespace/conflict checks pass.

To rerun the service builds, first install the shared libraries (including their
architecture test JAR), then verify each service:

```powershell
mvn -f lib/pom.xml clean install
$services = @('user-service', 'catalog-service', 'commerce-service',
              'api-gateway', 'notification-service', 'auction-service', 'fulfillment-service')
foreach ($service in $services) {
    mvn -f "$service/pom.xml" clean verify
    if ($LASTEXITCODE -ne 0) { throw "Verification failed: $service" }
}
```

## Remaining human coordination

ADR-008 and the style guide are proposed for review. Approval and the BE/FE merge
date are pending; the current FE parsers must be updated in the agreed cutover.
These test results do not represent that approval, a frontend migration, or a
production deployment.
