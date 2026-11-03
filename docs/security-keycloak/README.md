# Keycloak token trust acceptance (#237)

## Contract

Gateway and all nine business-service configurations trust Keycloak realm `nexus`
with audience `nexus-services`, not user-service-issued tokens. See
[ADR-006](../adr/ADR-006-service-authentication-RS256.md).

`KEYCLOAK_ISSUER_URI` and `KEYCLOAK_JWK_SET_URI` are deployment overrides. Defaults
use `http://localhost:18080/realms/nexus` and its
`/protocol/openid-connect/certs` endpoint. No private signing key is needed by
Gateway or business resource servers. The remaining `nexus.iam.jwt` configuration
is obsolete issuance compatibility pending #238, not an additional trusted issuer.

## Reproduce

Prerequisites: merge #236 first (provides `infra/local-dev/keycloak` fixtures),
Docker Compose, OpenSSL, Python 3 stdlib and Java 21. No Python dependencies.
#236 no longer needs host OpenSSL; #237 still uses it to sign an independent
legacy-key token for the rejection checks.
Build the executable jars and install shared libraries:

```bash
cd be-project-nexus
JAVA_HOME=/path/to/java21 ./config-service/mvnw -f lib/pom.xml install
for service in config-service api-gateway catalog-service user-service; do
  JAVA_HOME=/path/to/java21 "$service/mvnw" -f "$service/pom.xml" verify
done
python3 docs/security-keycloak/test-harness.py
JAVA_HOME=/path/to/java21 python3 docs/security-keycloak/test-token-trust.py
```

On Windows PowerShell, from `be-project-nexus` (Java 21 and OpenSSL on PATH):

```powershell
.\config-service\mvnw.cmd -f lib/pom.xml install
if ($LASTEXITCODE -ne 0) { throw 'Shared library build failed' }
foreach ($service in @('config-service', 'api-gateway', 'catalog-service', 'user-service')) {
    & ".\$service\mvnw.cmd" -f "$service/pom.xml" verify
    if ($LASTEXITCODE -ne 0) { throw "$service build failed" }
}
py -3 docs/security-keycloak/test-harness.py
if ($LASTEXITCODE -ne 0) { throw 'Fixture compatibility check failed' }
py -3 docs/security-keycloak/test-token-trust.py
```

Use `python` instead of `py -3` if the Windows launcher is not installed.
These commands reuse #236's committed helpers, including its cross-platform JWT
verification; no Bash installation is needed on the Windows host. Bash runs only
inside the Linux Keycloak seed container. The current validation includes Windows with Java 21 and JVM timezone UTC.

For review before #236 is merged, `KEYCLOAK_FIXTURE_DIR` may point at the
`infra/local-dev/keycloak` directory in a separate checkout/export of **the latest
#236 commit**. Set it for both Python commands. Do not copy or cherry-pick #236's
realm/Compose commits into #237. Without that override the scripts use the normal
in-repository directory after #236 is merged. The offline check verifies every
helper call against the actual fixture API, catching changes such as the new
`verify_token(base, token)` signature before starting containers.

If local Keycloak uses a different port/hostname, set `KEYCLOAK_ISSUER_URI` and
`KEYCLOAK_JWK_SET_URI` in the Config Server process environment before startup.
Changing Docker's `KEYCLOAK_PORT` alone does not update resource-server URLs.
The issuer must exactly match the real access token's `iss`; JWKS may use an
internal URL reachable by the resource servers.

The harness:

1. Starts dedicated PostgreSQL 18 and Keycloak 26.7.5 containers using random
   loopback ports, temporary generated passwords, a unique Compose project and
   the committed #236 realm/seed fixtures. It does not read local private `.env`,
   use fixed container names or stop existing containers.
2. Logs in the real seeded seller through authorization code + PKCE and verifies
   the RS256 signature against the actual realm JWKS.
3. Starts real executable Config Server, Catalog and Gateway jars. They use
   Config Server's committed JWT/policy configuration; only deployment URLs,
   credentials and isolated runtime infrastructure settings are overridden.
   A dedicated `spring.config.name` avoids the normal startup imports of private
   `.env` and fixed-port Config Server; the application name stays unchanged.
   Simple discovery points the unchanged Gateway route at the real Catalog.
   Kafka listeners are disabled for this unrelated acceptance; Redis is absent
   and exercises the existing fail-open rate limiter.
4. Creates a product through Gateway: **201**; verifies its seller UUID from the
   authenticated JWT and queries Catalog PostgreSQL to prove it was persisted.
5. Removes the audience mapper **only inside the disposable realm** and logs in
   again, obtaining a genuine Keycloak-signed token with no `aud` (not an edited
   payload with an invalid signature). Expects **401** at Gateway and at direct
   Catalog access.
6. Starts real user-service with an ephemeral RSA key pair and obtains its old
   token through registration/login. Also signs a forged Keycloak issuer with
   that same old key; expects
   **401** at Gateway and direct Catalog. No old production private key is read.
7. Confirms the original valid seller token still gets **200** for its products,
   proving the rejection checks were not caused by an unavailable service.
8. Terminates its own JVMs, deletes only its isolated Compose project/volumes and
   removes temporary credentials and logs, also on ordinary test failures.

The harness prints only assertions and created product UUIDs, not passwords or
bearer tokens. Redirect stdout/stderr to a local evidence log if desired.

## Automated coverage

- Config Server: default issuer/JWKS/audience for Gateway and all nine business
  services; deployment overrides; platform services excluded; legacy issuance
  configuration remains distinct.
- Gateway: signed Keycloak-shaped access tokens, unchanged flat roles/privileges,
  missing/wrong issuer/audience, expired tokens, legacy key and signature failure,
  and the shipped Config Server YAML contract.
- `common-security`: independent servlet JWT verification, flat authority mapping,
  no-audience and legacy-key/issuer rejection, existing public endpoint policy.
- Auction, inventory and notification application contexts: actual classpath
  security auto-configuration and filter chain; valid Keycloak-shaped RS256 token
  reaches a protected probe with flat role/privilege/sub intact; absent bearer,
  forged identity headers, legacy issuer/key and missing/wrong audience return 401.
  Only unrelated database/Kafka infrastructure is isolated, not JWT verification.

All nine business services use `common-security`; auction, inventory and
notification now install the same resource-server filter as user/catalog/commerce.
The live end-to-end acceptance specifically verifies Gateway and Catalog. Config
and Discovery remain platform services outside this token-trust scope.

Recorded run: [validation evidence](validation.md).

## Current service split and evidence

The resource servers are user, catalog, commerce, auction, inventory, notification,
payment, shipment and rating; fulfillment has been retired. Config contracts cover
all nine plus Gateway. Each service depends on common-security. The live business
acceptance covers Gateway and Catalog; the real legacy user-service is a rejected
issuer fixture, not a second trusted authority.

If #236 is not merged, export commit `874fbf8` into a separate local fixture
directory and set KEYCLOAK_FIXTURE_DIR as above. It contains the current policy and
helper implementation. Do not copy its private .env or use an existing realm.

Run with Java 21 and `-Duser.timezone=UTC` for Maven on Windows. Ensure OpenSSL is
on PATH (the Git for Windows installation includes mingw64/bin/openssl.exe).
To retain sanitized evidence without JWTs, use:

```powershell
python docs/security-keycloak/test-token-trust.py --evidence-dir C:/path/to/empty-evidence-directory
```

The legacy key is generated only for the isolated user-service. This proves
rejection of tokens produced by the actual legacy JwtIssuer without reading the
operator's existing private key. Both old-issuer and forged-issuer cases are
checked. The audience-negative token is genuinely signed by Keycloak after
removing only its audience mapper in the disposable realm.
