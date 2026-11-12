# Bid concurrency regression (#148)

`BidConcurrencyIT` sends 50 real HTTP bids from exactly ten buyer identities (five
requests each), released by one latch. `@RepeatedTest(10)` creates a fresh ACTIVE
auction for every run. All 50 amounts are distinct, from 1,050,000.00
to 3,500,000.00 VND, separated by exactly one 50,000.00 increment. A seeded shuffle
varies the request submission order in each repetition. Every request has its own UUID
`Idempotency-Key`, so the test also remains usable when #147 is integrated.

## Requirements and scope

- SRS v1.0, section 3.5.2: atomic placement and concurrent bid handling.
- Endpoint catalogue, `Endpoints!A108:X108` / BID-01:
  `POST /api/v1/auctions/{id}/bids`, BUYER with `AUCTION.BID`.
- Delivered #146 OpenAPI and ADR-008: success envelope and named Problem Details.
- [Issue #148](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/issues/148).

As checked on 11 November 2026, #146 / !129 is still open and unmerged. The QA
branch now includes #146 commit `882be7d4`, including `AuctionStatus` from #144
and the bid time-window guard. It contains no #147 changes.
Reconcile with `develop` after #146 merges before marking the dependency complete.

The test uses the actual embedded HTTP server, security filter and method
authorization, controller, service, repositories, transactions, Flyway migrations,
Hibernate schema validation, outbox writer and PostgreSQL 18. Test buyers use
locally signed RSA JWTs and a test decoder validating signature, issuer and expiry.
An external Keycloak server, gateway, Kafka broker and outbox relay are not needed.
This test does not measure throughput or latency.

## Four invariants

1. Every 201 maps to a strictly increasing winning update. A test-only PostgreSQL
   AFTER UPDATE trigger records OLD/NEW prices, counts and the bidder, ordered by
   an identity sequence. Updates of one auction hold the same row lock until
   commit, making this an observation of database update order. Client response
   completion time, timestamps, UUID order and sorted prices are not used as a
   substitute for that order. The observer neither rejects nor serializes bids
   beyond the existing auction row lock.
2. `auctions.bid_count` equals the number of HTTP 201 responses.
3. `auctions.current_price` equals the highest accepted amount.
4. `SELECT count(*) FROM bids WHERE auction_id = ?` equals the number of 201s.
   Exact bid IDs, bidders and amounts must also match the accepted responses,
   exposing orphan or missing rows even if their counts cancel each other out.

The test also checks the auction version, final leader, one `BidPlaced` outbox
entry per accepted bid, and that rejected requests return 400
`AUCTION_BID_TOO_LOW` with a minimum above the rejected amount. It permits one
accepted bid: the maximum amount can legitimately acquire the row first.

## Run locally

Use Java 21, Maven and a running Docker daemon. Install `lib` first, then run
`verify` **from the auction-service directory** (existing tests import sibling
config-service files relative to that directory).

```powershell
Set-Location be-project-nexus/lib
.\mvnw.cmd -B '-Duser.timezone=UTC' install
Set-Location ../auction-service
.\mvnw.cmd -B '-Duser.timezone=UTC' clean verify
```

Failsafe runs `*IT` during `verify` and fails if no integration tests are found.
The shared `common-test` base starts real PostgreSQL and fails if Docker is absent.
Surefire and Failsafe use UTC to avoid PostgreSQL rejecting the Java timezone alias
`Asia/Saigon`. No H2 fallback or Docker-based test skipping is configured.

The MR-specific `.gitlab-ci.yml` has been removed, following the team's removal
in `4cad8cb6` and review note 262857 on !134. Pipeline #41794 / job #123900 failed
with `stuck_pending_no_matching_runners`, `runner=null`, without starting tests.

The issue still requires green CI. Proposed replacement, pending PO approval:
"The test uses `@RepeatedTest(10)` and passes all ten repetitions locally against
real PostgreSQL; attach the command, environment and test logs to the MR."
Keep the existing CI AC unchecked until PO confirms the change. Removing the CI
file and passing locally do not by themselves satisfy that criterion.

## Red proof

[Actual red result](evidence/bid148/red-result.txt) records ten failed repetitions,
zero errors and no skips. It includes an actual invariant assertion failure and
the Maven summary for the updated 50 distinct amounts.

[Mutation patch](evidence/bid148/read-then-write.patch) replaces only
`AuctionRepository.acceptBid`: SELECT and validate a snapshot, then unconditionally
overwrite the price and count from that snapshot. It uses `AuctionStatus.ACTIVE`
and retains seller, amount and transaction-time window validation on the snapshot.
It bypasses no tests, adds no
sleep, and retains the normal transaction, bid insert and outbox paths. The
SHA256 of the unchanged test is recorded with the result. The production
repository was restored byte-for-byte after the run.

To reproduce, check out `fsa_KhangTd_02/QA/148-qa-bid-concurrency-test-50-bids`.
Apply the patch from that checkout's repository root with `git apply`, run the
following command from auction-service, then restore the patch with
`git apply -R` from the root. Always restore it, including after an unexpected
error, and run the normal green `verify` afterward.

```powershell
.\mvnw.cmd -B '-Dit.test=BidConcurrencyIT' test-compile failsafe:integration-test failsafe:verify
```

The unsafe patch is evidence only and must never be applied to production code.
The local green result is recorded in [green-result.txt](evidence/bid148/green-result.txt).
Both results record the same test SHA256. The mutation is reversed and the
production repository checked for byte equality before the restored green run.
The result excerpts include the actual Maven output; full local logs and JUnit
reports are retained under `D:\GitLab\log\bid148-review-20261111`.

## Scope audit — 11/11/2026

MR !134 is stacked on the #146 feature branch until !129 merges. Its diff
contains only #148 QA, common-test, Maven setup and evidence. It does not add
bid idempotency (#147) or receipt completion (#204).

The test retains `management.tracing.export.enabled=false`. Its four invariants,
HTTP/security path, ten bidders and simultaneous release remain unchanged; the
amount generator now uses 50 distinct levels rather than ten duplicated levels.
The accepted count remains schedule-dependent: the highest amount may win first,
so no fixed minimum above one is required. This is a correctness regression,
not a claim that all 50 bids should succeed or a throughput benchmark.

After #146 merges, retarget !134 to develop and recheck its diff. The local/log
AC replacement remains a proposal until PO approves it on #148.
