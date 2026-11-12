# Seller auctions and buyer bid participation (#198)

`GET /api/v1/auctions/mine?status=&page=0&size=10` requires SELLER and AUCTION.LIST.
Omit status rather than sending an empty string to read all statuses. The caller is always
the JWT subject; a sellerId query parameter cannot select another account. Own CREATED,
CANCELLED and RESTRICTED auctions are included; soft-deleted auctions are excluded.
Sort: createdAt DESC, id DESC. Public list reads hide CREATED/CANCELLED even when explicitly filtered.
This does not change the existing public detail policy.

`GET /api/v1/bids/mine?page=0&size=10` requires BUYER and AUCTION.VIEW_BID_HISTORY.
Only the JWT bidder's history is read. Every auction appears once, including restricted
auctions and cancelled-bid history, excluding soft-deleted auctions. The latest personal
bid is selected using placedAt DESC, id DESC, **before paging**. Its amount and placedAt
are returned together. Outer paging uses the same ordering; totals count distinct auctions.
The query and count share a REPEATABLE_READ transaction snapshot. No external service is called.

`leading` means an ACTIVE/ENDED auction has leader_bidder_id equal to the caller and the
caller still has a non-CANCELLED bid at current_price. Old PLACED rows alone do not prove
leadership. A stale leader after cancellation/failed completion or invalidation is false.
`won` means the recorded winner_id is the caller and status is WON/SETTLED/PAYMENT_TIMEOUT;
it records the winner result, not payment success. A current top bidder is not assumed to
have won. Without a recorded winner it is false. These are read semantics, not a settlement implementation.

Both endpoints return ApiResponse with array data and sibling page metadata. Defaults are
page0/size10, page>=0 and size1–100. Empty pages keep actual totals. Shared auth/binding/
validation errors retain ADR-008 common Problem Details codes; no new domain error is needed.

## Automated tests

Use Java21 and Docker PostgreSQL18 Testcontainers, or set TEST_POSTGRES_ADMIN_URL to a
dedicated native PostgreSQL18 admin URL with TEST_POSTGRES_USERNAME/TEST_POSTGRES_PASSWORD.
Each fixture creates a random database and drops it on shutdown. Do not use application data.

From be-project-nexus:

```powershell
mvn -B -f lib/pom.xml -DskipTests install
mvn -B -f auction-service/pom.xml -Dtest=AuctionMineIntegrationTest test
mvn -B -f auction-service/pom.xml verify
mvn -B -f api-gateway/pom.xml verify
mvn -B -f config-service/pom.xml verify
node docs/api/check-contract.mjs
npx --yes --package @redocly/cli@2.72.0 redocly lint docs/api/openapi/auction-service.yaml
```

AuctionMineIntegrationTest drives real MVC/security/service/Flyway/JPA/SQL on PostgreSQL.
JWT decoding is mocked; it covers account isolation, explicit public-status filtering,
restricted/deleted auctions, repeated bids, distinct-count paging, timestamp ties,
leader changes, invalidated bids, stored winners and request validation. Winner fields are
fixtures and do not prove Sprint3 workers ran. Gateway tests use signed JWTs and HTTP
downstream fixtures to verify privilege enforcement/forwarding, including the bids route.

## Manual API checks

Start infrastructure, Config Server, Discovery, auction-service and Gateway from the #198
branch, with separate sellerA/sellerB and buyerA/buyerB tokens. Use a demo database.

1. SellerA owns CREATED, CANCELLED and restricted auctions; SellerB owns another. GET
   auctions/mine with sellerA sees only A's rows. status=CREATED and status=CANCELLED find
   those rows; the corresponding public list queries return data[]/total0. Neither caller
   can change ownership by sending sellerId or bidderId in the URL.
2. Give BuyerA two bids on auctionX and one on auctionY. BuyerB leads X, A leads Y. GET
   bids/mine as A returns two rows, X leading=false, Y leading=true, latest personal
   amount/placedAt. size=1 yields two distinct pages with totalElements=2/totalPages=2.
3. Place another actual bid by A above B on X; refresh mine and check leading changes.
   This step requires actual #146 bid-writing code in the runtime. #198 implements reads
   against the existing schema and does not include #146 production code.
4. Without token expect401. Wrong role or missing endpoint privilege expects403.
   page=-1/size=0/size=101/malformed status expects400 Problem Details.

Create CANCELLED fixtures through #197 only when that implementation is in the runtime;
otherwise use an isolated DB fixture and label it as such. No live create/cancel flow is
claimed by the read tests. SQL diagnosis:

```sql
SELECT id,seller_id,status,visibility,deleted_at FROM auctions WHERE seller_id=:seller_id;
SELECT id,auction_id,bidder_id,amount,status,placed_at FROM bids
WHERE bidder_id=:bidder_id ORDER BY placed_at DESC,id DESC;
SELECT id,current_price,leader_bidder_id,winner_id,status FROM auctions WHERE id=:auction_id;
```

## Verification on 2026-11-12

- Java21.0.9, PostgreSQL18.6 native isolated cluster; all fixture databases were disposable.
- Full Maven verify: auction-service **152**, api-gateway **81**, config-service **7**;
  no failures/errors/skips in the final runs. The 19 new mine tests prove both issue ACs
  through the real HTTP boundary and database. JWT decoder is mocked; no live Postman is claimed.
- Two additional tests passed in an isolated composition of #198 implementation
  `8f4bdd260fcc45421e5ca041fe13df165d7298a7` with actual #146 head
  `882be7d411e75066a78ea0965391f1c921688840`. POST bid110, bid120, rival130, bid140
  produced four real bid/BidPlaced rows; each buyer still saw one result with their own
  latest amount and leading switched correctly. A CANCELLED status fixture cleared
  leading without removing the actual bid history. This was not a run of #197 cancellation.
- Only merge conflicts combining repository methods, security-test mocks and contract
  controller registrations were reconciled. Bid business logic was unchanged; the temporary
  composition is not included in this MR. No settlement worker or live Kafka relay was run.
