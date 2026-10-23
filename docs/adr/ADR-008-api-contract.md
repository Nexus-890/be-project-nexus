# ADR-008: API response and error contract

- Status: **Proposed — implementation ready for review; approval pending**
- Date: 2026-10-23 (Asia/Saigon)
- Owning issue: #248
- Reviewers: @fsa_Chung, @fsa_An

## Context

Delivered user, catalog and cart APIs returned bare objects, bare arrays and two
different page DTOs. Errors used a shared business enum and did not expose a
stable symbolic identifier. This made frontend parsing depend on the endpoint.
No ADR-008 draft or style guide existed in this checkout, so this ADR records
the three decisions below for explicit review rather than claiming earlier approval.

The six controllers named in #248 contain **24 method/path operations**: Auth 2,
User 4, Category 5, Product 7, SKU 4 and Cart 2. The issue's figure of 25 differs
from the current implementation. JWKS is a protocol endpoint and is deliberately
excluded; adding it would break consumers of the standard JWK Set format.

## Decisions and the three open design topics

### 1. One success envelope, including arrays and pages

Every application 2xx JSON response uses `ApiResponse<T>(T data, PageInfo page)`.
`data` is always present; `page` is omitted for a single resource. A collection
is an array in `data`, including `[]` for an empty result. Every list operation
puts page metadata beside that array:

```json
{"data":[],"page":{"number":0,"size":10,"totalElements":0,"totalPages":0}}
```

`PageInfo` contains zero-based `number`, requested `size`, `totalElements` and
`totalPages`. Preserve existing endpoint defaults: users use size 20, products
use size 10. User size is limited to 100; catalog currently accepts any positive
size, so this change does not introduce an undocumented maximum. Complete lists
without request pagination (category tree and a product's SKUs) form one logical
page: `number=0`, `size=max(1,count)`, `totalElements=count`, and `totalPages=1`
unless empty, when it is 0. Tree count means root nodes; nested children are part
of their root representation. This preserves full-list retrieval while satisfying
the issue's requirement that list endpoints also contain `page`.

HTTP status and headers keep their meaning: creates remain 201, login/register
remain `Cache-Control: no-store`, and deletes remain 204 with **no body**. Do not
wrap a 204 or ask the frontend to parse JSON from it. JWKS, Actuator, Swagger and
other protocol/infrastructure output retain their native formats. Request and
domain DTOs are otherwise unchanged; in particular existing numeric monetary
fields are not converted to strings by this issue.

Rationale: one parser can handle objects, lists and pages without losing existing
HTTP semantics or combining pagination with business data.

### 2. Stable symbolic codes, owned by each service

`ErrorCode` is an interface with `code()`, `status()` and `message()` methods.
Shared transport failures live in `CommonErrorCode`; business errors live in
the owning service enum (`UserErrorCode`, `CatalogErrorCode`,
`CommerceErrorCode`, and corresponding enums in other services).

Errors use `application/problem+json` with required `type`, `title`, `status`,
`detail`, `instance` and `code`. `code` is a stable string such as
`USER_EMAIL_ALREADY_USED`; it is distinct from numeric HTTP `status`.
Clients branch on `status` and `code`, never English `detail` or the URI spelling.
Unknown future codes must still render a useful fallback.

Bean validation adds `errors: [{"field":"email","code":"VALIDATION_NOT_BLANK",
"detail":"Email is required"}]`, with every failed field represented and
constraint identifiers in `code`. `traceId` identifies an active trace when
available; callers must accept its absence at both services and gateway.
Tokens, passwords and internal
stack traces are never error properties.

Rationale: services can evolve their own failures without turning common-core
into the business vocabulary for the entire system. The standard Problem Details
shape remains usable by generic HTTP tooling.

### 3. Explicit frontend cutover and executable contract

The canonical delivered-endpoint specs are
[`docs/api/openapi/*.yaml`](../api/openapi/). They describe all 24 operations,
request constraints, success envelopes, status-specific error codes, access rules
and examples. [`style-guide.md`](../api/style-guide.md) governs future endpoints.
The older `docs/api/commerce-service.yaml` and `notification-service.yaml` remain
the #88 draft for future operations; they are not the delivered contract and
their old response/error shapes must not be copied into new implementations.

This is a breaking JSON change. FE must update `lib/api-client.ts` and endpoint
parsers on the agreed merge day: unwrap `data`, consume sibling `page`, handle
Problem Details by `code`, and skip JSON parsing for 204. Do not introduce a
permanent parser accepting both shapes or claim that coordination happened merely
because this plan is committed.

**Pending human actions:** ADR/style-guide approval and the named BE/FE owners'
agreement on a merge date. No date or approval has been inferred. Field-validation
and password-confirmation workflow changes remain in #238; new services #240,
#241 and #242 adopt this contract rather than adding another shape.

Rationale: a checked-in specification and enforcement tests prevent drift, while
a coordinated release prevents existing FE consumers from receiving a shape they
cannot parse.

## Enforcement and verification

- ArchUnit rejects application `@RestController` methods returning a bare DTO,
  `List`, or other unwrapped JSON. `ApiResponse<T>`, its `ResponseEntity` wrapper,
  and bodyless `void`/`ResponseEntity<Void>` are supported; protocol controllers
  are excluded explicitly.
- JSON contract tests verify serialization, field failures, symbolic codes,
  security errors, create statuses and empty pages. The architecture test is
  exercised with an intentionally invalid list-returning fixture.
- From `be-project-nexus`, run
  `node docs/api/check-contract.mjs` and
  `npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/*.yaml`.
  On shells that do not expand wildcards, pass the three YAML filenames explicitly.
- Existing service tests remain responsible for authorization, ownership and
  domain behavior; static OpenAPI validation does not prove runtime security.

## Consequences

Application controllers now have a uniform response type and FE gets one success
parser. Removing the legacy success wrapper and both duplicate page classes is intentional;
services and internal clients must rebuild against the updated shared libraries.
The commerce-to-catalog client must unwrap the SKU envelope too. Catalog lookup
failures are explicit: missing SKU remains 404, invalid SKU response is 502
`COMMERCE_INVALID_CATALOG_RESPONSE`, and other Feign failures are 503
`COMMERCE_CATALOG_UNAVAILABLE`. Previously malformed/missing SKU data was treated
as missing and other upstream failures reached the generic 500 handler.
No endpoint, permission or domain lifecycle value is added by this decision.
