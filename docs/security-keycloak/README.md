# Keycloak token trust acceptance (#237)

## Contract

Gateway and all six business-service configurations trust Keycloak realm `nexus`
with audience `nexus-services`, not user-service-issued tokens. See
[ADR-006](../adr/ADR-006-service-authentication-RS256.md).

`KEYCLOAK_ISSUER_URI` and `KEYCLOAK_JWK_SET_URI` are deployment overrides. Defaults
use `http://localhost:18080/realms/nexus` and its
`/protocol/openid-connect/certs` endpoint. No private signing key is needed by
Gateway or business resource servers. The remaining `nexus.iam.jwt` configuration
is obsolete issuance compatibility pending #238, not an additional trusted issuer.

## Reproduce

Prerequisites: merge #236 first (provides `infra/local-dev/keycloak` fixtures),
Docker Compose, OpenSSL, Python 3 stdlib and Java 21+. No Python dependencies.
Build the executable jars and install shared libraries:

```bash
cd be-project-nexus
JAVA_HOME=/path/to/java21 ./config-service/mvnw -f lib/pom.xml install
for service in config-service api-gateway catalog-service; do
  JAVA_HOME=/path/to/java21 "$service/mvnw" -f "$service/pom.xml" verify
done
JAVA_HOME=/path/to/java21 python3 docs/security-keycloak/test-token-trust.py
```

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
6. Signs a representative retired user-service token with a temporary independent
   RSA key. Tests both `iss=user-service` and a forged Keycloak `iss`; expects
   **401** at Gateway and direct Catalog. No old production private key is read.
7. Confirms the original valid seller token still gets **200** for its products,
   proving the rejection checks were not caused by an unavailable service.
8. Terminates its own JVMs, deletes only its isolated Compose project/volumes and
   removes temporary credentials and logs, also on ordinary test failures.

The harness prints only assertions and created product UUIDs, not passwords or
bearer tokens. Redirect stdout/stderr to a local evidence log if desired.

## Automated coverage

- Config Server: default issuer/JWKS/audience for Gateway and all six business
  services; deployment overrides; platform services excluded; legacy issuance
  configuration remains distinct.
- Gateway: signed Keycloak-shaped access tokens, unchanged flat roles/privileges,
  missing/wrong issuer/audience, expired tokens, legacy key and signature failure,
  and the shipped Config Server YAML contract.
- `common-security`: independent servlet JWT verification, flat authority mapping,
  no-audience and legacy-key/issuer rejection, existing public endpoint policy.

Configuration in a service that does not yet depend on `common-security` is
preparation, not proof that its endpoints have a resource-server filter. The
live end-to-end acceptance specifically verifies Gateway and Catalog. Config and
Discovery deliberately remain platform services outside this token-trust scope.

Recorded run: [validation evidence](validation.md).
