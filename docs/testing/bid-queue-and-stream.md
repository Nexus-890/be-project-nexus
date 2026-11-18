# Queued bidding and auction SSE — #253 / #267

Implementation lives in the primary local project and follows the existing controller/service/repository, transactional outbox, EventEnvelope and IdempotentConsumer conventions. SRS 3.5.2 requires atomic bids, the highest valid winner and no lost valid bids. The older endpoint workbook BID-01 (row 108) describes 201/200 synchronous placement; issue #253 explicitly replaces that flow with 202 plus an owner-only result resource.

## Delivered behavior

- POST `/api/v1/auctions/{id}/bids`: BUYER + `AUCTION.BID`, UUID `Idempotency-Key`; returns ApiResponse containing `{bidId,status:"QUEUED",requestedAt}` and `Location`. The transaction stores the request and `BidRequested` outbox command together. Format, ACTIVE/time window and seller exclusion run at admission; the minimum price runs in the consumer.
- Matching idempotent retries return the immutable 202 receipt, including after placement, rejection or auction end. A different bidder, auction or numeric amount returns 409 without exposing the original bid.
- GET `/api/v1/auctions/{id}/bids/{bidId}`: BUYER + `AUCTION.BID`; only the original bidder sees QUEUED, PLACED or REJECTED. Another bidder, a wrong auction or unknown bid receives the same `AUCTION_BID_NOT_FOUND` 404. Rejection includes a stable reason and minimum where relevant.
- `nexus.auction.bids` is keyed by auctionId. The consumer first waits for earlier in-flight admissions to commit, releases that admission barrier, then processes within IdempotentConsumer. It serializes processing per auction and drains committed requests in requested_at/id order through the command's bid. POST can still commit while a consumer holds the auction price update transaction.
- Conditional price update, result, requestedAt-based anti-sniping, processed marker and BidPlaced outbox commit together. Scheduler end and settlement wait for eligible QUEUED requests, including an admission not yet visible to a normal SELECT. QUEUED/REJECTED requests cannot become the winner or appear as accepted public history.
- Public GET `/api/v1/auctions/{id}/stream`: named `state` events contain exactly GET/state data, without ApiResponse, and use the auction version as id. First state is immediate; heartbeats run every 15 seconds. Every instance has its own Kafka group; committed events coalesce for 200 ms and refresh the one-second cache, including the anti-sniping endAt.
- Unknown/restricted/deleted auction: 404 before opening. Capacity: 503 `AUCTION_STREAM_BUSY`. Final ENDED/WON/SETTLED/FAILED/CANCELLED/PAYMENT_TIMEOUT state is sent, then the stream closes. Reconnect always receives the current snapshot; historical replay is not required.
- Existing FE SSE/reconnect/two-second polling stays available. Bid submission now waits for terminal status, shows REJECTED.minimum, preserves the key after an uncertain POST, and retries GET only once the bidId is known.

Migration V10 is additive. Develop already owns V8 settlement and V9 second-chance offers; the bid queue migration was moved from V9 to V10 without changing its SQL body. Existing develop migrations and checksums are unchanged. Fresh databases and upgrades from develop V9 are covered by BidQueueMigrationTest. Second-chance selection uses only PLACED/OUTBID/WON bids, excluding queued, rejected and cancelled requests even when their amount is higher.

## Configuration and infrastructure

`config-service/src/main/resources/config/auction-service.yml` owns:

| Key | Default | Purpose |
| --- | --- | --- |
| `nexus.outbox.relay.fixed-delay` | 100 ms | Low-latency command and state publication; env `AUCTION_OUTBOX_RELAY_DELAY_MS` |
| `nexus.auction.stream.max-connections` | 2000 | Concurrent emitters per instance; env `AUCTION_STREAM_MAX_CONNECTIONS` |
| `nexus.auction.stream.timeout` | 30m | Emitter lifetime |
| `nexus.auction.stream.heartbeat-interval` | 15s | Keep browser stream activity visible |
| `nexus.auction.stream.coalesce-interval` | 200ms | One latest state per event burst |
| `nexus.auction.stream.group-id` | `auction-stream-${random.uuid}` | Must remain distinct per instance; tests may use explicit distinct ids |

Provision `nexus.auction.bids` from `infra/local-dev/kafka/topics.conf` before starting the service. A larger partition count distributes different auctions; one auction always uses one key. Outbid and WebSocket are outside this issue.

The gateway has a dedicated public GET stream route at priority -2, disables response timeout and sends X-Accel-Buffering=no / no-cache,no-transform. Concurrent stream capacity is enforced by auction-service, so opening viewers does not consume the normal short-request per-IP quota. The private result path uses AUCTION.BID and preserves the JWT for ownership checks.

nginx (#163): use HTTP/1.1, clear Connection, `proxy_buffering off`, `proxy_cache off`, and `proxy_read_timeout 35m` for `/api/v1/auctions/<uuid>/stream`. Do not buffer/compress the entire response before flushing chunks. Polling remains available when a stream is refused or interrupted.

## Reproduce the checks

Use Java 21 and Docker. Run Maven from each service directory because tests import sibling Config Server files. UTC avoids the PostgreSQL/Asia/Saigon alias issue. On machines with limited Windows commit memory, use Xms64m/Xmx512m and ActiveProcessorCount=2; Mockito may be preloaded as a Java agent as used in the checked local logs.

```powershell
Set-Location be-project-nexus/lib
.\mvnw.cmd -B -DskipTests install
.\mvnw.cmd -B -pl common-events -am test
Set-Location ../api-gateway
.\mvnw.cmd -B verify
Set-Location ../auction-service
.\mvnw.cmd -B '-Duser.timezone=UTC' verify
Set-Location ..
node docs/api/check-contract.mjs
```

Frontend (Node 24; the full existing auth suite requires navigator.locks): `npm ci`, `npm run test:unit`, `npm run test:auction`, `npm run lint`, `npm run typecheck`, and `npm run build` from `fe-project-nexus`. Auction browser tests use the real Next.js app plus the existing Postman HTTP fixture; they do not claim a live banking/Keycloak/staging environment.

Optional real local load run, after gateway packaging:

```powershell
# From auction-service; the default run is 1000 clients, 60 bids, one bid per second.
.\mvnw.cmd -B '-Duser.timezone=UTC' '-Dtest=AuctionStreamKafkaIT' '-Dauction.stream.load=true' test
```

The load helper starts the packaged WebFlux gateway, routes to the real auction instance, and uses the existing real PostgreSQL/Kafka integration fixture. It records every viewer/bid delivery in `auction-service/target/auction-stream-load/result.json` and writes the gateway process log beside it. It sends bids through the real service/outbox/Kafka consumer, rather than the HTTP POST, so its accept timing is not an HTTP POST p95 measurement. Report local numbers separately from STAGING.

## Reviewer evidence and screenshots

| Issue item | Important production code | Important test / evidence |
| --- | --- | --- |
| Migration + atomic queued acceptance | V10__bid_queue.sql; BidService.accept; BidController.place | BidPlacementIntegrationTest; BidIdempotencyTest.acceptsWithoutChangingPriceThenAppliesCommandAtomically |
| 50 concurrent bids / no queued rows left | BidService.apply; AuctionRepository.acceptQueuedBid | BidConcurrencyIT: 10 runs x 50 real HTTP requests, 10 buyer JWTs; final highest price/leader and one event per placed bid |
| Duplicate command, restart, rebalance | BidCommandConsumer; IdempotentConsumer; bid processing guard + conditional SQL | BidIdempotencyTest duplicate/restart, competing consumers and conditionalUpdateAloneAllowsOneWinnerForSamePrice |
| Arrival order / no hidden older admission | BidService.awaitAdmissions and apply; admission locks | BidIdempotencyTest.processingWaitsForEarlierAdmissionBeforeDrainingLaterCommand; newAdmissionDoesNotWaitForConsumerPriceTransaction |
| requestedAt, anti-sniping, winner waits | AntiSnipingPolicy; AuctionScheduler; SettlementService | BidIdempotencyTest late consumption, in-flight admission and settlement-wait tests; AntiSnipingRefreshIntegrationTest |
| Owner-only result / 404 privacy | BidService.status; BidController.status; gateway policy | BidPlacementIntegrationTest ownership/403 and KeycloakTokenTrustTest; gateway private result test |
| SSE lifecycle / burst coalescing | AuctionStreamRegistry; AuctionStateQuery | AuctionStreamRegistryTest; AuctionStateVersionTest |
| Every instance receives live price/endAt | AuctionStreamConsumer; per-instance group | AuctionStreamKafkaIT: two real HTTP instances, Kafka, actual queued bid and deadline extension |
| Gateway no buffering / timeout | RouteConfig auction-stream route | SecurityConfigTest.auctionStreamFlushesStateAndHeartbeatThroughGateway, including heartbeat after the ordinary HTTP timeout |
| FE QUEUED/REJECTED/retry + polling fallback | auctionBidQueue.ts; useAuctionBid.ts | auction-bid-queue.test.ts; auction.spec.ts, including GET-only retry and existing SSE fallback scenarios |
| 1000 clients / delivery p95 | AuctionStreamRegistry + packaged gateway | Optional load result.json; record client count, duration, errors and p95 from the actual run |

For each screenshot include the class/method name, Vietnamese explanation and green assertions/result panel. Capture the corresponding DB/event assertions, not just a test name or a green total.

## Validation limits

The required STAGING HTTP POST p95 <=50 ms with 50 concurrent bidders and terminal bid latency <=2 seconds must remain unchecked until measured on STAGING. SRS throughput targets and a local passing load run do not certify that staging criterion. No GitLab CI result is inferred from local verification. Full cross-service FE type generation on the mixed local checkout may include unrelated older spec drift; this issue checks the auction contract and does not regenerate other service types.
## STAGING HTTP performance probe

Run `node docs/testing/measure-bid-queue.mjs <private-config.json> <result.json>` from the backend directory. The private input is JSON with `baseUrl`, `environment:"STAGING"`, `auctionId`, `tokens` (the authorized buyer tokens), `startAmount`, `increment`, and `count:50`. Keep that file outside the repository. Start with an ACTIVE auction whose current minimum is no greater than startAmount. Use 10 buyer tokens for the functional scenario or 50 for the performance scenario. The script sends real concurrent POSTs and polls each owner-only result every 250 ms, writes no token into the result, and exits nonzero if any request fails, POST p95 exceeds 50 ms or any terminal result exceeds two seconds. Polling adds up to one poll interval to the observed terminal latency. It neither certifies staging merely from the label nor substitutes local fixture numbers for a staging run.

## Recorded local SSE load — 2026-11-16

The real PostgreSQL18 / Kafka4.0 integration and packaged WebFlux gateway served 1,000 concurrent viewers for 60 bids at one bid per second. All 60,000 state changes arrived, with zero errors. BidPlaced-to-viewer p95 was 318.54 ms (max 380.84 ms); service accept-to-viewer p95 was 426.78 ms. The two-instance functional case delivered BidPlaced to both viewers in 293 ms, including the anti-sniping extension. Connections ramped in batches of 50; measurements started only after all 1,000 initial states were received. The driver calls the real production BidService and outbox/Kafka pipeline; it does not measure HTTP POST p95. See [JSON evidence](evidence/bid253/sse-local-load.json) and [measurement notes](evidence/bid253/sse-local-load.md).

Private bid-result polling uses the existing auction-read quota (600 requests per IP per minute by default), separately from the general 100-request POST quota. Authentication and bidder ownership remain enforced. This prevents result polling for an accepted burst from consuming the same quota as new bid submissions.

## ENDED stream review fix — 2026-11-17

Review of MR !187 at `ffce11bf8ccb059fa619a7be90ea20509bd01dbc` found that the backend closed its stream at ENDED but the frontend kept its EventSource alive because ENDED is not a completed settlement result. `useAuctionState` now uses a separate `stopStream` condition for ENDED or an existing terminal status. It closes an active source and does not open one for an already ENDED room. REST state/detail reconciliation and private winner/order polling retain their existing conditions; ENDED still means the result is pending.

The browser regressions observe native EventSource instances for an initially ENDED room and ACTIVE-to-ENDED transition, including a five-second observation window without reopening. The transition case checks that REST continues and obtains the subsequent winner/order result without another SSE event. Existing corrected-bid tests now wait for “Your bid was accepted.” before reading write evidence, retaining their amount, write-count and idempotency-key assertions. The reputation HTTP fixture supplies the required `ratingCount: 20` and `policyVersion: "reputation-policy-1"` fields; production reputation parsing and business rules are unchanged.

The reviewer's original-head results are a baseline, not successful final-suite evidence: auction-service reported 379 passed, one failure and nine errors; the actuator/refresh 403 was also reproduced on develop with a JWT fixture lacking refresh permission, and the errors came from two fixtures requiring Docker. The original auction browser suite passed 25 of 28 cases, with one reputation fixture failure and two corrected-bid evidence races. No Java source changed in this fix, and this session does not claim a fresh backend full-suite run.

Final checks on the fixed MR source with Node 24.22.3: `npm run test:unit` passed all 417 tests without skips; `npm run test:auction` passed all 30 browser cases in 3.7 minutes, including both new ENDED regressions and the existing winner, loser, shipping and payment handoffs. Both new stream regressions failed against the original production hook before the fix: the initial ENDED room retained an OPEN source after five seconds, and an ENDED push did not close it. Lint, typecheck, production build and the API inventory check (162 delivered operations) passed. Generated-type checking still reports drift only in User, Commerce and Shipment DTOs; auction DTOs are unchanged. These results do not make the combined full-service test criterion green: the backend full-suite baseline above remains unresolved and was not rerun.

The 16 November two-instance/Kafka and 1,000-viewer load figures above remain historical evidence and were not rerun for this fix. STAGING HTTP POST p95 and terminal bid latency remain unmeasured and unchecked. Local frontend fixtures do not establish live cross-service, Kafka, staging or remote CI acceptance.

## Develop conflict resolution — 18 November 2026

Merged develop `9e140179` into the existing reviewed source `e10ff0d4`. Gateway policies retain both the private bid result and all second-chance routes; path fixtures cover both bidId and offerId. The refresh integration fixture uses an admin token with SYSTEM.CONFIG.UPDATE while bid tokens retain BUYER/AUCTION.BID. The idempotency race barrier waits only for write transactions; obsolete synchronous-placement conflict helpers were removed. Frontend conflicts retain the 202/PLACED waits, complete reputation fixture and both ENDED stream regressions.

Develop V1–V9 SQL files are unchanged. The queue migration is now V10 with the exact original SQL body. BidQueueMigrationTest verifies a fresh V1–V10 database and a V9 upgrade with unchanged historical rows and Flyway checksums. Second-chance preview/send excludes QUEUED/REJECTED/CANCELLED requests and retains each bidder's highest accepted bid.

On the merged source, Java 21/PostgreSQL 18 focused auction checks passed 255 cases with zero failures, errors or skips, including both migration regressions, all 19 second-chance cases, refresh, queue rollback/idempotency, settlement, and ten repetitions of 50 simultaneous HTTP bids. Gateway full verify passed 91 cases and Config full verify passed 47. All auction main/test sources compile. API inventory passed all 182 operations. Frontend Node 24 unit tests passed 437 cases; lint, typecheck, generated-type check and production build passed. PostgreSQL used an isolated disposable local cluster; no application database or existing migration history was changed. Full auction verify, fresh Kafka/multi-instance SSE/load and STAGING latency measurements were not rerun by this conflict task.

The merged auction Playwright suite also passed all 30 cases (4.6 minutes), including both native EventSource ENDED regressions and the private winner/order handoff. Browser fixtures simulate HTTP/SSE responses and do not certify a live Kafka or staging deployment.
