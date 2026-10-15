# Gateway permissions

This document contains the active gateway policy table and the frozen Sprint 2
draft contracts below. A draft contract does not expose a runtime endpoint. Every
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

| Path | Method | Public or required privilege | Owning issue |
|---|---|---|---|
| `/api/v1/auth/register` | POST | Public | #74 — User registration with a default role |
| `/api/v1/auth/login` | POST | Public | #71 — Login endpoint |
| `/.well-known/jwks.json` | GET | Public | #72 — JWKS endpoint |
| `/api/v1/products` | GET | Public | #99 — Product lists |
| `/api/v1/products/{id}` | GET | Public | #83 — Product create, update and read |
| `/api/v1/categories/{id}` | GET | Public | #80 — Category create, update and read |
| `/api/v1/categories/tree` | GET | Public | #81 — Category tree endpoint |
| `/api/v1/products/{id}/skus` | GET | Public | #100 — SKU public reads and product visibility |
| `/api/v1/skus/{id}` | GET | Public | #86 / #100 — SKU reads and product visibility |
| `/api/v1/users/me` | GET | PROFILE.VIEW | #75 — Own profile: read and update |
| `/api/v1/users/me` | PUT | PROFILE.UPDATE | #75 — Own profile: read and update |
| `/api/v1/products` | POST | PRODUCT.CREATE | #83 — Product create, update and read |
| `/api/v1/products/mine` | GET | PRODUCT.LIST | #99 — Product lists |
| `/api/v1/products/{id}` | PUT | PRODUCT.UPDATE | #83 — Product create, update and read |
| `/api/v1/products/{id}/status` | PATCH | PRODUCT.UPDATE | #84 — Product status workflow |
| `/api/v1/products/{id}` | DELETE | PRODUCT.DELETE | #85 — Product soft delete |
| `/api/v1/categories` | POST | CATEGORY.CREATE | #80 — Category create, update and read |
| `/api/v1/categories/{id}` | PATCH | CATEGORY.UPDATE | #80 — Category create, update and read |
| `/api/v1/categories/{id}` | DELETE | CATEGORY.DELETE | #82 — Category delete guard |
| `/api/v1/products/{id}/skus` | POST | PRODUCT.UPDATE | #86 — SKU create, update and read |
| `/api/v1/skus/{id}` | PUT | PRODUCT.UPDATE | #86 — SKU create, update and read |
| Any other path | Any other method/path pair | Token required; add the SRS privilege before exposing a new endpoint | Gateway JWT filter and permit list; future owning issue |

## Matching and JWT contract

- Exactly nine method/path pairs are public. No public `/**`, method-less rule,
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
  `title`, `status`, `detail`, `instance`, nonempty `traceId`, and
  `WWW-Authenticate: Bearer`. Missing privileges return 403 in the same JSON format.
  Details never echo tokens or decoder exceptions. The trace ID uses the active
  tracing span, or a generated correlation ID when tracing is unavailable.
- No security session is created. Existing routes forward the bearer header;
  authenticated unknown paths keep the existing route/fallback behavior.

## Verification

From `be-project-nexus/api-gateway`, run `./mvnw verify` using Java 21.
Tests use real RS256 tokens and a local HTTP JWKS fixture, not a mocked decoder.
A real gateway HTTP route forwards accepted requests to an instrumented HTTP
service; rejected requests leave its request log empty. Tests cover all nine
public pairs, other methods, listed privileges, invalid tokens, and fallback.
The permission test binds the same YAML properties consumed by `SecurityConfig`
and compares all explicit rows (including privileges) in both directions. It also
independently checks the nine public pairs. Newly public undocumented pairs,
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


## Sprint 2 draft — commerce and notifications

These **14 method/path contracts are fixed for callers**, while DTO field names
remain drafts (`0.1.0-draft`). This section is the permission handoff for the
[commerce contract](commerce-service.yaml) and
[notification contract](notification-service.yaml), not an assertion that the
runtime gateway already enforces them. The active table above is unchanged.
Each method/path appears exactly once in the draft table; none is public.

The gateway dependency is present in the branch history: merge commit `f67e7a3`
merged `gateway-jwt-permit-list` into `develop`. Its current configuration supports
one privilege per policy and falls back to authentication for unlisted routes.
**A token alone is not sufficient under these Sprint 2 contracts.** Endpoint-owning
implementation MRs must register and enforce these rules before exposing the APIs:
all listed privileges **AND** at least one listed role. `ADMIN` does not bypass
privileges. Preserve the existing nine public method/path pairs.

Roles are canonical JWT `roles` codes from ADR-006; the gateway maps them to
`ROLE_<code>`. Privileges are exact JWT `privileges` strings without prefixes.
`none (role-only)` below means the ADMIN role is required, not public access.
The status endpoint has no dedicated SRS privilege; do not invent `ORDER.UPDATE`.
Ownership constraints are mandatory downstream checks against validated JWT `sub`.
Do not accept a caller-controlled `userId` to select another account's resources.

| Method | Path | Required role (any one) | Required privileges (all) | Ownership / business restriction | Owning issue |
|---|---|---|---|---|---|
| GET | `/api/v1/carts/me` | BUYER | CART.VIEW AND CART.CREATE | Own cart; get-or-create can create a resource | #110 |
| POST | `/api/v1/carts/me/items` | BUYER | CART.UPDATE | Own active cart | #110 |
| PUT | `/api/v1/carts/me/items/{id}` | BUYER | CART.UPDATE | Own cart item; inaccessible item returns 404 | #111 |
| DELETE | `/api/v1/carts/me/items/{id}` | BUYER | CART.REMOVE_ITEM | Own cart item; inaccessible item returns 404 | #111 |
| POST | `/api/v1/orders` | BUYER | CHECKOUT.START AND ORDER.CREATE | Own cart; caller-scoped idempotency key | #113, #114, #115 |
| GET | `/api/v1/orders` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | ORDER.LIST | Own orders only, including for ADMIN; not a global admin list | #117 |
| GET | `/api/v1/orders/{id}` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | ORDER.VIEW | Owner or ADMIN; other caller returns 403 | #117 |
| GET | `/api/v1/orders/{id}/history` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | ORDER.VIEW | Owner or ADMIN; other caller returns 403 | #116 |
| PATCH | `/api/v1/orders/{id}/status` | ADMIN | none (role-only) | Only PENDING to PAID or CANCELED in Sprint 2; audit actor and reason | #116 |
| POST | `/api/v1/orders/{id}/payment` | BUYER | CHECKOUT.START | Own PENDING order only; repeated call returns the same instructions | #120, #121 |
| GET | `/api/v1/notifications` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | Own notifications only, including for ADMIN | #108 |
| GET | `/api/v1/notifications/unread-count` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | Count own unread notifications only | #108 |
| PATCH | `/api/v1/notifications/{id}/read` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | Own notification only; other caller returns 403 | #109 |
| PATCH | `/api/v1/notifications/read-all` | ADMIN, SELLER, BUYER, SUPPORT_STAFF | NOTIFICATION.VIEW | Update own unread notifications only | #109 |

System-generated status transitions run through internal service or consumer
boundaries. They are not permission to send a buyer JWT to the ADMIN HTTP endpoint.
Gateway fixtures must cover missing token (401), missing role or any required
privilege (403), and successful authorization; services must additionally test
ownership. Prism cannot prove any of these runtime security properties.

### Scope and draft decisions

- Source: [SRS 3.2 and 3.4](https://docs.google.com/document/d/1nw7_WwZoWhjgA1WiZJzu37ps_MisI5Ls/edit)
  and [endpoint catalogue](https://docs.google.com/spreadsheets/d/1KIJsr87F-6TWieBbie9CVzgCBTPsM4Zk/edit),
  filtered to **R1 · Sprint 2** and the two requested services. Catalogue IDs:
  CRT-01..04, ORD-01/03..06, PAY-01 and NTF-01..04.
- Not included: standalone pricing/validation/checkout preview, guest carts,
  buyer cancellation/refunds, notification preferences or push subscriptions.
  The Sepay webhook is Sprint 4, not a new public Sprint 2 path. Cart expiry,
  outbox relay and notification consumers are jobs/consumers, not HTTP endpoints.
- **Nonempty mutation responses:** this draft deliberately replaces catalogue
  204 with 200 JSON for cart item removal and both mark-read operations. Removal
  returns the updated cart; mark-one returns the notification; mark-all returns
  `{updatedCount, readAt}`. Empty collections/counts still have nonempty JSON
  envelopes. This is a proposed contract for #111/#109, not a claim that those
  implementation issues or the frontend have approved the change.
- **Money:** draft JSON numbers with at most two decimal places and `currency: VND`
  follow current `numeric(19,2)` migrations. Later-sprint long-VND notes still need
  alignment before DTOs are finalized. Clients must not calculate authoritative
  prices. Shipping, tax, discounts and totals are server supplied.
- **Idempotency:** order creation requires UUID `Idempotency-Key`; a new order is
  201, an identical caller/payload retry is 200 with the same order, and a changed
  payload with the same key is 409. A key belonging to another caller returns
  400 and never discloses that caller's order. Payment initiation repeats the same
  201 instructions; marking an already-read notification preserves `readAt`.
- **Pagination:** zero-based `page`, `size` defaults to 20 (1..100); list envelopes
  use `{content, page, size, totalElements, totalPages}`. Sprint 2 order filtering
  supports `status`, not the later-sprint `from`/`to` filters.

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

#### Frontend quick start — hướng dẫn bàn giao

**1. Cập nhật nhánh**

Chạy từ thư mục gốc repository, trước khi mở hai terminal chạy mock:

```bash
git fetch origin
git switch fsa_ANNT_25/feature/sprint-2-api-shapes
git pull --ff-only
```

Các lệnh trên dành cho working tree sạch. Nếu Git báo thay đổi local hoặc nhánh
bị phân kỳ, giữ nguyên thay đổi của bạn và xử lý trước khi tiếp tục; không dùng
`reset --hard` chỉ để chuyển sang nhánh mock.

**2. Chạy mock server**

Mở **hai terminal riêng**, đều bắt đầu tại thư mục gốc repository. Giữ cả hai
terminal chạy trong lúc phát triển frontend.

Terminal 1 — Commerce (cart, order, payment):

```bash
cd be-project-nexus
npx --yes @stoplight/prism-cli@5.14.2 mock docs/api/commerce-service.yaml -p 4010
```

Terminal 2 — Notification (list, unread count, mark-read):

```bash
cd be-project-nexus
npx --yes @stoplight/prism-cli@5.14.2 mock docs/api/notification-service.yaml -p 4011
```

Đợi mỗi terminal báo `Prism is listening` trước khi gọi API. Nếu terminal đã ở
`be-project-nexus`, bỏ qua lệnh `cd`. Dùng cùng phiên bản Prism để tái lập fixtures.
`@stoplight/prism-cli` là package CLI; không dùng `@stoplight/prism` trong lệnh
của issue. Không thêm `--dynamic`: FE cần các example cố định đã commit.
Prism bật CORS mặc định; khi sửa YAML, khởi động lại mock để nạp contract mới.

**3. Thông tin kết nối API**

| Service | Mock base URL | API kiểm tra nhanh |
|---|---|---|
| Commerce | `http://localhost:4010` | `GET /api/v1/carts/me` |
| Notification | `http://localhost:4011` | `GET /api/v1/notifications` |

`localhost` / `127.0.0.1` là máy **đang chạy Prism**, không phải server dùng chung.
FE chạy hai mock trên máy mình rồi cấu hình API client trỏ đến hai base URL tương
ứng. Base URL chưa chứa `/api/v1`, nên thêm prefix này đúng một lần khi gọi API.
Nếu nhóm dùng host phát triển chung, cần gửi URL thực sự truy cập được; thay đổi
này không tạo host chung hoặc public tunnel.

**4. Header và request body**

Mọi operation trong hai contract đều cần các header sau khi gọi success fixture:

```text
Authorization: Bearer mock-token
Accept: application/json
```

`mock-token` chỉ dùng cho Prism. Mock không kiểm tra chữ ký JWT, role, privilege
hay ownership như runtime gateway/service.

**Chỉ gửi JSON body và `Content-Type: application/json` khi operation khai báo
`requestBody`**, không dựa riêng vào việc method là POST/PUT/PATCH:

| Operation có JSON body | Dữ liệu cần gửi |
|---|---|
| `POST /api/v1/carts/me/items` | `skuId`, `quantity` |
| `PUT /api/v1/carts/me/items/{id}` | `quantity` |
| `POST /api/v1/orders` | `shippingAddress`, `paymentMethod`; xem example đầy đủ trong YAML |
| `PATCH /api/v1/orders/{id}/status` | `status`, `reason` |

Riêng `POST /api/v1/orders`, gửi thêm:

```text
Idempotency-Key: 70000000-1111-4111-8111-111111111111
```

`POST /api/v1/orders/{id}/payment`, `PATCH /api/v1/notifications/{id}/read` và
`PATCH /api/v1/notifications/read-all` **không có request body**. Không tự động gửi
`{}` hoặc gắn `Content-Type` cho các request này. Các GET/DELETE cũng không có body.
Lấy UUID, payload và response example trực tiếp từ hai YAML; không gửi `{}` thay
cho body bắt buộc. Khi gặp 400, kiểm tra field bắt buộc, kiểu dữ liệu và header;
khi gặp 415, kiểm tra body và media type có được operation khai báo hay không.
Chỉ nhận 400/415 không chứng minh success contract đã được kiểm tra đúng.

Ví dụ gọi từ frontend, dùng trong môi trường hỗ trợ `fetch` và top-level `await`:

```javascript
// Thêm một SKU: operation có requestBody.
const addItemResponse = await fetch("http://localhost:4010/api/v1/carts/me/items", {
  method: "POST",
  headers: {
    Authorization: "Bearer mock-token",
    Accept: "application/json",
    "Content-Type": "application/json",
  },
  body: JSON.stringify({
    skuId: "00000003-1111-4111-8111-111111111111",
    quantity: 2,
  }),
});
if (!addItemResponse.ok) throw new Error(await addItemResponse.text());
const cart = await addItemResponse.json();

// Đánh dấu tất cả đã đọc: không gửi body hoặc Content-Type.
const markAllResponse = await fetch("http://localhost:4011/api/v1/notifications/read-all", {
  method: "PATCH",
  headers: {
    Authorization: "Bearer mock-token",
    Accept: "application/json",
  },
});
if (!markAllResponse.ok) throw new Error(await markAllResponse.text());
const markAllResult = await markAllResponse.json();
```

**5. Chọn fixtures cho màn hình**

Gửi header `Prefer` để chọn fixture phù hợp; nếu không gửi, Prism chọn success
response mặc định. Riêng tạo order, chọn rõ 201 hoặc 200 cho màn hình cần kiểm tra.

| Trường hợp | Operation áp dụng | Header `Prefer` |
|---|---|---|
| Tạo order mới | `POST /api/v1/orders` | `code=201` |
| Retry cùng order | `POST /api/v1/orders` | `code=200` |
| Cart/list rỗng | GET cart, danh sách order hoặc notification | `example=empty` |
| Trạng thái order | `GET /api/v1/orders/{id}` | `example=pending`, `example=paid` hoặc `example=canceled` |
| Giá thay đổi | `POST /api/v1/orders` | `code=409, example=priceChanged` |
| Thiếu tồn kho | `POST /api/v1/orders` | `code=409, example=insufficientStock` |
| Không có unread / không có thay đổi | GET unread-count hoặc PATCH read-all | `example=none` |
| Lỗi xác thực từ service | Mọi operation | `code=401, example=service` |
| Lỗi quyền từ gateway | Mọi operation | `code=403, example=gateway` |
| Lỗi field validation | Bốn operation có JSON body | `code=400, example=validation` |
| Body không hợp lệ | Bốn operation có JSON body | `code=400, example=malformedBody` |

Khi chọn error fixture, đổi `Accept` thành `application/problem+json`.
`code=...` trong `Prefer` là HTTP status selector của **Prism**, không phải field
`code` nghiệp vụ trong response và không phải header cần dùng khi gọi backend thật.
Các fixture ép chọn không chứng minh Prism đang chạy Java handler hoặc kiểm tra
quyền thực tế.

Mock **không lưu trạng thái**: thêm/xóa cart, mark-read, retry hay polling payment
không làm dữ liệu tự thay đổi. Chọn example tương ứng trong FE mock adapter; dùng
cặp order/payment examples có cùng order UUID, số tiền, memo và deadline cho màn
hình checkout. Mỗi operation đều có success example với JSON body không rỗng.
UUID, tiền, timestamp, QR và tài khoản ngân hàng chỉ là dữ liệu mẫu; không dùng
chúng làm thông tin thanh toán thực tế.

### Verification recorded for this draft

- Both files pass Swagger Parser 12.1.0 validation and Redocly CLI 2.63.0
  recommended lint. The default lint reports only four advisory warnings in
  total: local mock server URLs and missing license metadata (two per file).
  Skipping only `info-license` and `no-server-example.com` yields a clean lint;
  no schema/example rule is disabled.
- Live Prism 5.14.2 checks cover all 14 operations and 154 HTTP fixture/invalid
  request cases, plus 14 default-success requests without a `Prefer` header
  (168 HTTP requests total). Selected success and forced-error bodies match their
  declared examples and schemas, including 200 mutations and all polling variants.
- 319 schema/example checks cover request examples, parameter examples, declared
  response examples and live response bodies. The role/privilege extensions match
  the 14 permission rows one-to-one, with no duplicate method/path pair.
- Shared-handler compatibility: 30 JSON responses serialized through Spring
  MockMvc from the real `GlobalExceptionHandler` / `SecurityProblemAdvice` validate
  against each spec, with and without an active span. Four negative checks reject
  invented symbolic `code` and SKU-shaped validation `errors` (64 checks total).
  The previous schema rejected real untraced responses; this is now corrected.
- The common-core/common-web/common-security reactor passes Java 21 `verify`,
  including 14 common-security tests (0 failures/errors).
- Existing `GatewayPermissionsTest` passes on Java 21: 4 tests, 0 failures/errors.
  This proves the active permission table is preserved, not that the new draft
  rules have been deployed. No Java or gateway configuration is changed here.
- Tools and temporary smoke scripts stay outside the repository. Only the two
  contracts and this permission/handoff document are delivered.

### Frontend handoff and acceptance

Gửi link mục **Frontend quick start** ở trên, hai mock base URL và kết quả
validation/smoke vào MR hoặc kênh nhóm có frontend developer. Ghi rõ FE cần chạy
Prism local nếu chưa có host dùng chung. Commit/push tài liệu chưa đồng nghĩa đã
bàn giao trực tiếp hoặc frontend đã xác nhận.

Mẫu nhắn frontend trong MR (thay `@TEN_FRONTEND_DEV` bằng người nhận thật):

> Chào @TEN_FRONTEND_DEV, draft API Sprint 2 đã có trên nhánh
> `fsa_ANNT_25/feature/sprint-2-api-shapes`. Bạn xem mục **Frontend quick start**
> trong `docs/api/gateway-permissions.md` để chạy hai mock local.
> Commerce: `http://localhost:4010`; Notification: `http://localhost:4011`.
> Request/response examples và quyền gọi từng API nằm trong hai YAML và bảng
> permissions của MR. Bạn thử các màn hình cần xây dựng; nếu thiếu field hoặc
> fixture, nhờ bạn comment rõ operation và dữ liệu cần thêm. Khi đủ để phát triển,
> nhờ bạn xác nhận trực tiếp trong MR: **“Confirmed, mock data đã đủ để FE phát triển.”**

Before marking this issue done:

- [x] Both OpenAPI files pass validation and all examples conform to schemas.
- [x] A live Prism request to each of the 14 method/path operations returns the
      declared example/status and a nonempty JSON body (including DELETE/PATCH).
- [x] Validate new-order/retry, empty lists, price/stock conflict and illegal-status
      fixtures; no real JWT or payment credentials are used in mocks.
- [x] The 14 operations match the draft table one-to-one with required role(s).
- [ ] #111/#109 owners acknowledge the draft 200-with-body change from 204.
- [ ] Owning backend MRs carry the fixed caller policy into gateway and service
      checks before exposure; the current draft must not be treated as deployment.
- [ ] Mock URLs and the quick-start link have been sent to the frontend developer.
- [ ] **Frontend developer comments in the MR that the mocks are enough to build on.**

Frontend confirmation and cross-issue agreement are external review steps; this
file does not claim they have occurred. Technical checks alone do not close the
issue.
