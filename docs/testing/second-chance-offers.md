# #208 payment timeout and second chance offers

The original auction and each offer have independent orders/reservations/outcomes. `SETTLED` still means the order exists, rather than payment confirmed. Auction timeout never changes stock; Inventory consumes the same cancellation.

## Run the checks

Use JDK 21. PostgreSQL 18 is required; set `TEST_POSTGRES_ADMIN_URL` to a disposable local admin database or use Docker. The fixtures create isolated databases/schemas. For `SecondChanceEmailIntegrationTest`, optionally set `TEST_MAILPIT_HTTP_URL` and `TEST_MAILPIT_SMTP_PORT` to a dedicated Mailpit instance; otherwise Docker starts one. Do not point that fixture at a shared mailbox: it clears messages between tests.

```powershell
mvn -B -f lib/pom.xml install
mvn -B -f auction-service/pom.xml "-Dtest=SecondChanceOfferIntegrationTest,SettlementIntegrationTest,SettlementStatusUpdaterTest,SettlementStatusUpdaterUnitTest,AuctionStateMachineTest" test
mvn -B -f inventory-service/pom.xml "-Dtest=AuctionStockIntegrationTest" test
mvn -B -f commerce-service/pom.xml "-Dtest=AuctionSettlementTest" test
mvn -B -f rating-service/pom.xml "-Dtest=ReputationEngineIntegrationTest,MigrationUpgradeTest" test
mvn -B -f notification-service/pom.xml "-Dtest=SecondChanceEmailIntegrationTest,NotificationTemplateTest,DomainEventConsumerTest" test
node docs/api/check-contract.mjs
```

Commerce's narrow settlement fixture also supports `-Dsettlement.test.jdbc-url=jdbc:postgresql://localhost:5432/postgres`. It creates a unique schema. These direct-consumer fixtures prove transactions/replay on a real database; they do not by themselves prove broker transport or live identity-provider integration.

## API and manual flow

1. Seed at least three buyers who bid on one auction. Let the first winner's unpaid order expire. Compare `orders.status`, `auctions.status`, `auction_payment_timeouts` and outbox: CANCELED, PAYMENT_TIMEOUT and exactly one AuctionPaymentTimeout. Replay the same event and another eventId for the same order; state and penalty remain unchanged. STANDARD or unrelated cancellation reasons do not produce a timeout.
2. GET `/api/v1/auctions/{id}/second-chance-offers` as its seller. Read the masked candidate and their own maximum bid. No offer row, stock hold or email should be created by the read. POST that path with no body to send. A second concurrent send returns OFFER_ALREADY_OPEN. A seller who does not own the auction gets 403.
3. Inspect the new offer hold: available 5 -> 4. The offer has `stockReady=false` until Inventory acknowledges the hold; accept during that interval returns OFFER_STOCK_PENDING. Notification emails the bidder only after readiness. If the prior order cancellation is still in transit, `inventory_pending_offer_holds` retries after its old hold releases. If stock is gone, the offer expires without penalty and no bidder invitation is sent.
4. GET `/api/v1/auctions/offers/me` as the selected buyer; lists use `data` plus sibling `page`. POST `/api/v1/auctions/offers/{offerId}/decline` or let `expiresAt` pass. The corresponding event returns the hold (4 -> 5), emails the seller and adds no penalty. The seller can now select the next eligible bidder. A late/repeated response returns 409; another buyer gets OFFER_NOT_YOURS.
5. Accept another offer. It becomes ACCEPTED, auction WON, with deadline from acceptance plus `nexus.auction.payment-deadline-hours`. `orderId` is null while Commerce consumes AuctionWon. Refresh offers until OrderCreated projects the real order and auction is SETTLED. Original canceled order and new PENDING order share referenceId but have distinct IDs/keys; only the latter has this offerId.
6. Confirm the real order's address through `/api/v1/orders/{id}/shipping-confirmation`, initialize payment for its group and use the normal signed SePay webhook. Confirm Commerce PAID and the new hold CONFIRMED. Replayed payment/cancellation/settlement events must not touch another round's hold. Do not use the offer amount alone as bank total if shipping is charged.
7. On a separate offer, accept then allow the new order to time out. That bidder receives one penalty, the first winner remains excluded, and the seller can offer again. Test both A-timeout/B-timeout and A-timeout/B-completed: Rating accepts multiple order outcomes for one auction. Migrating pre-#208 penalty rows must preserve their effect and replay protection.

## Data to inspect

```sql
-- auction_db
SELECT a.id,a.status,a.winner_id,a.order_id,a.offer_id,o.bidder_id,o.amount,
       o.status AS offer_status,o.stock_ready,o.expires_at,o.order_id AS offer_order
FROM auctions a LEFT JOIN auction_offers o ON o.auction_id=a.id
ORDER BY o.offered_at DESC LIMIT 20;
SELECT * FROM auction_payment_timeouts ORDER BY timed_out_at DESC LIMIT 20;
-- commerce_db
SELECT id,reference_id,offer_id,user_id,status,total_amount,payment_due_at
FROM orders WHERE order_type='AUCTION' ORDER BY created_at DESC LIMIT 20;
-- inventory_db
SELECT auction_id,offer_id,order_id,auction_winner_id,status,quantity
FROM inventory_reservations WHERE auction_id IS NOT NULL ORDER BY created_at DESC LIMIT 20;
-- rating_db
SELECT order_id,auction_id,outcome FROM reputation_outcomes;
SELECT user_id,change_type,source_id,delta FROM reputation_history
WHERE change_type='AUCTION_PAYMENT_TIMEOUT';
```

Use Mailpit to inspect bidder invitation and seller acceptance/decline/expiry messages. Browser fixture suites are `npm run test:auction`, `npm run test:auction-list` and the seller checks under `playwright.backoffice.config.ts`; report these separately from requests to running backend services. Runtime, signed provider fixture and actual bank-transfer evidence must remain distinct.
