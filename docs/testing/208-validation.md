# #208 validation

Implementation head a44718873386034c8902af025fff2b32a55e3345; compared with develop e4cb53cb8fee3ce827f21c6f67f73d879aefb564.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| auction-service | 374 | 0 |
| inventory-service | 160 | 0 |
| commerce-service | 542 | 5 |
| notification-service | 187 | 0 |
| rating-service | 147 | 0 |
| config-service | 47 | 0 |
| api-gateway | 89 | 0 |

See the issue handoff comment for the per-item acceptance mapping. Database tests use isolated PostgreSQL 18.6. SMTP uses dedicated Mailpit. Notification health tests use native Redis 5.0.14.1; default Docker paths retain their configured versions.

The runtime result below uses packaged Auction, Commerce, Inventory, Rating, Notification and Payment through a real Kafka KRaft broker and PostgreSQL. Identity/JWKS/recipient lookup, Catalog and shipping quote are controlled HTTP fixtures; SePay is a signed webhook fixture, not a real bank transfer. The Postman collection is prepared but has not itself been run live.

## Frontend and contract

- 422 unit tests passed; generated-type check, ESLint, TypeScript and Webpack production build passed.
- Required buyer Playwright suites passed: auction room 26/26, auction list/My Bids/offers 13/13. The latter includes the pending orderId projection case.
- Broader back-office simulation: 28 passed, 1 failed after retry. The product-listing/stock/checkout case at tests/backoffice.spec.ts:85 exceeded its 150-second timeout. All second-chance seller cases, including hiding the action without a next bidder and offering again after an accepted order times out, passed. This broader timeout is retained as a limitation; it is not counted as green.
- Controller/OpenAPI inventory: 180 operations matched. Auction OpenAPI lint: zero errors, twelve warnings (metadata, path ambiguity and existing example validation warnings).
- Five Commerce CatalogEnvelopeHttpTest scenarios were skipped because CATALOG_CONTRACT_SIMULATION_URL was unset. They are separate from the tested offer/order/stock path and are not reported as live integration passes.
- Postman collection/environment JSON, scripts and variable references were validated; all token values are blank. The collection itself was not run live.

## Final runtime proof

The [unaltered runtime result](208-runtime-result.json) was emitted by the harness for source a44718873386034c8902af025fff2b32a55e3345. The harness asserts the following against running service processes and their databases:

| Scenario | Observed result |
| --- | --- |
| Seller preview | Candidate B's own maximum bid 4,800,000; no offer row or stock movement from GET. |
| Decline, expiry, then accept | Three distinct offer rounds, available/reserved/unavailable sequence 5/0/0 → 4/1/0 → 5/0/0 → 4/1/0 → 5/0/0 → 4/1/0 → 4/0/1. |
| New order and payment | Accepted D's amount 4,600,000, fresh payment deadline; distinct order for the same auction reference; address confirmation and signed SePay webhook lead to PAID and CONFIRMED stock. Payment replay and original-order cancellation replay leave two orders and the same stock. |
| Accepted offer times out | The new winner receives one penalty, the first winner remains excluded, the next bidder is available and stock returns to 5/0/0. Decline/expiry applies no penalty. |
| Hand-published cancellation | A separately seeded auction/order fixture receives STANDARD cancellation without changing state; AUCTION deadline cancellation transitions it once. Replaying the envelope and a new event ID for the same order leave one AuctionPaymentTimeout and unchanged stock. |
| Email | Real SMTP deliveries in Mailpit include bidder invitations and seller acceptance/decline/expiry messages. |

The hand-published case uses fixture auction/order data. The main offer flow uses real AuctionWon, OrderCreated, AuctionSettled, OrderCancelled, payment and inventory consumers through Kafka. Auth/JWKS/OAuth recipient lookup, Catalog and shipping quote remain controlled HTTP fixtures; the SePay transaction is a signed webhook fixture. This proves the local service integration described above, not a real bank transfer or full live Keycloak/Gateway/STAGING deployment.

## Reproduction and version boundaries

See [the runbook and SQL](second-chance-offers.md). Use clean Maven resources after switching from an intermediate branch migration: Commerce's final offer migration is V19; V18 on develop is the delivery index. Tests that block a SQL writer need an additional connection for the observer; keep a sufficient pool and bound cached Spring contexts when sharing one native PostgreSQL.

The default Docker test paths remain available. Native test infrastructure here used PostgreSQL 18.6, Kafka 4.2.1 KRaft, Redis 5.0.14.1 and Mailpit. Docker-specific PostgreSQL pause is represented by an actual exclusive JDBC event-log table lock in native mode; Kafka health timing and queued-event assertions are unchanged.
