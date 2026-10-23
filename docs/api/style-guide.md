# API style guide

Status: **Proposed for #248 review; approval and FE cutover date pending**.
Decisions and scope are recorded in [ADR-008](../adr/ADR-008-api-contract.md).
The three [OpenAPI files](openapi/) describe delivered behavior; the older #88
commerce/notification drafts describe future contracts and require migration
before implementation.

## URLs, methods and access

Use `/api/v1` and plural resource nouns. Preserve delivered methods: category
partial updates are PATCH; product and SKU replacement requests are PUT. Creates
use 201, reads/updates use 200, bodyless deletes use 204. Do not silently change a
delivered method or success status to make an envelope easier to implement.

UUID resource identifiers use the OpenAPI `uuid` format. Check JWT and exact SRS
privileges at the configured boundary, then enforce downstream ownership and
additional privileges. `ADMIN` only bypasses a check where the implementation
explicitly allows it. `x-required-privileges` is an ALL-of requirement;
`x-required-roles` and `x-ownership` describe additional service rules.
These extensions document policy; they do not configure security.

Public endpoints declare `security: []`; a supplied invalid token may still be
rejected. Protected operations declare HTTP bearer JWT authentication. Do not add
an endpoint without updating gateway permissions and security configuration.

## Successful JSON

Every application controller returns `ApiResponse<T>`, optionally inside
`ResponseEntity<ApiResponse<T>>` when headers/status must be set. Wrap exactly
once, in the controller boundary. Never return a bare DTO, a bare array, or a
Spring `Page`. Services may return DTOs or `Page<T>` internally.

```json
{"data":{"id":"68000000-0000-4000-8000-000000000002"}}
```

Lists have arrays in `data`; empty lists have `data: []`. All list operations have
`page`, including category tree and a product's SKU list. Paged responses have:

```json
{"data":[],"page":{"number":0,"size":10,"totalElements":0,"totalPages":0}}
```

The top-level `page` is omitted for a single-resource response. It is never nested
inside `data`. Indices are zero-based. Category tree and product SKUs keep their
complete-list retrieval with one logical page: `number=0`, `size=max(1,count)`,
`totalElements=count`, `totalPages=count === 0 ? 0 : 1`. Tree totals count roots,
not nested children. An empty complete list has `size=1` and both totals 0; no
request pagination parameters are introduced. Preserve existing defaults and limits until a
separate reviewed change: users size 20 with maximum 100; products size 10 and
positive size without a configured maximum. A page beyond the end returns 200
with an empty array and the actual totals.

A 204 has no `Content-Type` promise and no response schema or body. FE checks
`response.status === 204` before `response.json()`. JWKS, Actuator and Swagger
retain their protocol formats. Do not put infrastructure or standard protocol
responses into an application envelope.

Use existing DTO property names and casing. Enum values retain uppercase wire
values; dates use `YYYY-MM-DD`, timestamps use an offset/UTC ISO-8601 string.
Delivered catalog/cart monetary fields currently serialize JSON numbers from
`BigDecimal`; do not apply the #88 future draft's decimal-string convention to
these fields without a coordinated contract change.

## Errors

Use HTTP 4xx/5xx and `application/problem+json`; never return HTTP 200 with an
error flag. Required fields are `type`, `title`, `status`, `detail`, `instance`
and symbolic `code`. `traceId` is optional when tracing is unavailable; FE may
display it for support. Do not manufacture trace identifiers at services or gateway.

```json
{
  "type":"urn:problem:validation-error",
  "title":"Bad Request",
  "status":400,
  "detail":"The request data is invalid",
  "instance":"/api/v1/auth/register",
  "code":"VALIDATION_FAILED",
  "errors":[
    {"field":"email","code":"VALIDATION_NOT_BLANK","detail":"Email is required"},
    {"field":"phone","code":"VALIDATION_NOT_BLANK","detail":"Phone is required"}
  ]
}
```

List every failed field, including query/model binding failures. A field entry
contains `field`, a constraint `code` such as `VALIDATION_NOT_BLANK`,
`VALIDATION_EMAIL` or `VALIDATION_SIZE`, and
human-readable `detail`. Object-level failures may use their object name.
Invalid JSON or an unparseable enum/UUID body is `INVALID_REQUEST_BODY`; parameter
conversion is `INVALID_PARAMETER`; privilege denial is `FORBIDDEN`; unexpected
failures use `INTERNAL_ERROR` with a generic detail.

Implement business identifiers in the owning service's `ErrorCode` enum. Codes
are immutable machine identifiers (`USER_*`, `CATALOG_*`, `COMMERCE_*`); status
stays numeric and English messages may change. For example, a duplicate email
uses 409 `USER_EMAIL_ALREADY_USED`. Include each reachable code under the
operation's corresponding status in OpenAPI; add an example when adding a code.
Do not copy domain codes into common-core or put exception class names, secret
values or stack traces on the wire.

401 security failures include `WWW-Authenticate: Bearer`; a domain login failure
is documented separately and does not imply that the Bearer challenge is always
present. Rate limiting uses 429 and preserves `Retry-After` when provided by the
gateway limiter. Retrying a non-idempotent POST still requires caller care.

## Documentation and verification

For every delivered operation maintain a unique `operationId`, tag, concrete
request constraints, success schema/example, all implemented error statuses and
codes, bearer/public metadata, required privileges/roles and ownership rules.
Return no schema or example for 204. The documented server is the local gateway
at port 8080; direct service ports are for debugging and may have different
gateway-only access controls.

From `be-project-nexus` run:

```powershell
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/user-service.yaml docs/api/openapi/catalog-service.yaml docs/api/openapi/commerce-service.yaml
```

Redocly uses `redocly.yaml`. Only absent project-license metadata and local server
URL advisories are disabled; no project license or public production URL is
invented. Endpoint inventory verification compares these specs with the six
controllers, normalizing only path-variable names. Service tests and ArchUnit
cover runtime serialization and architecture; lint cannot replace those tests.
The recorded execution evidence is in [verification-248.md](verification-248.md).

FE handoff must update `lib/api-client.ts`, authentication/profile/product/cart
parsers and pagination together. Record real BE/FE approval and the agreed merge
date in the issue/MR before release. This guide records the required handoff;
it does not claim that a discussion or deployment has already occurred.

The checked-in FE still expects the previous payloads. The coordinated FE change
must include these concrete parser updates:

| FE area | Required change on the cutover day |
| --- | --- |
| `lib/api-client.ts` success handling | Read the resource from the wire body's `data`; retain sibling `page` for list consumers; handle 204 before JSON parsing. |
| `lib/api-client.ts` error handling | Preserve top-level `code`; validate field entries as `{field, code, detail}` and display `detail` instead of the previous field message property. |
| `types/common/response/type.ts` | Represent `PageInfo.number/size/totalElements/totalPages` and the new error fields. |
| Auth, profile, catalog and cart parsers | Consume the shared envelope once; remove endpoint-specific assumptions about bare DTOs, bare arrays and nested page content. |

Before release, the BE and FE owners record ADR/style-guide approval and an agreed
merge date in #248 or its MR, run login/profile/category/SKU/cart and empty-list
checks together, and deploy the compatible changes in that agreed window.
