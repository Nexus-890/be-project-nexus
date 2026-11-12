# Configure and cancel an auction (#197)

Routes: `PUT /api/v1/auctions/{id}`, `POST /{id}/cancel`, `POST /{id}/admin-cancel`.
Success returns `ApiResponse<AuctionResponse>`; failures use Problem Details.
Domain failures use `AUCTION_*`; shared authentication, binding and validation failures retain the ADR-008 common codes.

## Rules and transaction

PUT requires AUCTION.UPDATE, SELLER/ADMIN and ownership. Only CREATED/SCHEDULED auctions whose stored startAt is still in the future can be edited. Starting price and increment must be positive decimal amounts. Duration uses AUCTION_MIN_DURATION_MINUTES (default 60) and AUCTION_MAX_DURATION_HOURS (default 168). Configure updates currentPrice and originalEndAt with the new pre-bidding baseline; anti-sniping must not call configure.

Seller cancellation requires SELLER, AUCTION.CANCEL, ownership and no valid bid. PLACED/OUTBID/WON bids count; CANCELLED bid history does not. Admin cancellation requires ADMIN and AUCTION.ADMIN_CANCEL, with no ownership/no-bid restriction. Non-final states are CREATED/SCHEDULED/ACTIVE/ENDED/WON; SETTLED/FAILED/PAYMENT_TIMEOUT/CANCELLED reject cancellation. SRS 3.5.1 defines eligibility in terms of business rules and does not enumerate final states; this policy follows the issue's non-final requirement and the lifecycle boundaries. SETTLED remains eligible for PAYMENT_TIMEOUT through the existing settlement flow.

Lock the auction row before checking eligibility. State machine transition, cancelledBy (SELLER/ADMIN), actor UUID, trimmed reason, time, entity version and AuctionCancelled outbox insertion commit together. A failed outbox insertion rolls everything back. A repeated cancellation returns 409 and writes no second event. Never accept the cancellation actor from the request body.

Bid conditional UPDATE and scheduler row selection contend on the same row lock. Bid-first means a seller cancellation sees the committed bid and fails. Cancel-first means a conditional bid requiring ACTIVE cannot update the row. A CANCELLED row is excluded from scheduler selection. Settlement must likewise lock/recheck state and never create results from CANCELLED; event delivery and commerce transactions are separate and require integration verification with #170–#173.

## Automated verification

Use Java 21. Default database fixtures use Testcontainers PostgreSQL 18. Alternatively set TEST_POSTGRES_ADMIN_URL to a local PG18 admin JDBC URL, TEST_POSTGRES_USERNAME and TEST_POSTGRES_PASSWORD. Each fixture creates a randomly named disposable database and drops it on JVM shutdown; never point tests at the application database.

```powershell
mvn -B -f auction-service/pom.xml verify
mvn -B -f api-gateway/pom.xml verify
mvn -B -f config-service/pom.xml verify
node docs/api/check-contract.mjs
```

AuctionConfigureCancelIntegrationTest drives the real controller/security chain, service, PostgreSQL migrations/JPA and outbox. JWT decoding and Clock are mocked; Kafka relay is disabled. It covers edit/status/time/ownership, min/max overrides (90 minutes and 3 hours), valid versus cancelled bids, admin cancellation with bids, audit/versioned payload, repeat requests, concurrency and outbox rollback. AuctionSchedulerIntegrationTest runs two scheduler instances and verifies an expired CANCELLED row remains unchanged without AuctionEnded.

## HTTP/DB acceptance evidence

1. Create and schedule an auction as the owner; PUT new price/increment/times. Read it back and verify currentPrice and originalEndAt. Repeat against ACTIVE: 409 AUCTION_NOT_EDITABLE, unchanged DB row.
2. Cancel as owning seller with no valid bid: 200, CANCELLED and correct audit. With a valid bid: 409 AUCTION_HAS_BIDS and no cancellation event.
3. Cancel an ACTIVE auction with bids as admin: 200, cancelledBy ADMIN, actorId from JWT. Check exactly one outbox AuctionCancelled; payload auctionVersion must equal auctions.version. Repeat: 409, still one event.
4. With the actual #146 bid implementation composed in an isolated checkout, submit a bid after cancellation: reject it and verify no new bids/BidPlaced. SQL-only concurrency probes do not prove the HTTP bid route.
5. With #170–#173 available, run the actual settlement worker/consumer after cancellation and verify no winner/order/settlement is produced. Absence of these implementations is not a passing integration test. Keep the final issue AC unchecked until this is verified.

```sql
SELECT id, status, cancelled_by, cancelled_actor_id, cancelled_reason, cancelled_at, version
FROM auctions WHERE id = :auction_id;
SELECT event_type, topic, aggregate_id, payload
FROM outbox WHERE aggregate_id = :auction_id::text;
SELECT id, status, amount FROM bids WHERE auction_id = :auction_id;
```

Postman collection/environment must contain no saved bearer token, password or service secret. Evidence must distinguish tests on #197 alone, composed dependency tests and live application requests.
