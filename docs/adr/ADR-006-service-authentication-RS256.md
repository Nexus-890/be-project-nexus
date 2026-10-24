# ADR-006: Keycloak-issued RS256 access tokens

- Status: Accepted; issuer migration in #236–#238 supersedes the original user-service issuer decision.
- Scope: Gateway and business-service access-token validation.

## Decision

Keycloak realm `nexus` is the authoritative access-token issuer and JWKS owner.
Gateway and every protected business service independently validate the original
bearer token. Neither a trusted Gateway nor client-supplied identity headers
replace service-side authentication.

| Setting | Local default |
|---|---|
| Issuer (`iss`) | `http://localhost:18080/realms/nexus` |
| JWKS | `http://localhost:18080/realms/nexus/protocol/openid-connect/certs` |
| Audience (`aud`) | Contains `nexus-services` |
| Algorithm | RS256 only |

`KEYCLOAK_ISSUER_URI` and `KEYCLOAK_JWK_SET_URI` override these defaults for each
application through Config Server. The issuer must match the token exactly.
A container deployment may use an internal JWKS URL and a browser-visible issuer;
this does not change issuer validation. Config/Discovery are platform services,
not end-user JWT resource servers.

Gateway uses `spring.security.oauth2.resourceserver.jwt`; servlet services use
`nexus.security.jwt` from `common-security`. Config includes all six business
services (user, catalog, commerce, auction, fulfillment, notification). All six
business services depend on the existing `common-security` module, so its servlet
resource-server filter validates protected requests even when Gateway is bypassed.

## Token and authorization contract

Access tokens contain `sub` (the user UUID), `iss`, `aud`, `iat`, `exp` and flat
string arrays `roles` and `privileges`. Keycloak realm/client mappers in #236
supply these existing application claims. Additional Keycloak claims such as
`azp`, `scope` and `realm_access` do not grant application privileges.

- `roles: ["SELLER"]` becomes `ROLE_SELLER`.
- `privileges: ["PRODUCT.CREATE"]` becomes `PRODUCT.CREATE`, without a prefix.
- Authorization retains exact SRS privilege names and existing public routes.
- Gateway forwards the bearer unchanged; services derive identity from the
  validated JWT, never `X-User-Id`, `X-Role` or `X-Privileges`.

Missing/malformed/expired tokens, wrong issuer, missing/wrong audience, invalid
signature and untrusted keys return **401**. A valid token lacking the required
privilege returns **403**. Public routes remain public without a token; an invalid
bearer is not converted into anonymous access.

## Migration and compatibility

The original ADR's `iss=user-service`, user-service signing/private-key ownership,
local-public-key resource-server validation and
`http://localhost:8081/.well-known/jwks.json` trust model are **obsolete**, not
alternate supported trust paths. Legacy user-service-signed tokens are rejected,
even if their claims are changed to imitate Keycloak. Re-authentication through
Keycloak is required; there is no dual-issuer fallback.

Issue #237 changes verification only. Historical `nexus.iam.jwt` issuance settings
and public login/register/JWKS routes remain temporarily for the separate #238
migration. Those settings do not configure a resource server and do not imply
legacy tokens remain trusted. #238 owns removal/migration of password handling,
legacy issuance and user-service authentication integration. Keycloak password,
refresh-token, logout and brute-force policies are owned by #236/#238, not by
resource servers. The former BCrypt-12 application login SLA is historical
context, not a requirement to restore user-service password verification.

Merge/deploy the realm and mappers from #236 before exercising #237. Deploy
verification and client-login migration together to avoid an intentional window
where legacy-issued tokens no longer authenticate.

## Keys and operations

Keycloak exclusively owns signing private keys; never put private PEMs, passwords
or client secrets in Git or Config Server. Resource servers fetch only public
JWKS, cache keys and verify JWTs locally. For normal rotation publish old and new
realm keys during the active-token lifetime plus clock skew, then retire the old
key. Emergency key removal intentionally invalidates affected tokens after cache
refresh. Operational revocation and service-to-service credentials are separate
concerns.

## Verification

Automated config contracts cover all six business services, Gateway defaults,
deployment overrides and the distinction from platform/legacy issuance settings.
Gateway and common-security tests use Keycloak-shaped RS256 tokens, require the
realm issuer and audience, reject legacy keys, and preserve flat authorities.

The live acceptance harness in `docs/security-keycloak/test-token-trust.py` starts
isolated Keycloak/Postgres and real Config Server, Catalog and Gateway processes.
It authenticates the seeded seller via authorization code + PKCE, creates a
product through Gateway (**201**, persisted by Catalog), and checks legacy-key
and Keycloak-without-audience rejection (**401**) at both Gateway and Catalog.
See its adjacent README for prerequisites and evidence. No mocked business
upstream can satisfy this acceptance check.
