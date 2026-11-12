# Auction anti-sniping — #149

An accepted bid inside the last configured minutes adds that many minutes to the
current `end_at`. For example, a bid with two minutes left and the default window
of five minutes moves the deadline from T to T+5 minutes. The window includes its
boundary. PostgreSQL transaction time (`now()`) is shared with the bid admission
check; the application's event clock does not decide whether to extend.

The policy runs after the bid insert and before the outbox write, inside the same
transaction and auction row lock. Errors roll back the price, bid, extension and
outbox together. Refused bids never enter the policy.

## Runtime configuration

Config Server source: `config-service/src/main/resources/config/auction-service.yml`.

| Key under `nexus.auction` | Default | Meaning |
| --- | --- | --- |
| `anti-sniping-enabled` | `true` | Enables extension for accepted bids; SRS 2.6 default. |
| `anti-sniping-extension-minutes` | `5` | Both the remaining-time window and the duration added; SRS 2.6 default. |
| `anti-sniping-max-extension-minutes` | `30` | Maximum cumulative duration after `original_end_at`. Issue #149 requires a finite cap but specifies no default; 30 minutes is the implementation default. |

Minute settings must be positive integers. The final extension can be shorter
than the normal increment: `min(end_at + extension, original_end_at + cap)`.
`original_end_at` is never updated. `extension_count` increases only when the
effective deadline grows. Lowering the cap below an already extended deadline
does not shorten that deadline; it prevents further extension.

Update the Config Server source, then send an authenticated
`POST http://localhost:8084/actuator/refresh` to each auction-service instance.
The shared actuator exposure already includes refresh, and existing service
authentication applies. No new public endpoint or gateway route is added.
`AuctionProperties` is a refresh-scoped JavaBean so the switch, window and cap
are rebound without replacing `BidService` or restarting the application.

## Verification

From `be-project-nexus/auction-service`, run the focused unit and configuration
tests with Java 21, without starting Postman, PostgreSQL, Docker, Config Server
or Keycloak:

```powershell
.\mvnw.cmd '-Dtest=AntiSnipingPolicyTest,BidServiceAntiSnipingTest,AuctionPropertiesTest' test
```

- `AntiSnipingPolicyTest`: default/custom settings, disabling/re-enabling the
  policy, zero eligible updates, repository errors and Spring's transaction
  contract. A mock JDBC connection verifies that the policy requires and joins
  the enclosing transaction and that a failure selects rollback instead of commit.
- `BidServiceAntiSnipingTest`: the hook runs after the bid is persisted and before
  event/outbox creation; missing, seller, inactive, out-of-window and low bids
  never invoke the policy. Bid persistence and policy failures stop later writes.
- `AuctionPropertiesTest`: binds and validates the production configuration bean,
  rejects non-positive window/cap settings, and refreshes the existing proxy used
  by the same policy consumer without recreating the application context.

These tests do not simulate SQL deadline arithmetic. The PostgreSQL tests below
exercise the actual window, cap, original-deadline and rollback behavior.

After merging develop, the bid-service tests also cover the #147 idempotency API:
replay must not invoke the policy again, and an invalid key must be rejected
before opening a transaction. `BidIdempotencyTest` additionally checks that a
last-minute replay with the same numeric amount, or a changed-amount conflict,
leaves the committed deadline, extension count, bid count and outbox unchanged.

Verified on 11/11/2026: all 28 new unit/configuration cases passed (9 policy,
11 bid-service, 8 configuration), with no failures, errors or skips. The attempted
PostgreSQL rerun stopped before assertions because Testcontainers could not find
a running Docker environment. The earlier integration results below remain
historical evidence, rather than a successful rerun on this date.

From `be-project-nexus/auction-service` with Java 21 and Docker available:

```powershell
.\mvnw.cmd -Duser.timezone=UTC verify
```

- `BidPlacementIntegrationTest`: real MockMvc/PostgreSQL bids cover 2m -> +5m,
  30m unchanged, the inclusive 5m boundary versus 5m+1 microsecond, partial/final
  cap, repeated bids at the cap, lowering the cap, disabled policy, low/seller
  refusal and outbox failure rollback. Original deadlines are checked throughout.
- `AntiSnipingRefreshIntegrationTest`: full application and disposable PostgreSQL;
  real authenticated HTTP refresh disables/re-enables the policy and changes
  window, duration and cap without a new service or application startup. The
  normal ten-second scheduler ends a capped auction after repeated accepted bids;
  later bids are refused. JWT decoding is a test fixture; Keycloak is not contacted.

The bid response and BidPlaced event schemas are unchanged. Clients can refetch
the public auction read for the committed `endAt` and `extensionCount`.

Use UTC for the test JVM on Windows: some PostgreSQL images used by existing
auction tests reject Java's `Asia/Saigon` timezone alias. This is a test runtime
setting and does not alter application timestamps.

Local validation on 10/11/2026: auction-service clean verify passed 157 tests,
config-service verify passed 5 tests, with no failures, errors or skips. The
contract checker passed all 67 operations; auction OpenAPI passed Redocly 2.63.0.
These are local results using disposable databases, not remote CI evidence.

## Dependency status

On 12/11/2026 this branch merged develop, which now contains the bid placement,
idempotency and concurrency dependencies. The accepted-bid extension remains in
the same transaction as the conditional update, bid insert and outbox write.
Replays return the original response before reaching the extension hook.

Merge validation on 12/11/2026: all Auction main and test sources compiled;
30 anti-sniping unit/configuration cases, 27 real-JWT/HTTP contract cases and
7 Config Server cases passed with no failures, errors or skips. Contract checking
passed 90 operations. Auction OpenAPI
passed Redocly 2.63.0 without warnings. The three focused PostgreSQL integration
classes could not initialize: Docker's named-pipe endpoint returned HTTP 500
(33 setup errors, no assertions executed). This is not a successful PostgreSQL
rerun or remote CI result.
