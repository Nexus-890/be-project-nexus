# #237 validation — 2026-11-03

The existing branch fsa_ANNT_25/feature/237-keycloak-tokens was updated by pulling
origin/develop at 352987ba (merge ed227d7). This replaces the October six-service
evidence: the current resource servers are user, catalog, commerce, auction,
inventory, notification, payment, shipment and rating, plus Gateway.
All nine business services depend on common-security; Config/Discovery are platform services.

## Automated checks

Java 21, Maven verify with -Duser.timezone=UTC; shared libraries installed first.

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| lib | 84 | 0 | 0 | 0 |
| config-service | 5 | 0 | 0 | 0 |
| api-gateway | 42 | 0 | 0 | 0 |
| catalog-service | 105 | 0 | 0 | 0 |
| user-service | 191 | 0 | 0 | 0 |
| auction-service | 4 | 0 | 0 | 0 |
| inventory-service | 5 | 0 | 0 | 0 |
| notification-service | 36 | 0 | 0 | 0 |
| payment-service | 122 | 0 | 0 | 2 |
| shipment-service | 0 | 0 | 0 | 0 |
| rating-service | 3 | 0 | 0 | 0 |
| commerce-service | 199 | 0 | 0 | 5 |

Total 796 discovered, 789 executed/passed, 7 skipped. All final builds succeeded.
Shipment currently has no test source; its verify checks compile/package. Config
contracts still check its issuer/JWKS/audience. Seven existing opt-in tests were
skipped: two captured payment webhooks and five Catalog HTTP simulations in commerce.
They are not Keycloak ACs. Offline helper compatibility:
1 pass; API contract inventory: 39 operations pass.

Initial notification JWT-context failures after the service split were fixed by
isolating its new NotificationRepository and IdempotentConsumer dependencies, as
well as its listener group. JWT decoding/signatures/filters remain real. The old
fulfillment context test now targets InventoryServiceApplication.

A pre-existing payment expiry test used DB now()-1s, while the service used the
JVM Clock. Docker was observed about 15s ahead. Test data now uses the injected
application Clock; production payment behavior is unchanged. Initial failed logs
are retained locally separately from the successful final logs.

## Real Keycloak acceptance

Realm fixture: #236 commit 874fbf8 exported into a separate directory. The realm is
not copied into #237; #236 must be integrated before deploying this verification
cutover. Four real JVMs run Config Server, Gateway, Catalog and legacy User with
isolated Keycloak 26.7.5/PostgreSQL18 and random loopback ports/credentials.

UTC run: 2026-11-03T09:39:38.017079+00:00 to 2026-11-03T09:42:19.821239+00:00; **PASSED**.

- Starting disposable project nexus-237-test-b0039eb4
- PASS: real seeded seller PKCE login; Keycloak RS256 signature and flat claims verified
- PASS: seller token -> real Gateway -> real Catalog POST products = 201; persisted seller matches JWT
- PASS: genuine Keycloak Buyer without PRODUCT.CREATE -> 403 at Gateway AND direct Catalog
- PASS: rejected legacy fixture was issued by real user-service login and has a valid signature under its own old key
- PASS: real legacy user-service login token -> 401 at Gateway AND direct Catalog
- PASS: legacy key with forged Keycloak issuer -> 401 at Gateway AND direct Catalog
- PASS: real Keycloak token without aud -> 401 at Gateway AND direct Catalog
- PASS: original valid seller token still reads own products = 200
- Removed isolated containers and terminated owned application processes

The successful product is queried in PostgreSQL and its seller UUID equals the
validated demo seller sub. Keycloak Buyer without PRODUCT.CREATE returns403.

The rejected old token is produced by real legacy user-service registration/login
using an ephemeral RSA key; its signature is verified against that public key
first. A second token has valid seller claims/forged Keycloak issuer signed with
the same old key. Both return401 at Gateway and direct Catalog. No operator's
existing private key is read.

The no-audience token is genuinely Keycloak-signed, obtained by removing only the
audience mapper inside the disposable realm and logging in again with PKCE.
Payload tampering cannot satisfy this check. The original valid seller token still
returns200 afterward, excluding service outage as the explanation for401.

## Reproduce and limits

See README for fixture/build setup. Run test-harness.py then test-token-trust.py
with --evidence-dir pointing to a new empty directory. Sanitized evidence.json and
summary.log contain actual HTTP statuses, Problem Details, persisted ID, selected
claims and exact source hashes; no raw bearer/refresh tokens/passwords are stored.
The run occurred before final commit; file hashes map it to the committed bytes.
Owned JVMs and isolated Compose containers/volumes were cleaned afterward.

Configuration covers Gateway plus nine services; shared security and actual
auction/inventory/notification contexts check servlet trust. Live business ACs
cover Gateway/Catalog, not every business endpoint of all nine services. Historical
user-service issuance settings remain until #238; no dual trusted issuer exists.
