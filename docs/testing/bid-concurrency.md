# Bid concurrency regression (#148)

`BidConcurrencyIT` sends 50 real HTTP bids from exactly ten buyer identities (five
requests each), released by one latch. `@RepeatedTest(10)` creates a fresh ACTIVE
auction for every run. Mixed amounts include duplicate levels from 1,050,000.00
to 1,500,000.00 VND, with a 50,000.00 increment. Every request has its own UUID
`Idempotency-Key`, so the test also remains usable when #147 is integrated.

## Requirements and scope

- SRS v1.0, section 3.5.2: atomic placement and concurrent bid handling.
- Endpoint catalogue, `Endpoints!A108:X108` / BID-01:
  `POST /api/v1/auctions/{id}/bids`, BUYER with `AUCTION.BID`.
- Delivered #146 OpenAPI and ADR-008: success envelope and named Problem Details.
- [Issue #148](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/issues/148).

As checked on 7 November 2026, #146 / !129 is still open and unmerged. This local
implementation is based on #146 commit `486367f`; it contains no #147 changes.
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

## Run locally and in CI

Use Java 21, Maven and a running Docker daemon. Install `lib` first, then run
`verify` **from the auction-service directory** (existing tests import sibling
config-service files relative to that directory).

```powershell
Set-Location be-project-nexus/lib
.\mvnw.cmd -B '-Duser.timezone=UTC' install
Set-Location ../auction-service
.\mvnw.cmd -B clean verify
```

Failsafe runs `*IT` during `verify` and fails if no integration tests are found.
The shared `common-test` base starts real PostgreSQL and fails if Docker is absent.
Surefire and Failsafe use UTC to avoid PostgreSQL rejecting the Java timezone alias
`Asia/Saigon`. No H2 fallback or Docker-based test skipping is configured.

The root `.gitlab-ci.yml` installs shared libraries and runs auction `verify` with
Java 21 and Docker-in-Docker, retaining JUnit reports and Failsafe evidence even
on failure. It requires a runner allowing privileged Docker services. CI Lint
returned `valid: true`, no errors or warnings. The runner-list API returned 403;
runner availability and an actual green remote pipeline remain unverified.

## Red proof

[Actual red result](evidence/bid148/red-result.txt) records ten failed repetitions,
zero startup/errors and no skips. The first run accepted 50 HTTP bids but left
`bid_count = 1` and `current_price = 1150000.00`, although the highest accepted
amount was `1500000.00`.

[Mutation patch](evidence/bid148/read-then-write.patch) replaces only
`AuctionRepository.acceptBid`: SELECT and validate a snapshot, then unconditionally
overwrite the price and count from that snapshot. It bypasses no tests, adds no
sleep, and retains the normal transaction, bid insert and outbox paths. The
SHA256 of the unchanged test is recorded with the result. The production
repository was restored byte-for-byte after the run.

To reproduce, apply the patch from the repository root with `git apply`, run the
following command from auction-service, then restore the patch with
`git apply -R` from the root. Always restore it, including after an unexpected
error, and run the normal green `verify` afterward.

```powershell
.\mvnw.cmd -B '-Dit.test=BidConcurrencyIT' test-compile failsafe:integration-test failsafe:verify
```

The unsafe patch is evidence only and must never be applied to production code.
The local green result is recorded in [green-result.txt](evidence/bid148/green-result.txt).
Local execution and CI lint do not satisfy the acceptance criterion requiring a
green GitLab pipeline. Link this document and the red result from the #148 MR;
leave that CI criterion unchecked until the real pipeline passes.

## Scope audit — 08/11/2026

MR !134 is stacked on the #146 feature branch until !129 merges. Its diff
contains only #148 QA, common-test, Maven/CI setup and evidence. It does not add
bid idempotency (#147) or receipt completion (#204).

The existing one-line tracing configuration fix is preserved:
`management.tracing.export.enabled=false`. The red/green evidence above records
the earlier test revision at `fecaf00`; its assertion and request logic are
unchanged by this configuration fix. The updated test compiles, but PostgreSQL
reruns on 08/11 were blocked by Docker Desktop engine startup. Do not treat the
historical evidence as a new run of this revision. Pipeline #41794 failed before
execution with `stuck_pending_no_matching_runners`; a green remote CI run remains
outstanding. After #146 merges, retarget !134 to develop and recheck its diff.