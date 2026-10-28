# ADR-009: Service Boundaries After the Split

**Status:** Proposed. Merge requires both backend lanes to approve.
**Scope:** Which service owns which data, topic, gateway path and synchronous call.
**References:** ADR-006 (service authentication), ADR-007 (payment provider), `docs/api/events.md`, `docs/api/gateway-permissions.md`, the endpoint catalogue `nexus-api-endpoints.xlsx`.

---

## 1. Context

Three services each do more than one job today:

- `user-service` issues tokens (`JwtIssuer`, JWKS, login, lockout), stores profiles and computes reputation.
- `commerce-service` owns carts and orders, and will own payment and refunds.
- `fulfillment-service` owns stock and shipping.

A change to one job redeploys the others, and the data of one job is reachable from code that belongs to another. Each service gets one responsibility.

---

## 2. Decision Summary

| Service | Responsibility | Database | Port | Publishes on |
|---|---|---|---|---|
| **auth: Keycloak** | Sign-in, tokens, refresh, sign-out, lockout, passwords, roles and privileges | `keycloak_db` | `KEYCLOAK_PORT` | none |
| user-service | Profiles; registration and admin user management through the Keycloak Admin API | `user_db` | 8081 | `nexus.user.events` |
| catalog-service | Categories, products, SKUs, images | `catalog_db` | 8082 | `nexus.catalog.events` |
| commerce-service | Carts, orders, order status, promotions | `commerce_db` | 8083 | `nexus.commerce.events` |
| auction-service | Auctions, bids, watchers | `auction_db` | 8084 | `nexus.auction.events` |
| inventory-service | Warehouses, stock, reservations, stock movements | `inventory_db` | 8085 | `nexus.inventory.events` |
| notification-service | Notifications, templates, preferences, event log, audit log, health | `notification_db` | 8086 | none |
| payment-service | Payment instructions, transactions, Sepay webhook, reconciliation, refunds | `payment_db` | 8087 | `nexus.payment.events` |
| shipment-service | Shipments, tracking, carriers, carrier webhook | `shipment_db` | 8088 | `nexus.shipment.events` |
| rating-service | Ratings, reputation, trust level, eligibility | `rating_db` | 8089 | `nexus.rating.events` |

Platform services keep their ports: api-gateway 8080, config-service 8888, discovery-service 8761.

`inventory-service` is the new name of `fulfillment-service`. Shipping moves out of it into `shipment-service`.

Keycloak replaces the token code of `user-service`. The gateway and the services keep validating RS256 tokens with the claims `sub`, `roles`, `privileges` and audience `nexus-services`; only the issuer and the JWKS address change (ADR-006).

---

## 3. Data Ownership

No service reads or writes the database of another service. A column that holds the id of another service's entity is a plain `uuid` with no `REFERENCES`.

### 3.1 Tables of the six V1 schemas

| V1 schema | Table | Owner after the split | Issue |
|---|---|---|---|
| user | `users` | user-service; `password_hash` and the lockout columns are dropped | #238 |
| user | `roles`, `permissions`, `user_roles`, `role_permissions` | Keycloak; dropped from `user_db` | #236, #238 |
| user | `refresh_tokens`, `password_reset_tokens` | Keycloak; dropped from `user_db` | #238 |
| user | `ratings`, `user_reputations` | rating-service; dropped from `user_db` | #242 |
| user | `outbox`, `processed_events`, `shedlock` | user-service | — |
| catalog | `categories`, `products`, `skus`, `product_images`, `product_stats` | catalog-service | — |
| catalog | `outbox`, `processed_events` | catalog-service | — |
| commerce | `carts`, `cart_items`, `orders`, `order_items`, `order_status_history` | commerce-service | — |
| commerce | `promo_mega_sale_events`, `promo_vouchers`, `promo_event_sellers`, `promo_customer_vouchers`, `promo_voucher_products`, `promo_voucher_sellers`, `promo_voucher_usages`, `promo_voucher_usage_allocations` | commerce-service | — |
| commerce | `payment_transactions` | payment-service; dropped from `commerce_db` | #240 |
| commerce | `outbox`, `processed_events`, `shedlock` | commerce-service | — |
| auction | `auctions`, `bids`, `auction_watchers` | auction-service | — |
| auction | `outbox`, `processed_events`, `shedlock` | auction-service | — |
| fulfillment | `warehouses`, `inventory_balances`, `inventory_reservations`, `inventory_movements` | inventory-service | #243 |
| fulfillment | `shipments`, `shipment_history` | shipment-service; dropped from `inventory_db` | #241, #243 |
| fulfillment | `outbox`, `processed_events`, `shedlock` | inventory-service | #243 |
| notification | `event_logs`, `notification_templates`, `notifications`, `notification_deliveries`, `notification_delivery_attempts`, `notification_preferences` | notification-service | — |
| notification | `outbox`, `processed_events`, `shedlock` | notification-service | — |

Columns that move: `orders.memo_code` (commerce V2) becomes `payment_instructions.memo_code` in `payment_db` (#240). `orders.payment_due_at` stays in commerce, because the order expiry job reads it.

### 3.2 Schemas of the new services

| Database | Tables | Issue |
|---|---|---|
| `payment_db` | `payment_instructions`, `payment_transactions`, `outbox`, `processed_events` | #240 |
| `shipment_db` | `shipments`, `shipment_history`, `outbox`, `processed_events` | #241 |
| `rating_db` | `ratings`, `user_reputations`, `rating_eligibility`, `outbox`, `processed_events` | #242 |
| `keycloak_db` | managed by Keycloak | #235 |

---

## 4. Gateway Paths

The gateway routes by path prefix. **Rule:** a prefix belongs to exactly one service, and no service exposes an endpoint under a prefix that routes to another service. This is why eleven paths moved off `/api/v1/users/**` and `/api/v1/orders/**`.

| Prefix | Service | In `RouteConfig` |
|---|---|---|
| `/api/v1/auth/**` | user-service | Yes. After #238 only `POST /api/v1/auth/register` remains |
| `/api/v1/users/**` | user-service | Yes |
| `/.well-known/**` | user-service | Yes. Removed in #238 |
| `/api/v1/roles/**`, `/api/v1/permissions/**` | user-service | With #211 |
| `/api/v1/categories/**`, `/api/v1/products/**`, `/api/v1/skus/**` | catalog-service | Yes |
| `/api/v1/carts/**` | commerce-service | Yes |
| `/api/v1/orders/**`, `/api/v1/checkout/**` | commerce-service | With the first order endpoint (#113) and the checkout preview (#188) |
| `/api/v1/auctions/**`, `/api/v1/bids/**` | auction-service | With the first auction endpoint |
| `/api/v1/inventory/**`, `/api/v1/warehouses/**` | inventory-service | #245 |
| `/api/v1/payments/**`, `/api/v1/refunds/**`, `/api/v1/webhooks/sepay` | payment-service | #245 |
| `/api/v1/shipments/**`, `/api/v1/shipping/**`, `/api/v1/carriers/**`, `/api/v1/webhooks/carriers/**` | shipment-service | #245 |
| `/api/v1/ratings/**`, `/api/v1/reputations/**` | rating-service | #245 |
| `/api/v1/notifications/**`, `/api/v1/event-logs/**`, `/api/v1/audit-logs/**`, `/api/v1/system/health/**` | notification-service | With the first endpoint of each |

There is no route for `/api/v1/webhooks/**` as a whole: each webhook has its own route.

Not routed by the gateway:

- `/internal/v1/**`: service-to-service calls only.
- `/realms/**` and `/admin/realms/**`: the browser and `user-service` talk to Keycloak directly.

---

## 5. Synchronous Calls

Services talk through events by default: outbox, Kafka, `IdempotentConsumer`. A synchronous call is allowed only when the caller cannot continue without the answer.

| Caller → callee | Call | Why it cannot be an event | Issue |
|---|---|---|---|
| commerce → catalog | `GET /api/v1/skus/{id}` (`CatalogClient`) | The price and status shown in the cart and charged at order time must be the current ones; a copy fed by events can lag behind a price change | #110, #115 |
| commerce → inventory | `POST /api/v1/inventory/reservations` | The buyer waits for the answer: an order without stock must fail at once with 409 | #113, #138 |
| commerce → shipment | `POST /api/v1/shipping/quotes` | The checkout preview shows the shipping fee before the buyer confirms | #188, #201 |
| auction → rating | `GET /internal/v1/eligibility/{userId}` | A bid or an auction from an ineligible user must be refused before it is written | #214 |
| user → Keycloak | Admin API: create user, set password, assign role, unlock | Registration returns 201 only once the account exists | #211, #238 |
| gateway and services → Keycloak | `GET /realms/nexus/protocol/openid-connect/certs` | Public keys to validate tokens, cached by the resource server | #237 |
| shipment → GHN | quote, create, track | External provider | #201, #202, #203 |
| Sepay → payment | `POST /api/v1/webhooks/sepay` | External provider, inbound | #155 |

Every internal synchronous call has a timeout and a circuit breaker, and fails closed with a clear 503 instead of hanging (#138, #214).

---

## 6. Events Between Services

Eight domain topics, one per publishing service (#244): `nexus.user.events`, `nexus.catalog.events`, `nexus.commerce.events`, `nexus.auction.events`, `nexus.inventory.events` (was `nexus.fulfillment.events`), `nexus.payment.events`, `nexus.shipment.events`, `nexus.rating.events`.

| Flow | Events |
|---|---|
| Payment | commerce `OrderCreated` → payment creates the instruction. Sepay webhook → payment `PaymentConfirmed` → commerce moves the order to `PAID` and publishes `OrderPaid` → inventory confirms the stock, shipment creates the shipment, notification tells the buyer |
| Late or wrong payment | payment `PaymentUnreconciled` → commerce puts a reason on the order; the status does not change |
| Delivery | shipment `ShipmentPickedUp`, `ShipmentDelivered` → commerce `SHIPPED`, `DELIVERED`, `COMPLETED` and `OrderCompleted` → rating stores eligibility and updates reputation |
| Cancellation and refund | commerce `OrderCancelled` → inventory releases the stock, payment stops accepting the transfer. payment `OrderRefundRequested`, `OrderRefundCompleted` → commerce `REFUND_REQUESTED`, `REFUNDED` |
| Auction | auction `AuctionWon` → commerce creates the order. `AuctionCreated`, `AuctionFailed`, `AuctionCancelled`, `AuctionSettled` → inventory holds, releases or commits the stock. `AuctionPaymentTimeout` → rating applies the penalty |

Payloads are in `docs/api/events.md` (#186).

---

## 7. Consequences

**Positive**

- Each service changes, deploys and fails on its own. A payment provider change touches only `payment-service`.
- Rating needs no call to commerce to check that an order belongs to a user: `rating_eligibility` is filled from `OrderCompleted`.
- Authentication is a product, not project code: lockout, password reset and token rotation come from Keycloak.

**Negative**

- Four more services to run locally, plus Keycloak.
- More flows are eventually consistent. For example, an order shows `PAID` a few seconds after the bank transfer, not in the same request.
- `UserLoggedIn`, `UserPasswordChanged` and `UserPasswordReset` stay in Keycloak's own event store and are not published to Kafka.

**Order of work**

#234 → #235 → #236, #240, #241, #242, #243 → #237 → #238 → #245. #244 and #246 can start once this ADR merges.

---

## 8. Open Questions

1. Owner of `GET /api/v1/system/configs` and `PUT /api/v1/system/configs/{key}` (#217). Proposed: `config-service`, because it already holds every `config/<service>.yml`. The prefix `/api/v1/system/configs/**` then routes there.
2. Whether `UserLoggedIn` needs to reach the audit log. If yes, a Keycloak event listener that writes to Kafka is a separate issue.
