# Issue #237 validation — 2026-10-24

Base: `origin/develop` at `d816a5e`. Java: OpenJDK 21.0.12.1.
Local live fixtures came from #236 commit `2f60aa6`; they were used temporarily
for validation and are **not** part of the #237 commit.

## Maven

All commands completed with `BUILD SUCCESS`, zero failures and zero errors:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./be-project-nexus/config-service/mvnw -f be-project-nexus/lib/pom.xml install
# install includes verify and makes shared artifacts available to standalone services
for service in config-service api-gateway catalog-service user-service \
  commerce-service auction-service fulfillment-service notification-service; do
  "./be-project-nexus/$service/mvnw" -f "be-project-nexus/$service/pom.xml" verify
done
```

| Module | Tests discovered | Skipped | Failures/errors |
|---|---:|---:|---:|
| lib reactor | 34 | 0 | 0 |
| config-service | 5 | 0 | 0 |
| api-gateway | 40 | 0 | 0 |
| catalog-service | 34 | 0 | 0 |
| user-service | 183 | 17 | 0 |
| commerce-service | 52 | 2 | 0 |
| auction-service | 3 | 0 | 0 |
| fulfillment-service | 4 | 0 | 0 |
| notification-service | 17 | 0 | 0 |

The 17 existing registration integration tests require `REGISTRATION_TEST_DB_URL`;
the two existing captured-payment webhook tests require `SEPAY_CAPTURE_TEST=true`.
These opt-in suites were not enabled.

Review follow-up added the existing `common-security` dependency and three actual
application-context filter tests to auction, fulfillment and notification. Each
context verifies valid Keycloak-shaped RS256/flat authorities reach a protected
probe (200), while missing bearer/forged identity headers, legacy issuer/key and
missing/wrong audience are rejected (401). Database auto-configuration and Kafka
startup are isolated; notification uses a mock JdbcTemplate for its unrelated
message repository. The SecurityFilterChain, JwtDecoder, JWKS fetch, RSA signature
verification and current-user resolution are not mocked.

## Real end-to-end acceptance

Command (exit **0**, completed 2026-10-24 12:25 Europe/Berlin):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  python3 be-project-nexus/docs/security-keycloak/test-token-trust.py
```

Successful output:

```text
PASS: real seeded seller PKCE login; Keycloak RS256 signature and flat claims verified
PASS: seller token -> real Gateway -> real Catalog POST products = 201; persisted id=c69df50c-6208-4c13-a15c-e50bdb97332e seller=68000000-0000-4000-8000-000000000002
PASS: legacy user-service issuer/key -> 401 at Gateway AND direct Catalog
PASS: legacy key with forged Keycloak issuer -> 401 at Gateway AND direct Catalog
PASS: real Keycloak token without aud -> 401 at Gateway AND direct Catalog
PASS: original valid seller token still reads own products = 200
Removed isolated containers and terminated owned application processes
```

This used Keycloak 26.7.5, PostgreSQL 18.6, actual Config Server configuration,
real Gateway routes, real Catalog controllers/services/JPA and a persisted
product row. No business upstream or JWT decoder was mocked. The legacy token
was signed with a temporary independent RSA key representing the retired
user-service signing key; no production key was used. The missing-audience token
was issued and cryptographically verified against **real Keycloak** after
removing the audience mapper in the disposable realm only.

The test's unique Docker project was removed; no `nexus-237-test-*` containers
remained. Existing containers and private `.env` were not changed.

## Scope / gaps

- This is the verification migration; #238 still owns legacy login/issuer removal.
- The realm and claim mappers from #236 must be merged/deployed first.
- The live acceptance covers Gateway and Catalog, not a full deployment of every
  business service. Unit/config contracts and the existing Maven suites cover
  the broader configuration surface.
- Final `git diff --check` and Python source compilation passed. The project has
  no separate lint/typecheck command for these Java/YAML changes.
