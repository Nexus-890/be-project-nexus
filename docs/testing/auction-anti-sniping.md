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

Verified on GitLab on 10/11/2026: #145 is closed/merged; #146 remains open in
review. This branch includes #146 commit `882be7d` so #149 can be implemented and
tested, but the existing dependency on merging #146 still applies to release.
No #147 idempotency or #148 concurrency-suite changes are included.
