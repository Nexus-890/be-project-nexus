# QA test report — #166

Date: 21–22 November 2026, Asia/Saigon. **NOT ACCEPTED: open failures and no STAGING walkthrough.** This report records local execution; it does not certify a release.

## Source and environment

- Full backend suites: existing local/integration/nexus-current working tree, HEAD fdb89f6a03453e210243ab69c73e9610239f43ad. The pre-existing integration edits are preserved. **This is a historical local integration baseline, not a full-suite result for the publication branch.** Source hash inventory for concurrency is in the linked evidence.
- Live browser/API walkthrough: separate archive of origin/develop 1ea5801e07de79eb4d007f85a7799a79bcc3029e at D:/SRS-project/qa166-latest-20261121; all 12 backend JARs packaged using a separate Maven cache. Package-only bootstrap skips are not counted as test passes.
- Windows, JDK 21.0.12.1, Maven 3.9.16, PostgreSQL 18, Kafka 4.3.1, Docker 29.8.0, actual Keycloak and installed Chrome. Secrets read from existing .env and kept out of repository evidence.
- Runtime uses nine new qa166_*_20261121 databases. Existing business databases and Flyway history were not reset or modified. Kafka is the local broker. Recovery scenarios use their own broker and two independent disposable PostgreSQL containers.
- Local laptop 16 GB RAM: full stack plus large test suites exhausted RAM; Docker restarted, causing transient database EOF. Testing and runtime were subsequently separated. Outbox multi-context tests also hit the shared database connection cap before runtime JVMs were stopped. Raw failed attempts are retained. The final dedicated Outbox verify passed 48/48 after using its own PostgreSQL and Kafka. Docker ports were restored while retaining the same PostgreSQL volume and restarting the other infrastructure containers. Runtime and test observations are identified separately.

## Coverage and every module

Recorded module-suite attempts: **2258 pass, 33 failure, 5 error, 9 existing skips** across 20 executable/test modules. The first 19 rows are the historical local integration baseline; receipt-completion-acceptance is an additional check on the publication source. A failed Surefire phase prevents later Failsafe tests in that module; the separate concurrency/recovery executions below are independently recorded. The lib parent is an aggregator with no executable lines (coverage N/A).

| Module | Pass | Fail | Error | Skip | LINE | verify | Follow-up |
|---|---:|---:|---:|---:|---:|---|---|
| lib/common-core | 0 | 0 | 0 | 0 | 0.00% (no data) | FAIL | [#274](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/274) |
| lib/common-web | 12 | 0 | 0 | 0 | 57.65% (49/85) | FAIL | [#275](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/275) |
| lib/common-security | 23 | 0 | 0 | 0 | 94.20% (130/138) | PASS | — |
| lib/common-events | 22 | 0 | 0 | 0 | 100.00% (62/62) | PASS | — |
| lib/common-outbox | 48 | 0 | 0 | 0 | 82.76% (96/116) | PASS | — |
| lib/common-test | 0 | 0 | 0 | 0 | 0.00% (no data) | FAIL | [#276](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/276) |
| config-service | 52 | 0 | 0 | 0 | 93.67% (355/379) | PASS | — |
| discovery-service | 1 | 0 | 0 | 0 | 33.33% (1/3) | FAIL | [#273](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/273) |
| api-gateway | 89 | 0 | 0 | 0 | 74.03% (456/616) | PASS | — |
| user-service | 241 | 6 | 0 | 0 | 92.78% (745/803) | FAIL | [#277](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/277) |
| catalog-service | 131 | 0 | 0 | 0 | 75.15% (644/857) | PASS | — |
| commerce-service | 466 | 0 | 0 | 5 | 91.65% (1438/1569) | PASS | — |
| auction-service | 387 | 0 | 0 | 0 | 86.58% (858/991) | PASS | — |
| inventory-service | 125 | 3 | 0 | 0 | 96.75% (387/400) | FAIL | [#278](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/278) |
| notification-service | 183 | 0 | 0 | 0 | 89.04% (959/1077) | PASS | — |
| payment-service | 258 | 19 | 0 | 3 | 96.44% (785/814) | FAIL | [#279](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/279) |
| shipment-service | 201 | 0 | 0 | 0 | 86.21% (569/660) | PASS | — |
| rating-service | 3 | 0 | 0 | 0 | 77.65% (66/85) | PASS | — |
| infra/db-verification | 9 | 5 | 2 | 0 | N/A | FAIL | [#280](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/280) |
| infra/receipt-completion-acceptance | 7 | 0 | 3 | 1 | N/A (test-only) | FAIL | [#282](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/282) |

The inherited JaCoCo plugin in lib/pom.xml binds prepare-agent and report/check at verify, with BUNDLE/LINE/COVEREDRATIO minimum 0.70. Failsafe is inherited so *IT is included. A verify guard fails modules containing production classes without jacoco.exec; no-data modules above are reported 0%, with no invented numerator. Both infra/db-verification and infra/receipt-completion-acceptance are test-only modules with no production coverage denominator.

Proof: discovery-service clean verify runs its context test and then fails with actual LINE 1/3 versus 0.70. See [retained build failure](testing/evidence/qa166/coverage/below70-verify-failure.txt). No test or class was excluded to reach the threshold. Existing skip counts remain visible; they are not passes. No test was newly disabled.

The runner also includes the newly discovered receipt acceptance module. Its four plain service artifacts must first be installed as documented in that module README. The runner preserves caller Maven repository/proxy options.

Run [run-quality-suite.ps1](testing/run-quality-suite.ps1) with JDK 21 and Docker. It executes clean verify for each selected module, preserves failing exit codes, and extracts JUnit/JaCoCo evidence. A report goal after failed tests only recovers measurements and does not turn verify green. Shared-library installation may need explicit -DskipTests -Djacoco.skip=true install solely for dependency bootstrap while low-coverage issues remain; this is not verification. OutboxIT additionally requires its documented dedicated PostgreSQL/Kafka environment and an owned broker name beginning nexus-outbox-test-.

## Verification on the publication source

The publication checkout starts from origin/develop1ea5801e and applies only the QA changes. On22 November2026 (Asia/Saigon), focused checks passed: Recovery3/3, seller enum2/2, ReservationConcurrencyIT10/10 and BidConcurrencyIT10/10. Discovery clean verify again failed specifically at JaCoCo LINE1/3<0.70. See [publication evidence](testing/evidence/qa166/publication/README.md). These checks do not rerun all20 module suites on one commit.

The first bid attempt on current develop failed10/10 with AUCTION_REPUTATION_UNAVAILABLE because the fixture had no reputation decision after enforcement became default-on. Under #166 the fixture now provides valid EligibilityClient decisions, explicitly keeps the real reputation guard enabled and verifies its calls. All HTTP/JWT/queue/DB assertions remain. The full10 repetitions then passed. Fixture diagnostics now omit signed test JWTs.

The additional receipt acceptance suite ran11 cases:7passed,3fixture errors and1existing opt-in GHN sandbox skip. Its three receipt-email cases lack the now-required NotificationPreferenceService in the fixture. Tracked by [#282](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/282); the failed suite is retained as a failure.

Gateway duplicate-method and Commerce Kafka-topic repairs described below were local integration repairs; current develop already includes the corresponding resolved setup, so those files are not copied wholesale into this MR. The seller enum assertion was already corrected in develop; this MR adds explicit default/invalid-value checks to that existing test.

## Concurrency

**20/20 race repetitions passed, zero failures/errors/skips.**

| Test | Repetitions | Requests each | Outcome |
|---|---:|---:|---|
| ReservationConcurrencyIT | 10 | 100 | Each 10 HTTP201/90 HTTP409; 10 reservation rows; available0/reserved10/total10; movement and uniqueness assertions pass |
| BidConcurrencyIT | 10 | 50 | All 50 queued, queue fully drained, final highest 3,500,000 VND; leader/count/outbox/strictly increasing accepted updates asserted |

[Per-race evidence and scope](testing/evidence/qa166/concurrency/README.md), [rerun script](testing/run-concurrency-reruns.ps1). Each existing test uses @RepeatedTest(10); the selected Maven invocation was run once per class, so the requirement is 10 races per test rather than 100. Reservation uses actual HTTP/PostgreSQL with mocked JWT decoding. Auction uses actual HTTP/RSA JWT/PostgreSQL and invokes the actual command consumer directly; that test does not verify broker transport. Recovery below exercises actual broker transport.

## Settlement crash recovery

AuctionSettlementRecoveryIT starts the packaged production Commerce/Auction JARs in separate JVMs. It force-kills processes and verifies SQL committed state, consumer offsets and matching broker records. The focused execution passed 3/3 with zero failures/errors/skips.

| Scenario | Result | Auction id | Order id | SQL order count |
|---|---|---|---|---:|
| commerce-crash-before-publish | PASS | eb1d0254-a3a2-4c53-b495-a9e429c573cc | 3aa99c3e-8507-4d3d-a776-9830793b6e02 | 1 |
| auction-won-delivered-three-times | PASS | ab8aaa2a-73c4-4313-8a98-747f035a1459 | 22809249-56e3-42d1-974b-4f3dbce6f74d | 1 |
| auction-offline-during-order-created | PASS | 654c4cfa-4707-4893-9269-35220e7aa07b | 2a10c2f4-4a10-4e17-bac3-e8e196ea8112 | 1 |

For all three scenarios the auction reaches SETTLED, links the exact order, and has one AuctionSettled outbox event. Commerce has one OrderCreated and broker evidence independently counts exactly one matching OrderCreated. Triple delivery waits for all three records to be consumed before asserting. The crash scenario observes committed order + pending event + zero corresponding broker records before forcibly killing Commerce; restart removes the pending obligation. Auction-offline replay preserves the settlement group's committed warm-up offset. [Reproduction and design](testing/settlement-crash-recovery.md), [scenario evidence](testing/evidence/qa166/recovery/commerce-crash-before-publish.json). These IDs belong to disposable recovery DBs and are not STAGING walkthrough IDs. Child process work is not added to JaCoCo coverage.

## Cross-service browser/API walkthrough

All 12 backend processes started from latest develop; Config/Discovery and all 9 domain services reported UP. Eureka listed all 9 domain services plus Gateway UP. Gateway protects /actuator/health with HTTP401; successful public/private API calls verify actual forwarding. Discovery's first startup was attempted before Config was ready, then restarted successfully; this startup-order mistake is not a product defect.

Installed Chrome rendered actual catalog products, signed in through Keycloak PKCE and Gateway/session, and displayed the persisted cart and checkout. Browser pageerror count 0. Captured API calls include session200, profile200, cart200, inventory200, pricing200, notifications200 and addresses200. See [sanitized browser result](testing/evidence/qa166/runtime/ui-result.json) and [checkout screenshot](testing/evidence/qa166/runtime/checkout-live.png).

| Flow | Actual result | IDs / blocker |
|---|---|---|
| Purchase from fresh seed | Catalog/cart succeed; create order409 COMMERCE_SHIPPING_METADATA_REQUIRED | No order/group created; [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281) |
| Auction from fresh seed | Create409 AUCTION_STOCK_UNAVAILABLE | No auction created; [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281) |
| Owning SELLER fixture preparation | PUT SKU parcel200 and POST inventory/intake201 on isolated QA DB | Product69000000-0000-4000-8000-000000000201, SKU01a1fef1-ef0d-710d-b169-7c94cbfa9f5e |
| Purchase after fixture setup | Valid preview and create503 COMMERCE_SHIPPING_UNAVAILABLE; shipping/provinces503 | Carrier/pickup configuration missing; [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281) |
| Auction after fixture setup | Create503 AUCTION_REPUTATION_UNAVAILABLE | Latest develop enables reputation enforcement; required service credential absent; [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281) |
| Voucher UI | BUYER GET /vouchers/me403, visible missing-privilege feedback | [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281) |
| STAGING purchase and auction | BLOCKED | #162 has no verified deployedURL; no STAGING ids fabricated |

The initial preview400 used an incomplete diagnostic request and is retained as a negative control, not reported as a backend defect. The later preview uses the actual DTO. No guard was disabled to force a successful flow; no order/payment/auction status was patched in SQL. No bank transfer or carrier booking was made. Runtime auction duration was lowered to 1 minute only for this explicitly local fixture; the request still failed reputation validation before creating an auction.

## Known broken and fixes

| Finding | Tracking / status |
|---|---|
| Four modules below 70% | [#273](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/273), [#274](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/274), [#275](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/275), [#276](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/276) |
| Five real Keycloak email-change failures | [#277](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/277) |
| Three Inventory refresh403 failures | [#278](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/278) |
| Nineteen Payment assertions against new unreconciled event contract | [#279](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/279) |
| Three receipt acceptance fixture errors | [#282](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/282) |
| Five database upgrade preservation failures and two fixture/migration errors | [#280](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/280) |
| Missing runtime dependencies, seed and voucher privileges | [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281) |
| Gateway duplicate test methods blocked compilation | Fixed under #166 by removing only identical duplicate copies; full clean verify 89/89 passes, LINE 74.03% |
| Seller enum missing from exact fresh-schema expectation | Fixed under #166, retains exact enum inventory plus new seller default/invalid-value assertions; focused 2/2 passes. Full User baseline above remains the earlier 247-case run |
| Commerce embedded brokers omitted Auction topic after settlement consumer was added | Fixed under #166 by provisioning Topics.AUCTION in both fixtures; no listener disabled. Final full-suite result is in the table |
| Test host RAM/PG connection exhaustion and Docker restart | Environment constraint under #166/#162; failed attempts retained. Reruns limit Spring context cache to 2 and separate heavy tests from live runtime |
| Next 16.4.0 printed a security advisory reminder | Recorded under [#281](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/281); version unchanged in this QA task |

Full raw logs/XML/HTML remain in D:/GitLab/log/qa166-20261121; compact reviewable results are [results.json](testing/evidence/qa166/coverage/results.json) and [summary.csv](testing/evidence/qa166/coverage/summary.csv). Newly created tracking issues 273–282 contain concrete reproduction commands; they are not counted as fixes. No CI pipeline was available when MR !197 was refreshed. Historical matrix results are not a remote CI result.

## Acceptance disposition

Coverage enforcement, below-rule proof, per-module reporting, issue tracking,10+10 concurrency repetitions and three real crash/restart scenarios are delivered locally. #166 remains open: all below 70 modules and functional/test failures must be fixed and their full suites rerun; one complete purchase and auction on actual STAGING with real IDs is still required. No final run free of open High/Critical failures is claimed. This report and code are submitted for review; no merge/release approval is implied.


Checklist and capture guidance: [qa166-checklist.md](testing/qa166-checklist.md).
