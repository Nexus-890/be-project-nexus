# Gateway permissions

The delivered JSON response/error contract introduced by #248 is documented in
[ADR-008](../adr/ADR-008-api-contract.md), the [style guide](style-guide.md), and
[`openapi/`](openapi/). Its 24 delivered application operations use `{data,page?}`
and symbolic Problem Details `code` values. The older **#88 draft** below retains
its original scope and historical contract; its former page/error-shape text does
not override #248 for already delivered endpoints. Access-policy rows remain the
source of truth for gateway privilege registration.

This document contains the active gateway policy table and the #88 and #124
API-shape draft contracts below. A draft contract does not expose a runtime endpoint. Every
issue enabling or changing a runtime endpoint must update this document and the endpoint policy
in `config-service/src/main/resources/config/api-gateway.yml` in the same merge
request. `SecurityConfig.java` binds `nexus.security.public-endpoints` and
`nexus.security.protected-endpoints` and applies their method/path rules; it has
no hardcoded endpoint list. Update `SecurityConfig.java` in that MR when matcher
or authorization behavior changes. Owning issues identify endpoint contracts;
listed policies may precede the merge of their service implementations.

`Public` means no bearer token is required; a supplied invalid bearer token is
still rejected. Listed SRS 2.7 privileges must appear verbatim in the JWT
`privileges` claim, with no `ROLE_` or `SCOPE_` prefix. Roles use `ROLE_`.
Downstream services validate JWTs again and enforce ownership/business rules
independently, as required by ADR-006.

| Path | Method | Public or required privilege | Owning issue                                            |
|---|---|---|---------------------------------------------------------|
| `/api/v1/auth/register` | POST | Public | #74 — User registration with a default role             |
| `/api/v1/products` | GET | Public | #99 — Product lists                                     |
| `/api/v1/products/{id}` | GET | Public | #83 — Product create, update and read                   |
| `/api/v1/categories/{id}` | GET | Public | #80 — Category create, update and read                  |
| `/api/v1/categories/tree` | GET | Public | #81 — Category tree endpoint                            |
| `/api/v1/products/{id}/skus` | GET | Public | #100 — SKU public reads and product visibility          |
| `/api/v1/skus/{id}` | GET | Public | #86 / #100 — SKU reads and product visibility           |
| `/api/v1/users/me` | GET | PROFILE.VIEW | #75 — Own profile: read and update                      |
| `/api/v1/users/me` | PUT | PROFILE.UPDATE | #75 — Own profile: read and update                      |
| `/api/v1/users` | GET | USER.LIST | #76 — Admin user list and user by id                    |
| `/api/v1/products` | POST | PRODUCT.CREATE | #83 — Product create, update and read                   |
| `/api/v1/products/mine` | GET | PRODUCT.LIST | #99 — Product lists                                     |
| `/api/v1/products/{id}` | PUT | PRODUCT.UPDATE | #83 — Product create, update and read                   |
| `/api/v1/products/{id}/status` | PATCH | PRODUCT.UPDATE | #84 — Product status workflow                           |
| `/api/v1/products/{id}` | DELETE | PRODUCT.DELETE | #85 — Product soft delete                               |
| `/api/v1/categories` | POST | CATEGORY.CREATE | #80 — Category create, update and read                  |
| `/api/v1/categories/{id}` | PATCH | CATEGORY.UPDATE | #80 — Category create, update and read                  |
| `/api/v1/categories/{id}` | DELETE | CATEGORY.DELETE | #82 — Category delete guard                             |
| `/api/v1/products/{id}/skus` | POST | PRODUCT.UPDATE | #86 — SKU create, update and read                       |
| `/api/v1/products/{id}/images` | POST | PRODUCT.UPDATE | #122 — Product image upload; service requires SELLER and product ownership |
| `/api/v1/products/{id}/images/{imageId}` | DELETE | PRODUCT.UPDATE | #123 — Delete an image; service requires SELLER and product ownership |
| `/api/v1/products/{id}/images/order` | PUT | PRODUCT.UPDATE | #123 — Reorder images; service requires SELLER and product ownership |
| `/api/v1/skus/{id}` | PUT | PRODUCT.UPDATE | #86 — SKU create, update and read                       |
| `/api/v1/carts/me` | GET | CART.VIEW | #110 — Cart: get-or-create and add item                 |
| `/api/v1/carts/me/items` | POST | CART.UPDATE | #110 — Cart: get-or-create and add item                 |
| `/api/v1/carts/me/items/{id}` | PUT | CART.UPDATE | #111 — Cart: update and remove item                     |
| `/api/v1/carts/me/items/{id}` | DELETE | CART.REMOVE_ITEM | #111 — Cart: update and remove item                     |
| `/api/v1/notifications` | GET | NOTIFICATION.VIEW | #108 — Notification list and unread count |
| `/api/v1/notifications/unread-count` | GET | NOTIFICATION.VIEW | #108 — Notification list and unread count |
| `/api/v1/notifications/{id}/read` | PATCH | NOTIFICATION.VIEW | #109 — Mark own notification as read |
| `/api/v1/notifications/read-all` | PATCH | NOTIFICATION.VIEW | #109 — Mark all own notifications as read |
| `/api/v1/orders` | POST | ORDER.CREATE | #113 — Order creation from a cart; service also requires CHECKOUT.START |
| `/api/v1/payments` | POST | CHECKOUT.START | #121 — Buyer reads own order group's immutable payment session |
| `/api/v1/orders/{id}/history` | GET | ORDER.VIEW | #116 — Order state machine and status history |
| `/api/v1/orders` | GET | ORDER.LIST | #117 — Order read endpoints; the caller's own orders only |
| `/api/v1/orders/manage` | GET | ORDER.LIST | #190 — All orders; service additionally requires ADMIN or SUPPORT_STAFF |
| `/api/v1/orders/sales` | GET | ORDER.LIST | #190 — Seller orders; service additionally requires SELLER and JWT seller ownership |
| `/api/v1/orders/{id}` | GET | ORDER.VIEW | #117 — Order read endpoints; the owner or ADMIN |
| `/api/v1/orders/{id}/cancel` | POST | ORDER.CANCEL | #189 — Order cancellation; the buyer, ADMIN or SUPPORT_STAFF |
| `/api/v1/carts/me/pricing` | GET | CART.VIEW | CRT-05 — Read-only cart pricing |
| `/api/v1/carts/me/validate` | POST | CART.VIEW | CRT-06 — Read-only cart validation |
| `/api/v1/checkout/preview` | POST | CHECKOUT.START | ORD-02 — Read-only checkout preview |
| `/api/v1/event-logs` | GET | EVENT_LOG.VIEW | #192 — Event log consumer and audit log |
| `/api/v1/event-logs/{id}` | GET | EVENT_LOG.VIEW_DETAIL | #192 — Event log consumer and audit log |
| `/api/v1/audit-logs` | GET | AUDIT_LOG.VIEW | #192 — Event log consumer and audit log |
| Any other path | Any other method/path pair | Token required; add the SRS privilege before exposing a new endpoint | Gateway JWT filter and permit list; future owning issue |

PAY-01 is owned by payment-service at `POST /api/v1/payments` (#121), with
the Buyer actor and `CHECKOUT.START` privilege. Its explicit issue contract is
HTTP 201 with `ApiResponse<PaymentInitResponse>` and numeric whole-VND `data.amount`.
See `openapi/payment-service.yaml`. The old per-order payment operation in
the #88 commerce mock is superseded; it is not a runtime payment endpoint.

`GET /api/v1/users/{id}` (#76) has no row on purpose: a caller may read their own
record without `USER.VIEW`, which the gateway cannot decide. Any valid token passes
the gateway and user-service allows the caller's own id or `USER.VIEW`.

## Order management and sales (#190)

Both order-list operations require `ORDER.LIST` at the gateway and service. They
also enforce the role restrictions defined by ORD-10 and ORD-11 downstream:
`/api/v1/orders/manage` requires either `ADMIN` or `SUPPORT_STAFF`, while
`/api/v1/orders/sales` requires `SELLER`. A `BUYER` with `ORDER.LIST` still receives
403 on both paths. The gateway's single-privilege rows do not replace those role
checks. `/api/v1/orders` retains its existing caller-as-buyer scope for every role.

The management list reads all orders when no filter is supplied. Optional
`userId` (buyer), `sellerId`, `status`, `orderType`, `from` and `to` filters combine
with AND. The sales list supports `status`, `from` and `to`; its `seller_id` is
always the validated JWT subject, so a caller cannot select another seller.
Both lists use inclusive `createdAt` bounds expressed as ISO-8601 timestamps with
an offset. Encode a positive offset's `+` as `%2B` in a URL, or use `Z` for UTC.
`from > to` returns 400 `COMMERCE_INVALID_ORDER_FILTER`. Paging is zero-based with
`page=0`, `size=10`, a size limit of 1–100, and newest-first ordering by
`createdAt DESC, orderNumber DESC`. Invalid paging also returns
`COMMERCE_INVALID_ORDER_FILTER`; malformed UUID/enum/date parameters retain the
shared `INVALID_PARAMETER` Problem Details response. A page beyond the result set
returns 200 with empty `data` and the actual `page` totals.

## Matching and JWT contract

- Exactly seven method/path pairs are public. No public `/**`, method-less rule,
  or implicit public HEAD/OPTIONS, Swagger or Actuator exception. TRACE is rejected
  earlier by Spring Security's HTTP firewall with 400, never forwarded.
- `{id}` is a UUID. UUID-constrained matchers keep `/products/mine`,
  `/products/search`, `/products/discovery`, and future literal paths from
  inheriting public product-detail access. Category `tree` has its own public row.
- Category updates use PATCH in the current controller; the external endpoint
  catalogue says PUT. A later contract change must update both files.
- RS256 only; issuer `user-service`; audience includes `nexus-services`; `exp`
  is mandatory. Expiry and `nbf` retain Spring Security's 60-second clock skew.
- JWT segments must use canonical unpadded base64url. A changed last character
  is rejected even when only unused encoding bits, not signature bytes, change.
- Config Server's `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` points
  directly at `http://localhost:8081/.well-known/jwks.json`. Override
  `USER_SERVICE_JWKS_URI` for the deployment network. The reactive decoder caches
  public keys; validation does not call the login API or loop through gateway.
- Missing/invalid tokens return 401 `application/problem+json` with `type`,
  `title`, `status`, `detail`, `instance`, `code: UNAUTHENTICATED`, and
  `WWW-Authenticate: Bearer`. Missing privileges return 403 with `code: FORBIDDEN`
  in the same JSON format. Details never echo tokens or decoder exceptions.
  `traceId` uses the active tracing span and is omitted when tracing is unavailable.
- No security session is created. Existing routes forward the bearer header;
  authenticated unknown paths keep the existing route/fallback behavior.

## Verification

From `be-project-nexus/api-gateway`, run `./mvnw verify` using Java 21.
Tests use real RS256 tokens and a local HTTP JWKS fixture, not a mocked decoder.
A real gateway HTTP route forwards accepted requests to an instrumented HTTP
service; rejected requests leave its request log empty. Tests cover all seven
public pairs, other methods, listed privileges, invalid tokens, and fallback.
The permission test binds the same YAML properties consumed by `SecurityConfig`
and compares all explicit rows (including privileges) in both directions. It also
independently checks the seven public pairs. Newly public undocumented pairs,
stale document rows, duplicates, and missing owners fail.
Configuration-binding tests verify that changed paths/privileges are honored;
invalid methods, missing paths/privileges, and duplicate policies are rejected.
This follows Vinh's configuration-driven public endpoint pattern in commits
`131e7f3` (`NexusSecurityProperties` and `SecurityAutoConfiguration`) and `3fce9d5`
(`user-service.yml`), while keeping the gateway reactive and its public list exact.

Fixtures prove gateway behavior, not the existence of unmerged service endpoints.
Live register/login/product/profile acceptance also requires their owning issues,
running services, test data, and matching downstream permit/privilege rules.

References: [ADR-006](../adr/ADR-006-service-authentication-RS256.md),
[SRS 2.7](https://docs.google.com/document/d/1nw7_WwZoWhjgA1WiZJzu37ps_MisI5Ls/edit),
[endpoint catalogue](https://docs.google.com/spreadsheets/d/1KIJsr87F-6TWieBbie9CVzgCBTPsM4Zk/edit),
[Spring Security reactive JWT](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).


## API shapes draft (#88) — commerce and notifications

These **14 method/path contracts are fixed for callers**, while DTO field names
remain drafts (`0.1.0-draft`). This section is the permission handoff for the
[commerce contract](commerce-service.yaml) and
[notification contract](notification-service.yaml). The active table above
includes the two GET notification endpoints implemented by #108. Other draft
rows do not assert that their runtime endpoints exist.
Each method/path appears exactly once in the draft table; none is public.

The gateway dependency is present in the branch history: merge commit `f67e7a3`
merged `gateway-jwt-permit-list` into `develop`. Its current configuration supports
**one privilege per policy** (`SecurityConfig` calls `hasAuthority` for one string)
and falls back to authentication for unlisted routes. The columns below separate
the proposed gateway registration from the complete downstream requirement.
Endpoint-owning implementation MRs must register the gateway column in
`config-service/src/main/resources/config/api-gateway.yml` and enforce **all**
service privileges and ownership checks before exposing these APIs. This draft
changes neither that configuration nor runtime authorization.

**Privilege-first:** when SRS defines a privilege, check privileges, not an extra
hard-coded role allowlist. Actor roles below are SRS reference metadata, not a
second access gate; roles can be assigned privileges dynamically. `ADMIN` does not
bypass a required privilege. Only ORD-06 has no SRS privilege: its service requires
`ADMIN` (role-only); the current gateway can only require a valid token for it.
Do not invent `ORDER.UPDATE` or use authentication alone as the service policy.

In the YAMLs, `x-actor-roles` records reference actors, `x-required-privileges`
records the service ALL-of requirement, and `x-gateway-privilege` records one
primary privilege for gateway registration. `x-required-roles` appears only on
the role-only status endpoint. These documentation extensions do not configure
Spring Security or make Prism enforce authorization. CRT-01 uses `CART.VIEW` at
the gateway and also requires `CART.CREATE` downstream; ORD-01 uses `ORDER.CREATE`
at the gateway and also requires `CHECKOUT.START` downstream. Neither privilege
from the catalogue is dropped, combined into a new string, or treated as OR.

Roles are canonical JWT `roles` codes mapped to `ROLE_<code>`; privileges are
exact JWT `privileges` strings without prefixes. Ownership checks use validated
JWT `sub`; do not accept a caller-controlled `userId` for another account.
Preserve the seven public method/path pairs above; none of these 14 paths is public.

| Method | Path | Actor roles (reference; ADMIN gate only for ORD-06) | Gateway privilege | Required service privileges (all) | Ownership / business restriction | Owning issue |
|---|---|---|---|---|---|---|
| GET | `/api/v1/carts/me` | BUYER | CART.VIEW | CART.VIEW AND CART.CREATE | Own cart; get-or-create can create a resource | #110 |
| POST | `/api/v1/carts/me/items` | BUYER | CART.UPDATE | CART.UPDATE | Own active cart | #110 |
| PUT | `/api/v1/carts/me/items/{id}` | BUYER | CART.UPDATE | CART.UPDATE | Own cart item; inaccessible item returns 404 | #111 |
| DELETE | `/api/v1/carts/me/items/{id}` | BUYER | CART.REMOVE_ITEM | CART.REMOVE_ITEM | Own cart item; inaccessible item returns 404 | #111 |
| POST | `/api/v1/orders` | BUYER | ORDER.CREATE | CHECKOUT.START AND ORDER.CREATE | Own cart; caller-scoped idempotency key | #113, #114, #115 |
| GET | `/api/v1/orders` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | ORDER.LIST | ORDER.LIST | Own orders only, including for ADMIN; not a global admin list | #117 |
| GET | `/api/v1/orders/{id}` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | ORDER.VIEW | ORDER.VIEW | Owner or ADMIN; other caller returns 403 | #117 |
| GET | `/api/v1/orders/{id}/history` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | ORDER.VIEW | ORDER.VIEW | Owner or ADMIN; other caller returns 403 | #116 |
| PATCH | `/api/v1/orders/{id}/status` | ADMIN | token only | none (role-only) | Service requires ADMIN; moves follow the order transition table, other moves return 409; audit actor and reason | #116 |
| POST | `/api/v1/payments` | BUYER | CHECKOUT.START | CHECKOUT.START | Own PENDING group/session before dueAt; repeated call returns the same instructions | #120, #121 |
| GET | `/api/v1/notifications` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | NOTIFICATION.VIEW | Own notifications only, including for ADMIN | #108 |
| GET | `/api/v1/notifications/unread-count` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | NOTIFICATION.VIEW | Count own unread notifications only | #108 |
| PATCH | `/api/v1/notifications/{id}/read` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | NOTIFICATION.VIEW | Own notification only; other caller returns 403 | #109 |
| PATCH | `/api/v1/notifications/read-all` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | NOTIFICATION.VIEW | Update own unread notifications only | #109 |

System-generated status transitions run through internal service or consumer
boundaries. They do not grant a buyer JWT access to the ADMIN HTTP endpoint.
Owning MRs must test gateway token/primary-privilege failures (401/403), service
rejection when either required privilege is missing on CRT-01/ORD-01, and ownership.
For ORD-06, test missing ADMIN at the service (403). Do not reject a caller solely
for a different role when its effective privileges satisfy a privilege-backed path.
Prism cannot prove any of these runtime security properties.

### Scope and draft decisions

- Source: [SRS 3.2 and 3.4](https://docs.google.com/document/d/1nw7_WwZoWhjgA1WiZJzu37ps_MisI5Ls/edit)
  and [endpoint catalogue](https://docs.google.com/spreadsheets/d/1KIJsr87F-6TWieBbie9CVzgCBTPsM4Zk/edit),
  selected by #88 scope and the two requested services, not obsolete sprint labels.
  Catalogue IDs:
  CRT-01..04, ORD-01/03..06, PAY-01 and NTF-01..04.
- The legacy #88 draft excludes standalone pricing/validation/checkout preview;
  CRT-05, CRT-06 and ORD-02 are now delivered in `openapi/commerce-service.yaml`
  and registered in the active policy table above. Each reads only the JWT subject's
  active cart, including for ADMIN. Preview requires CHECKOUT.START alone;
  pricing/validation require CART.VIEW alone, without CART.CREATE.
- Not included in the #88 draft: guest carts,
  buyer cancellation/refunds, notification preferences or push subscriptions.
  The Sepay webhook is outside #88, not a new public path in this draft. Cart expiry,
  outbox relay and notification consumers are jobs/consumers, not HTTP endpoints.
- **Catalogue status codes:** CRT-04, NTF-03 and NTF-04 return **204 No Content**,
  including idempotent mark-read retries. No response schema, example or body is
  declared for 204. FE must not call `response.json()` on these responses; refetch
  the cart or notifications/unread count instead. Other success responses retain
  explicit nonempty JSON examples, including empty-list/count envelopes. This
  restores the catalogue rather than introducing an unapproved 200 contract.
  Issue #88's original "every path, no empty body" criterion is incompatible with
  these 204 operations and needs an explicit exception; validation here does not
  imply that the issue acceptance text has already been updated.
- **Money:** all 11 commerce/payment monetary fields use JSON **strings**, with
  pattern `^\d+\.\d{2}$`, for example `"150000.50"` or `"0.00"`. This follows
  #92's decimal-safe convention confirmed by review !40 and the frontend's
  [string-valued price formatter](../../../fe-project-nexus/lib/format-price.ts). SQL `numeric(19,2)` describes storage, not a JSON
  number requirement. Preserve both fractional digits on the wire; never convert
  these values to JavaScript `Number` for authoritative money calculations.
  Shipping, tax, discounts and totals are server supplied.
  The updated #121 endpoint `POST /api/v1/payments` follows its explicit response
  example: `amount` is a numeric whole-VND value, limited to 13 digits. It does
  not change the older #88 mock monetary fields described above.
- **Idempotency:** order creation requires UUID `Idempotency-Key`; a new order is
  201, an identical caller/payload retry is 200 with the same order, and a changed
  payload with the same key is 409. A key belonging to another caller returns
  400 and never discloses that caller's order. Payment initiation repeats the same
  201 instructions; marking an already-read notification preserves `readAt`.
- **Pagination:** zero-based `page`, `size` defaults to 10 (1..100), matching
  [product lists](../../catalog-service/src/main/java/com/group5/catalogservice/product/controller/ProductController.java).
  List envelopes use `{content, page, size, totalElements, totalPages}`. Order
  filtering supports `status`, not out-of-scope `from`/`to` filters.

### Shared error contract (no new error-code system)

The OpenAPI `Problem` / `ValidationError` schemas describe the existing shared
handlers; they do not implement another handler or change `ErrorCode`. Each YAML
keeps the same schemas locally so Prism can load either file independently,
without introducing a fourth output file.

Authoritative implementation sources:

- [ErrorCode](../../lib/common-core/src/main/java/com/group5/common_core/constant/ErrorCode.java)
  and [DomainException](../../lib/common-core/src/main/java/com/group5/common_core/exception/DomainException.java).
- [GlobalExceptionHandler](../../lib/common-web/src/main/java/com/group5/common_web/exception/GlobalExceptionHandler.java).
- [SecurityProblemAdvice](../../lib/common-security/src/main/java/com/group5/common_security/SecurityProblemAdvice.java).
- [Gateway SecurityConfig](../../api-gateway/src/main/java/com/group5/apigateway/config/SecurityConfig.java).

Contract details:

- `ErrorCode.getCode()` supplies the numeric HTTP status; the handler does **not**
  emit a symbolic top-level `code`. Existing `DATA_INVALID`, `INVALID_QUANTITY`,
  `INVALID_STATUS` and `NOT_FOUND` messages are reused where applicable.
- Domain exceptions use `type: urn:problem:domain-error`. Malformed JSON,
  bean-validation failures and parameter conversion errors have the handler's
  distinct `type`, `title` and `detail` fixtures. Validation `errors` contains
  `{field, message}` entries, deduplicated by field, not SKU/stock/price metadata.
- Service `traceId` is optional because no active tracing span means no property.
  Gateway security rejections always include a correlation ID. Fixtures expose
  both gateway `https://nexus/errors/...` and service `urn:problem:...` security
  types, with their existing messages. Both 401 variants include a Bearer challenge.
- Checkout price/stock/idempotency conflicts, illegal transitions and non-PENDING
  payment requests remain **draft business scenarios**, not new `ErrorCode` enum
  values. Their 409 (and dependency-unavailable 503) fixtures use the existing
  `DomainException(int, String)` envelope; owning implementations must select the
  corresponding status/detail. No symbolic conflict code or unsupported structured
  `errors` extension is promised. After a price conflict, refetch the cart before
  reconfirming; clients cannot obtain new prices from this error body.
- Unexpected failures use the shared 500 `urn:problem:internal-server-error`
  response and its generic detail, without exposing exception internals.

### Validate and run the mocks

Run these commands from `be-project-nexus`. Node tooling is invoked with `npx`;
no project dependency, lockfile, generated client or backend endpoint is added.

```bash
npx --yes @redocly/cli@2.63.0 lint --extends recommended \
  docs/api/commerce-service.yaml docs/api/notification-service.yaml
```

Localhost server and absent-license lint warnings are intentional: these are
local mock contracts and no project license is being invented. Schema and example
validation must have no errors or invalid-example warnings.

### Frontend handoff

The runnable FE guide, both mock URLs, request/header examples and MR comment
template are in [commerce-service.yaml](commerce-service.yaml), under
`info.description` → **Frontend quick start**. Notification uses the same guide.
This keeps the gateway document focused on access policy without adding a fourth
output file. The guide uses branch `fsa_ANNT_25/feature/api-shapes`.

### Verification scope

Validate both schemas, each declared JSON example and live Prism response. For
CRT-04/NTF-03/NTF-04, assert status 204 and an empty body; do not invent a body to
meet the old #88 wording. Also test all 14 default successes without `Prefer`.
The active gateway permission test proves the existing policy table is preserved,
not that draft service/gateway authorization is implemented. Temporary tooling
stays outside the repository; only these three documents are delivered.

Validation recorded after review !40 fixes:

- Swagger Parser 12.1.0 accepts both OpenAPI documents. Redocly 2.63.0 recommended
  lint has no errors or invalid-example warnings; the four advisory warnings are
  missing license metadata and localhost server URLs, two per file.
- Prism 5.14.2: 153 fixture/invalid requests plus 14 default successes without
  `Prefer` (167 requests), with 322 schema/example checks. Each declared JSON body
  matches its example and schema; all three 204 successes have zero body bytes.
- All 14 permission rows match the YAML actor/service/gateway metadata uniquely.
  The 11 monetary schemas and 36 example amounts pass decimal-string checks;
  numbers and malformed decimal strings are rejected. Page-size defaults are 10.
- The executable FE examples pass five additional live requests, including
  bodyless mark-all returning 204 and the subsequent GET unread count.
- Shared error schemas are unchanged. Both accept 30 serialized shared-handler
  fixtures and reject invented symbolic codes / SKU-shaped validation errors
  (64 compatibility checks). No new `ErrorCode` enum or runtime handler is added.
- Java 21 `./mvnw -Dtest=GatewayPermissionsTest test` in `api-gateway` passes:
  four tests, zero failures/errors. The active permission table is unchanged.

### Acceptance evidence (issue #88 / MR !40)

Issue #88 and the actual FE comment in MR !40 are the source of truth for handoff
and confirmation. Do not copy checked/unchecked progress into this document or
infer confirmation from committed instructions. If those boxes were checked
without evidence, clear them on the issue until the guide has actually been sent
and the FE developer has commented. Updating these documents does not itself
update the issue or post an MR comment.

Retain the catalogue 204 responses and record an exception to #88's original
nonempty-body criterion. Implementation MRs still need to register the gateway
privilege and enforce the full downstream policy before deployment; these drafts
and Prism cannot demonstrate runtime authorization.

## API shapes draft (#124) — inventory and auction

These **26 method/path contracts** are drafts (`0.1.0-draft`) for the
[inventory contract](inventory-service.yaml) and the
[auction contract](auction-service.yaml). A draft does not expose a runtime
endpoint: `RouteConfig` has no inventory or auction route yet, and this section
changes neither `api-gateway.yml` nor `SecurityConfig`. Each owning issue registers
its row, enforces the service rule and copies its operations into `openapi/`.
Each method/path appears exactly once below, with a role, the word `public`, or
`system` for the two paths the gateway never routes.

Gateway privilege `token only` means the gateway requires a valid token and the
service checks the role, as ORD-06 does above. SRS 2.7 defines no `INVENTORY.*` or
`WAREHOUSE.*` privilege, so inventory paths are role-only; do not invent one.
A path with several roles in `x-required-roles` accepts any one of them.

**inventory-service**

| Method | Path | Actor roles (or public) | Gateway privilege | Required service privileges (all) | Ownership / business restriction | Owning issue |
|---|---|---|---|---|---|---|
| POST | `/api/v1/inventory/intake` | ADMIN | token only | none (role-only) | Service requires ADMIN; writes one ledger row in the same transaction | #130 |
| GET | `/api/v1/inventory/{skuId}` | public | public | none (public) |  | #132 |
| GET | `/api/v1/inventory` | public | public | none (public) |  | #132 |
| POST | `/api/v1/inventory/reservations` | BUYER | token only | none (role-only) | Service requires BUYER; commerce-service forwards the buyer token | #134 |
| POST | `/api/v1/inventory/reservations/{id}/confirm` | system | not routed | none (service-to-service) | Called by the event consumers of #160 and #161; conditional on status HELD | #136 |
| POST | `/api/v1/inventory/reservations/{id}/release` | system | not routed | none (service-to-service) | Called by the event consumers of #160 and #161; conditional on status HELD | #136 |
| GET | `/api/v1/inventory/reservations` | ADMIN, SUPPORT_STAFF | token only | none (role-only) | Service requires ADMIN or SUPPORT_STAFF | #200 |
| GET | `/api/v1/inventory/balances` | ADMIN, SELLER | token only | none (role-only) | Service requires ADMIN, or SELLER for their own SKUs | #200 |
| POST | `/api/v1/inventory/adjustments` | ADMIN | token only | none (role-only) | Service requires ADMIN; writes one ledger row | #200 |
| GET | `/api/v1/inventory/movements` | ADMIN, SUPPORT_STAFF | token only | none (role-only) | Service requires ADMIN or SUPPORT_STAFF | #200 |
| POST | `/api/v1/warehouses` | ADMIN | token only | none (role-only) | Service requires ADMIN | #199 |
| GET | `/api/v1/warehouses` | ADMIN, SUPPORT_STAFF | token only | none (role-only) | Service requires ADMIN or SUPPORT_STAFF | #199 |
| GET | `/api/v1/warehouses/{id}` | ADMIN, SUPPORT_STAFF | token only | none (role-only) | Service requires ADMIN or SUPPORT_STAFF | #199 |
| PUT | `/api/v1/warehouses/{id}` | ADMIN | token only | none (role-only) | Service requires ADMIN | #199 |

**auction-service**

| Method | Path | Actor roles (or public) | Gateway privilege | Required service privileges (all) | Ownership / business restriction | Owning issue |
|---|---|---|---|---|---|---|
| POST | `/api/v1/auctions` | ADMIN, SELLER | AUCTION.CREATE | AUCTION.CREATE | Seller of the product; ADMIN on behalf of a seller | #140, #141, #142 |
| PUT | `/api/v1/auctions/{id}` | ADMIN, SELLER | AUCTION.UPDATE | AUCTION.UPDATE | Owner of the auction; frozen once it has started | #197 |
| POST | `/api/v1/auctions/{id}/schedule` | ADMIN, SELLER | AUCTION.UPDATE | AUCTION.UPDATE | Owner of the auction; only from CREATED | #144 |
| GET | `/api/v1/auctions/{id}` | public | public | none (public) |  | #143 |
| GET | `/api/v1/auctions` | public | public | none (public) |  | #143 |
| GET | `/api/v1/auctions/{id}/state` | public | public | none (public) |  | #168 |
| POST | `/api/v1/auctions/{id}/cancel` | ADMIN, SELLER | AUCTION.CANCEL | AUCTION.CANCEL | Owner of the auction; only while it has no valid bid | #197 |
| POST | `/api/v1/auctions/{id}/admin-cancel` | ADMIN | AUCTION.ADMIN_CANCEL | AUCTION.ADMIN_CANCEL | Any auction not yet settled or cancelled; audit actor and reason | #197 |
| GET | `/api/v1/auctions/mine` | SELLER | AUCTION.LIST | AUCTION.LIST | Own auctions only | #198 |
| POST | `/api/v1/auctions/{id}/bids` | BUYER | AUCTION.BID | AUCTION.BID | Not the auction's seller; caller-scoped idempotency key | #146, #147 |
| GET | `/api/v1/auctions/{id}/bids` | public | public | none (public) |  | #169 |
| GET | `/api/v1/bids/mine` | BUYER | AUCTION.VIEW_BID_HISTORY | AUCTION.VIEW_BID_HISTORY | Own bids only | #198 |

### Scope and draft decisions

- **Required role of the reservation path:** `POST /api/v1/inventory/reservations`
  requires `BUYER`. commerce-service sends it with the buyer's token; the frontend
  does not call it.
- **Availability is public:** `GET /api/v1/inventory/{skuId}` and
  `GET /api/v1/inventory` need no token and return only `skuId` and `available`.
- **`{skuId}` is not UUID-constrained by the gateway:** `matcherPath` constrains
  only a segment named `{id}`, and public matchers are registered first. A public
  row written as `/api/v1/inventory/{skuId}` would also match `/reservations`,
  `/balances` and `/movements`, making those GETs public at the gateway. #132 must
  register the row as `/api/v1/inventory/{id}`, or change `matcherPath`. The
  `{id}` rows for auctions do not have this problem.
- **Not routed:** the route #136 relies on must leave out
  `/api/v1/inventory/reservations/{id}/confirm` and `/release`, so that a call from
  outside returns 404.
- **Public auction reads:** the catalogue lists a privilege next to AUC-04, AUC-05,
  AUC-06 and BID-02 and also marks them public. Public wins: no token is needed, and
  the service does not check a privilege for an anonymous caller.
- **No bidder id in public reads:** `Auction` leaves out `leader_bidder_id` and
  `winner_id`, because AUC-04 and AUC-05 are public. AUC-06 and BID-02 return masked
  names only. `POST .../bids` returns `leaderBidderId` to the bidder who just led.
- **Shipment:** shipping moved to shipment-service (#241), so its paths are not in
  this draft.
- **Scope:** every inventory and auction path in the Sprint 2 issues, plus AUC-06
  (#168) and BID-02 (#169) from Sprint 3, so the auction room can be mocked too. The
  catalogue still marks AUC-02, AUC-07, AUC-08, AUC-10 and BID-03 after R1, but #197
  and #198 are in Sprint 2. AUC-09, AUC-11 and AUC-12 have no issue and are left out.
  `GET /internal/v1/inventory/reservations` (#209, Sprint 3) is not in this draft.
- **Movement types:** `movementType` and the `type` filter use the eight
  `inventory_movement_type` values plus `INTAKE` and `COMMIT`, which V6 adds (#131).
  #200 writes `ADJUSTMENT`. The schema document still names an `ADJUST` value that no
  issue writes.
- **Money:** a JSON number, per ADR-008. It differs from the #88 decimal strings.
- **Error codes:** the `AUCTION_*` and `INVENTORY_*` names under
  `x-business-error-codes` are proposals, except `AUCTION_BID_TOO_LOW` and
  `INVENTORY_INSUFFICIENT_STOCK`, which #146 and #134 name. Security failures keep the
  shared `UNAUTHENTICATED` and `FORBIDDEN`.
- **204:** INV-05 and INV-06 return 204 with no body, as the catalogue and #136 say.
  They are the exception to "returns example data on every path" in #124.

### Validate and run the mocks

Run these commands from `be-project-nexus`:

```bash
npx --yes @redocly/cli@2.63.0 lint docs/api/inventory-service.yaml docs/api/auction-service.yaml
npx --yes @stoplight/prism-cli@5.14.2 mock docs/api/inventory-service.yaml -p 4012
npx --yes @stoplight/prism-cli@5.14.2 mock docs/api/auction-service.yaml -p 4013
```

The runnable FE guide is in [inventory-service.yaml](inventory-service.yaml), under
`info.description`, **Frontend quick start**.

### Verification scope

- Redocly 2.63.0 with `redocly.yaml`: both files valid, no warnings.
- Prism 5.14.2, all 26 operations: the default response matches its example, every
  declared status and named error example is returned with the right media type, and
  the two 204 responses have no body. A protected path without a token returns 401, a
  public path without a token succeeds, 101 SKU ids return 400, and a bid without
  `Idempotency-Key` returns 400. `/inventory/reservations` and `/auctions/mine` reach
  their own operations, not `{skuId}` or `{id}`. 441 checks, 0 failures.

Prism does not check JWT signatures, roles, privileges or ownership, so none of this
proves runtime authorization. The FE confirmation is recorded on #124, not here.
