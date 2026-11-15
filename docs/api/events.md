# Event catalogue — #103 / #186

Updated 2026-10-28 against branch `fsa_ANNT_25/docs/186-event-catalogue`, baseline `fc0a982`, incorporating the MR !82 review. This is the proposed R1 event contract for backend review; a schema does not claim that its publisher/consumer is implemented or that both lanes have approved it.

## Sprint 3 handoff (#139)

The six contracts below are the #139 handoff for [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171), [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172) and their downstream owners. This section was checked against SRS 3.5.3 and the current issue requirements on 2026-11-10. It retains the existing schemas; contract review is separate from implementing or running the flow.

| Event / payload | Publishing issue | Business consuming issue | Topic |
| --- | --- | --- | --- |
| [AuctionWon](#auctionwon) | #171 | #172: create the auction order | `nexus.auction.events` |
| [AuctionFailed](#auctionfailed) | #171 | #209: release the auction hold | `nexus.auction.events` |
| [OrderCreated](#ordercreated) | #118, #172 | #107: notify; #173: settle AUCTION only; #216: product flag | `nexus.commerce.events` |
| [OrderPaid](#orderpaid) | #156 | #160: order reservations; #202: shipment | `nexus.commerce.events` |
| [OrderCancelled](#ordercancelled) | #159, #189 | #161: release; #107: notify; #157/#247: payment/refund; #208: auction deadline only | `nexus.commerce.events` |
| [InventoryReservationExpired](#inventoryreservationexpired) | #175 | No additional business handler assigned; #192 consumes for the event log | `nexus.inventory.events` |

#192 independently logs every event in this table; it does not replace a business consumer. The linked sections contain the complete named issue mappings, schemas, examples, aggregate IDs and Kafka keys.

```mermaid
sequenceDiagram
    participant A as auction-service
    participant K as Kafka via outbox relay
    participant C as commerce-service
    participant P as payment-service
    A->>K: #171 AuctionWon
    K->>C: #172 consume with dedupe
    Note over C: Commit PENDING singleton group/order/item + OrderCreated + processed marker
    C->>K: OrderCreated (AUCTION, referenceId=auctionId)
    K->>A: #173 consume
    Note over A: Commit WON to SETTLED, orderId, settlement time + AuctionSettled + marker
    Note over C: Winner confirms address; resolve linked hold/warehouse and shipping fee
    C->>K: CheckoutPaymentRequested after confirmation
    K->>P: #121 create immutable session/instruction
```

The two databases commit separately. Each domain write, processed marker and its outbox rows share that service's transaction; Kafka delivery is at least once. #172 also guards the auction reference so a new eventId cannot create a second order for the same auction. On failure it rolls back the marker and effects for retry. #173 ignores STANDARD orders and follows its own WON-state guard. SETTLED confirms that the order exists; it does not mean the winner has paid.

For AUCTION, do not reserve stock again and do not publish CheckoutPaymentRequested during order creation. The initial group total is finalPrice; #172's owner-only shipping confirmation stores the address/linked warehouse, fixes shipping fee and total, and writes the payment manifest atomically. Its repeat with the same address returns the stored result without another manifest. The payment deadline remains paymentDueAt from AuctionWon.

The draft state endpoint is [GET /api/v1/auctions/{id}/state](auction-service.yaml); [the delivered SePay webhook](openapi/payment-service.yaml) embeds the [captured body](sepay-webhook-sample.json). [Gateway permissions](gateway-permissions.md#sprint-3-contract-handoff-139) distinguishes public webhook contracts from currently enabled paths.

From `be-project-nexus`, run `node docs/api/check-contract.mjs` to compare delivered routes with controllers, and lint `docs/api/auction-service.yaml` and `docs/api/openapi/payment-service.yaml` with Redocly. Separately review the six linked payload schemas/examples and their publisher/consumer mappings against the owning issues, and compare the captured SePay fields with its OpenAPI schema and DTO. The route checker and OpenAPI lint do not validate the event schemas or prove live Kafka, banking, staging latency or cross-service delivery. Both the auction and settlement developers must approve !150 before #139's final acceptance criterion is checked; no approval is inferred from existing catalogue content.

### Acceptance evidence for #139

| Criterion | Evidence / remaining gate |
| --- | --- |
| #172 can build its order line from AuctionWon | Required fields and persistence mapping in [AuctionWon](#auctionwon); locally generated IDs and nullable sku_code are explicit |
| All six events have schema and named publisher/consumer | The six-event handoff table links to each payload section containing its schema, example and named publisher/consumer issues |
| OrderCancelled publishers are #159 and #189 | The Publisher entry in [OrderCancelled](#ordercancelled) explicitly names both issues |
| Every captured SePay field is in the webhook schema | Compare the twelve fields in [the captured body](sepay-webhook-sample.json) with `SepayWebhookRequest` and the request example in [payment OpenAPI](openapi/payment-service.yaml), and with [the service DTO](../../payment-service/src/main/java/com/group5/paymentservice/payment/dto/request/SepayWebhookRequest.java) |
| Both AuctionWon developers approve the MR | Pending actual auction/settlement approvals on !150; schema validation does not satisfy this gate or the joint-authoring subtask |

## Scope and source precedence

The original #103 catalogue contained 64 SRS names: 13 R1 contracts and 51 R2 entries. The current #186 table, including its 2026-10-28 decision, promotes **30 of those R2 entries**, adds **CheckoutPaymentConfirmed and PaymentUnreconciled**, and requires schemas/examples and publisher/consumer mappings for that **32-event R1 expansion set**. Its overview still says 30; the current table contains 32 names after the payment additions. PaymentConfirmed is no longer in that R1 expansion set.

The group-payment requirements in #113/#118/#121/#240/#250 also require **CheckoutPaymentRequested and OrderGroupCancelled**. These two coordinated R1 additions have contracts here and are counted separately from the current #186 expansion set; CheckoutPaymentConfirmed is already counted in its 32 events. With PaymentConfirmed deferred to R2, the complete inventory is **71 names: 49 R1 design contracts and 22 R2 entries**. Within the original 64 SRS names the split remains **43 R1 / 21 R2**; the seven additional names comprise six R1 contracts and one R2 reference. There are **50 documented schemas: 49 R1 contracts and the retained PaymentConfirmed R2 schema**. R2 means publication is outside current R1 scope, without a delivery-date commitment. HTTP feature scope and Kafka-event scope differ.

The #203 update on 2026-11-11 adds ShipmentStatusUpdated and ShipmentCancelled as R1 contracts for accepted intermediate and cancellation transitions. These two names are additional to the #186 expansion set. Duplicate/no-op updates publish neither event.

The #157 update on 2026-11-14 adds InventoryStockUnavailable as an R1 contract: inventory publishes it when a late OrderPaid finds an expired hold whose stock is no longer available, and commerce cancels and refunds that paid order.

**CheckoutPaymentConfirmed is the authoritative payment confirmation event for R1**, using one immutable session per order group under #250. **PaymentConfirmed is deferred to R2 and must not be published or consumed as an alternative payment-state event during R1.** Its schema, example and historical #186 ownership references are retained only for traceability, not as an R1 compatibility publication. UserPasswordChanged, UserPasswordReset and UserLoggedIn remain R2 here; Keycloak owns their authentication events (#210/ADR-009).

| Source | Use in this revision |
| --- | --- |
| #103 / MR !55 | Preserve the 64-name SRS 2.8 inventory and existing thirteen payload contracts, extending fields only for explicit new consumers. The six publisher-only exceptions were accepted for that earlier scope. |
| #186, including the 2026-10-28 decision | Current 32-event R1 expansion list, topic/publisher/consumer assignments, schema/example validation and both-lane review. CheckoutPaymentConfirmed replaces PaymentConfirmed in that list. |
| MR !82 review, applied 2026-10-28 | Unify money as decimal strings with exactly two fractional digits; select CheckoutPaymentConfirmed for R1 and defer PaymentConfirmed to R2. Other handoff decisions and both-lane approvals remain open. |
| #234 / ADR-009; #244 / #246 | Eight domain topics, independent payment/shipment/rating/inventory owners, no notification/system topic. |
| #113 / #118 / #121 / #240; #250 design in `../database/schema-after-service-split.md` | Complete group manifest, one immutable session/QR, one instruction and shipping/refund lifecycle per seller order. These newer explicit group rules supersede older per-order memo/payment prose in #155–#157 and ADR-009. |
| #203 R1 status/cancellation handling | A PAID order may be cancelled until ShipmentPickedUp, with no handover request/ACK; accepted transitions commit one history and one Shipment* outbox row. |
| #204 / #212 / #213 / #242 | Order completion, local rating eligibility, reputation inputs/results. |
| #197 / #208 / #209 / #216 | Auction cancellation/timeout, auction stock, product flags. |
| #191 / #192 / #193 / #211 / #217 / #247 | Delivery records, immutable event/audit log, health transitions, admin user/config audit and instruction-scoped refunds. |
| Migrations, EventEnvelope/Topics, and #250 | Available fields, UUID/nullability, numeric(19,2), exact state names and service ownership. #250 is a reviewed target design; this document does not execute its migrations. |

New field names, nullability, machine reason codes, local recording boundaries and version conventions in #186 are contract decisions proposed for both lanes, not verbatim claims about the SRS. Specific unresolved decisions are listed under [Review and implementation handoff](#review-and-implementation-handoff).

## EventEnvelope and Kafka configuration

| Field | Java type | Rule |
| --- | --- | --- |
| eventId | UUID | Non-null; generated by the factory and preserved across relay retries/redelivery. |
| eventType | String | Non-null and non-blank; exact SRS event name. |
| aggregateId | String | Non-null and non-blank; mapped in each payload section. |
| occurredAt | Instant | Non-null; factory uses Clock. UTC ISO-8601 on the JSON wire. |
| traceId | String | Non-null and non-blank; taken from the current Micrometer span. |
| payload | T | Non-null; nested fields may be null only where the schema permits it. |

The factory fails without an active span, so scheduled publishers need a traced context. The relay preserves the stored eventId, occurredAt, traceId and payload instead of generating a new envelope on retry.

Inject the producer using `EventKafka.TEMPLATE` (`eventKafkaTemplate`) and select `EventKafka.LISTENER_CONTAINER_FACTORY` (`eventListenerContainerFactory`) for listeners. Default String Kafka beans also remain available. The shared serializer writes JSON without Java type headers; the listener reads `EventEnvelope<JsonNode>` through `ErrorHandlingDeserializer`. Dispatch by eventType before validating the event-specific payload. A deserialization-error header does not provide a project retry/DLT policy by itself.

Complete CategoryCreated envelope example, illustrating the wire shape rather than an executed integration test:

```json
{
  "eventId": "01936b2a-7c4e-7000-8000-000000000001",
  "eventType": "CategoryCreated",
  "aggregateId": "00000000-0000-4000-8000-000000000007",
  "occurredAt": "2026-10-19T08:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "payload": {
    "categoryId": "00000000-0000-4000-8000-000000000007",
    "name": "Electronics",
    "slug": "electronics",
    "parentId": null
  }
}
```

The upstream #102 tests cover field validation, factory identity/time/current trace, JSON round-trip to JsonNode, UTC Instant serialization, disabled type headers and missing-trace deserialization errors. The historical #186 document-validation results are recorded below; they are not a runnable validator supplied by #139 or a new run of the Java suite or Kafka integration tests. The CatalogTestEvent demo is not one of the SRS events.

Complete group manifest envelope example for a hand-published contract test (synthetic IDs/data):

```json
{
  "eventId": "00000000-0000-4000-8000-000000000090",
  "eventType": "CheckoutPaymentRequested",
  "aggregateId": "00000000-0000-4000-8000-000000000020",
  "occurredAt": "2026-11-20T10:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "payload": {
    "orderGroupId": "00000000-0000-4000-8000-000000000020",
    "buyerId": "00000000-0000-4000-8000-000000000002",
    "currency": "VND",
    "totalAmount": "350000.50",
    "dueAt": "2026-11-20T10:30:00Z",
    "occurredAt": "2026-11-20T10:00:00Z",
    "orderCount": 2,
    "orders": [
      {
        "orderId": "00000000-0000-4000-8000-000000000001",
        "sellerId": "00000000-0000-4000-8000-000000000004",
        "amount": "200000.50",
        "paymentDueAt": "2026-11-20T10:30:00Z",
        "orderVersion": 0
      },
      {
        "orderId": "00000000-0000-4000-8000-000000000011",
        "sellerId": "00000000-0000-4000-8000-000000000012",
        "amount": "150000.00",
        "paymentDueAt": "2026-11-20T10:30:00Z",
        "orderVersion": 0
      }
    ]
  }
}
```

## Topics and delivery rules

| Owner | Constant | Topic |
| --- | --- | --- |
| user-service | Topics.USER | nexus.user.events |
| catalog-service | Topics.CATALOG | nexus.catalog.events |
| commerce-service | Topics.COMMERCE | nexus.commerce.events |
| auction-service | Topics.AUCTION | nexus.auction.events |
| inventory-service | Topics.INVENTORY | nexus.inventory.events |
| payment-service | Topics.PAYMENT | nexus.payment.events |
| shipment-service | Topics.SHIPMENT | nexus.shipment.events |
| rating-service | Topics.RATING | nexus.rating.events |

- Domain publishers commit business changes and OutboxWriter rows in their own database transaction (#104). The relay preserves eventId/occurredAt/traceId/payload across retries. Delivery is at least once; an ACK/crash race can redeliver. Kafka, carrier calls, Keycloak and another service's DB are outside that SQL transaction.
- Consumers use IdempotentConsumer (#105) with `(eventId, consumer)`. The marker and business effects commit together; missing prerequisites/failed effects roll back or remain in durable retry. Domain uniqueness/version guards also prevent the same operation arriving under another eventId. The eight-topic #192 logger is a real consuming issue for every domain event; an event with no business handler is now **log-only**, rather than the old publisher-only classification.
- Kafka keys are explicit per contract. A shared key only orders one partition/topic; it does not order commerce versus payment/shipment/auction topics. Version/business membership checks remain necessary. Event schema version, orderVersion, groupVersion, sessionVersion and instructionVersion are distinct concepts.
- All properties listed in a schema are required; null is allowed only explicitly. Draft 7, `additionalProperties=true`, UUID strings and UTC ISO-8601 timestamps ending in Z follow #103. Consumers dispatch by envelope.eventType and validate before effects. Breaking new required snapshots need coordinated producer/consumer rollout and historical replay/backfill; additive-property tolerance alone does not make missing required fields compatible.
- All money fields use JSON decimal strings with exactly two fractional digits and pattern `^\d+\.\d{2}$`, matching the commerce OpenAPI convention (for example, `"1500000.00"`). This includes BidPlaced.amount and AuctionWon.finalPrice; AuctionWon is written by #170. Parse with BigDecimal/Decimal; never calculate money through float/double. The wire pattern does not restrict integer length or leading zeros; producers and consumers must still enforce numeric(19,2) storage bounds and business amount rules. Amounts, membership, chronology, actor authority and state transitions also need semantic checks beyond JSON Schema.
- **Non-Kafka records:** NotificationCreated/Sent/Failed and the four health transitions have topic `none`. #191/#193 create EventEnvelope records via the #192 in-process recorder, without a Kafka outbox row or extra topic; local effects/logs can share notification_db transactions. A broker outage must not prevent its own health failure/recovery log. Redis health state and PostgreSQL logs still need durable handoff/retry; they are not one atomic transaction. The `none` topic on SystemConfigUpdated requires a separate durable internal handoff from the #217 owner to #192, explicitly pending review below.
- #192 stores the original logical envelope payload once by eventId; JSONB is not byte-for-byte HTTP evidence. Derive operator_id from payload.actorId for user/role/refund/config audit and derive aggregate type/producer from this catalogue. Never overwrite a duplicate eventId with different content. Actor IDs describe the original action, not the viewer or technical recorder. Passwords/tokens/provider keys and full notification bodies/addresses are excluded from these audit/delivery contracts.

## Event inventory

### Original 64 SRS 2.8 names

The contract cell contains only `R2` for out-of-scope names. Each R1 link resolves to a schema, example and publisher/consumer mapping.

| # | Event | Contract |
| --- | --- | --- |
| 1 | `UserRegistered` | R2 |
| 2 | `UserUpdated` | [R1 — payload](#userupdated) |
| 3 | `UserDeleted` | [R1 — payload](#userdeleted) |
| 4 | `UserPasswordChanged` | R2 |
| 5 | `UserPasswordReset` | R2 |
| 6 | `UserLoggedIn` | R2 |
| 7 | `UserLoggedOut` | R2 |
| 8 | `UserRoleAssigned` | [R1 — payload](#userroleassigned) |
| 9 | `ProductCreated` | R2 |
| 10 | `ProductUpdated` | R2 |
| 11 | `ProductDeleted` | R2 |
| 12 | `ProductStatusChanged` | R2 |
| 13 | `CategoryCreated` | [R1 — payload](#categorycreated) |
| 14 | `CategoryUpdated` | [R1 — payload](#categoryupdated) |
| 15 | `CategoryDeleted` | [R1 — payload](#categorydeleted) |
| 16 | `CartCreated` | R2 |
| 17 | `CartItemAdded` | R2 |
| 18 | `CartItemUpdated` | R2 |
| 19 | `CartItemRemoved` | R2 |
| 20 | `CartExpired` | R2 |
| 21 | `CheckoutStarted` | R2 |
| 22 | `OrderCreated` | [R1 — payload](#ordercreated) |
| 23 | `OrderCancelled` | [R1 — payload](#ordercancelled) |
| 24 | `OrderPaid` | [R1 — payload](#orderpaid) |
| 25 | `OrderPaymentFailed` | R2 |
| 26 | `OrderCompleted` | [R1 — payload](#ordercompleted) |
| 27 | `OrderRefundRequested` | [R1 — payload](#orderrefundrequested) |
| 28 | `OrderRefundCompleted` | [R1 — payload](#orderrefundcompleted) |
| 29 | `AuctionCreated` | [R1 — payload](#auctioncreated) |
| 30 | `AuctionScheduled` | R2 |
| 31 | `AuctionStarted` | [R1 — payload](#auctionstarted) |
| 32 | `BidPlaced` | [R1 — payload](#bidplaced) |
| 33 | `Outbid` | R2 |
| 34 | `AuctionCancelled` | [R1 — payload](#auctioncancelled) |
| 35 | `AuctionEnded` | [R1 — payload](#auctionended) |
| 36 | `AuctionWon` | [R1 — payload](#auctionwon) |
| 37 | `AuctionFailed` | [R1 — payload](#auctionfailed) |
| 38 | `AuctionPaymentTimeout` | [R1 — payload](#auctionpaymenttimeout) |
| 39 | `AuctionSettled` | [R1 — payload](#auctionsettled) |
| 40 | `InventoryChecked` | R2 |
| 41 | `InventoryReserved` | [R1 — payload](#inventoryreserved) |
| 42 | `InventoryReleased` | [R1 — payload](#inventoryreleased) |
| 43 | `InventoryCommitted` | [R1 — payload](#inventorycommitted) |
| 44 | `InventoryAdjusted` | R2 |
| 45 | `InventoryReservationExpired` | [R1 — payload](#inventoryreservationexpired) |
| 46 | `ShippingQuoteRequested` | R2 |
| 47 | `ShipmentCreated` | [R1 — payload](#shipmentcreated) |
| 48 | `ShipmentPickedUp` | [R1 — payload](#shipmentpickedup) |
| 49 | `ShipmentInTransit` | [R1 — payload](#shipmentintransit) |
| 50 | `ShipmentDelivered` | [R1 — payload](#shipmentdelivered) |
| 51 | `ShipmentFailed` | [R1 — payload](#shipmentfailed) |
| 52 | `ShipmentReturned` | [R1 — payload](#shipmentreturned) |
| 53 | `NotificationCreated` | [R1 — payload](#notificationcreated) |
| 54 | `NotificationSent` | [R1 — payload](#notificationsent) |
| 55 | `NotificationFailed` | [R1 — payload](#notificationfailed) |
| 56 | `UserRated` | [R1 — payload](#userrated) |
| 57 | `ReputationScoreUpdated` | [R1 — payload](#reputationscoreupdated) |
| 58 | `ReputationPenaltyApplied` | [R1 — payload](#reputationpenaltyapplied) |
| 59 | `TrustLevelChanged` | [R1 — payload](#trustlevelchanged) |
| 60 | `SystemConfigUpdated` | [R1 — payload](#systemconfigupdated) |
| 61 | `SystemHealthCheckFailed` | [R1 — payload](#systemhealthcheckfailed) |
| 62 | `SystemHealthCheckRecovered` | [R1 — payload](#systemhealthcheckrecovered) |
| 63 | `MessageBrokerUnavailable` | [R1 — payload](#messagebrokerunavailable) |
| 64 | `MessageBrokerRecovered` | [R1 — payload](#messagebrokerrecovered) |

### Additional names after the SRS baseline

| Event | Source | Contract |
| --- | --- | --- |
| `PaymentConfirmed` | Historical #186 payment addition; deferred by the 2026-10-28 decision | [R2 — retained reference](#paymentconfirmed) |
| `PaymentUnreconciled` | #186 payment addition | [R1 — payload](#paymentunreconciled) |
| `CheckoutPaymentRequested` | #113/#118/#121/#240/#250 group design | [R1 — payload](#checkoutpaymentrequested) |
| `CheckoutPaymentConfirmed` | #186 current table; #113/#118/#121/#240/#250 group design | [R1 — payload](#checkoutpaymentconfirmed) |
| `OrderGroupCancelled` | #113/#118/#121/#240/#250 group design | [R1 — payload](#ordergroupcancelled) |

| `ShipmentStatusUpdated` | #203 shipment status standardization | [R1 — payload](#shipmentstatusupdated) |
| `ShipmentCancelled` | #203 R1 pre-pickup cancellation | [R1 — payload](#shipmentcancelled) |
| `InventoryStockUnavailable` | #157 late OrderPaid recovery | [R1 — payload](#inventorystockunavailable) |
| `AuctionStockRejected` | #170 auction hold rejection | [R1 — payload](#auctionstockrejected) |

## Publishers and consumers

Issue numbers identify implementation ownership, not completed runtime behavior. The table covers 49 R1 contracts plus the deferred PaymentConfirmed R2 reference; its historical topic and issue links do not authorize R1 publication or consumption. See each payload section for log-only handling and review conditions.

| Event | Topic | Publishing issue | Consuming issue / handling |
| --- | --- | --- | --- |
| `UserUpdated` | `nexus.user.events` | [#211](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/211) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit |
| `UserDeleted` | `nexus.user.events` | [#211](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/211) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit |
| `UserRoleAssigned` | `nexus.user.events` | [#211](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/211) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit |
| `CategoryCreated` | `nexus.catalog.events` | [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip/no template; no notification recipient; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `CategoryUpdated` | `nexus.catalog.events` | [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip/no template; no notification recipient; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `CategoryDeleted` | `nexus.catalog.events` | [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip/no template; no notification recipient; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `OrderCreated` | `nexus.commerce.events` | [#118](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/118), [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172) | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): notify the buyer; auction-service [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173): settle AUCTION orders; catalog-service [#216](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/216): set has_order on the listed products; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `OrderCancelled` | `nexus.commerce.events` | [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159), [#189](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/189) | shipment-service [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203): cancel at carrier and persist CANCELLED before pickup, or warn Support after pickup; inventory-service [#161](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/161): release HELD order reservations; notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): notify the buyer; payment-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157), [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247): close unpaid instructions or refund the funded child; auction-service [#208](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/208): timeout AUCTION only for PAYMENT_DEADLINE_EXPIRED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `OrderPaid` | `nexus.commerce.events` | [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156) | inventory-service [#160](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/160): confirm order reservations; shipment-service [#202](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/202): create one shipment using this trusted snapshot; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `OrderCompleted` | `nexus.commerce.events` | [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204) | rating-service [#242](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/242), [#212](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/212): persist rating_eligibility without calling commerce; rating-service [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213): update score and transaction counts; notification-service [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191): deliver the HTML receipt; [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `OrderRefundRequested` | `nexus.payment.events` | [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247) | commerce-service [#116](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/116): move CANCELED -> REFUND_REQUESTED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit |
| `OrderRefundCompleted` | `nexus.payment.events` | [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247) | commerce-service [#116](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/116): move REFUND_REQUESTED -> REFUNDED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit |
| `AuctionCreated` | `nexus.auction.events` | [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209) | inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): hold one unit for the auction; catalog-service [#216](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/216): set product in_auction; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionStarted` | `nexus.auction.events` | [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `BidPlaced` | `nexus.auction.events` | [#146](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/146) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionCancelled` | `nexus.auction.events` | [#197](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/197) | inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): release the auction hold; catalog-service #215: remove the auction from the product's open-auction flag; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionEnded` | `nexus.auction.events` | [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145) | catalog-service [#216](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/216): clear product in_auction; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionWon` | `nexus.auction.events` | [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171) | commerce-service [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172): create the AUCTION order and singleton group; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionFailed` | `nexus.auction.events` | [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171) | inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): release the auction hold; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionPaymentTimeout` | `nexus.auction.events` | [#208](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/208) | rating-service [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213): apply the late-payment penalty to the winner once; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `AuctionSettled` | `nexus.auction.events` | [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173) | inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): attach orderId to the existing HELD reservation without a balance change or stock movement; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `InventoryReserved` | `nexus.inventory.events` | [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log only; stock was already changed by inventory |
| `InventoryReleased` | `nexus.inventory.events` | [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log only; stock was already changed by inventory |
| `InventoryCommitted` | `nexus.inventory.events` | [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log only; stock was already changed by inventory |
| `InventoryReservationExpired` | `nexus.inventory.events` | [#175](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/175) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `InventoryStockUnavailable` | `nexus.inventory.events` | [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157) | commerce-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157): cancel the PAID order with reason STOCK_UNAVAILABLE and publish OrderCancelled with paidAmount, which payment-service refunds |
| `AuctionStockRejected` | `nexus.inventory.events` | inventory-service #170 | auction-service #170 cancels an open auction with reason STOCK_UNAVAILABLE |
| `ShipmentCreated` | `nexus.shipment.events` | [#202](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/202) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recognize/log creation; no order-state move; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentPickedUp` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): PAID -> SHIPPED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentInTransit` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): confirm PAID -> SHIPPED if the pickup event was missed; no new order state is introduced; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentDelivered` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recover SHIPPED if PAID, then DELIVERED -> COMPLETED and publish OrderCompleted once; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentFailed` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): confirm PAID -> SHIPPED if the pickup event was missed; no new order state is introduced; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentStatusUpdated` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recognize/log only; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentCancelled` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recognize/log only, OrderCancelled already owns cancellation; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ShipmentReturned` | `nexus.shipment.events` | [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203) | commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): confirm PAID -> SHIPPED if the pickup event was missed; no new order state is introduced; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `NotificationCreated` | `none` | [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log |
| `NotificationSent` | `none` | [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log |
| `NotificationFailed` | `none` | [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log |
| `UserRated` | `nexus.rating.events` | [#212](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/212) | rating-service [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213): update the rated user's effective rating inputs and score; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ReputationScoreUpdated` | `nexus.rating.events` | [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `ReputationPenaltyApplied` | `nexus.rating.events` | [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `TrustLevelChanged` | `nexus.rating.events` | [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `SystemConfigUpdated` | `none` | [#217](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/217) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): audit through the proposed durable internal handoff; delivery/owner review below |
| `SystemHealthCheckFailed` | `none` | [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency |
| `SystemHealthCheckRecovered` | `none` | [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency |
| `MessageBrokerUnavailable` | `none` | [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency |
| `MessageBrokerRecovered` | `none` | [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193) | notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency |
| `PaymentConfirmed` (R2; deferred) | `nexus.payment.events` | [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156): historical #186 reference; R2 only, no R1 publication | commerce-service [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156), notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): historical #186 references; R2 only, no R1 handler |
| `PaymentUnreconciled` | `nexus.payment.events` | [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157) | commerce-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157): set buyer-readable paymentNote for affected orders without moving their status; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `CheckoutPaymentRequested` | `nexus.commerce.events` | [#118](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/118) | payment-service [#121](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/121), [#240](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/240): create the one immutable session and all seller instructions atomically; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `CheckoutPaymentConfirmed` | `nexus.payment.events` | [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156) | commerce-service [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156), [#116](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/116): validate the whole group and commit valid child PAID/history/OrderPaid atomically; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |
| `OrderGroupCancelled` | `nexus.commerce.events` | [#189](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/189), [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159) | payment-service [#121](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/121), [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157), [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247): close the immutable unpaid session or compensate funded cancelled children; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log |

## Integration flows and compatibility

1. **Purchase/group payment:** #113/#118 commit one group, seller orders, OrderCreated per child and the complete CheckoutPaymentRequested. #121 creates one immutable payment session and all instructions. #156 accepts one exact on-time movement, funds the whole plan and writes CheckoutPaymentConfirmed; commerce validates/commits group and eligible child PAID/history plus OrderPaid per child. The first OrderCreated never issues a partial-group QR. PaymentConfirmed is R2; do not publish it or enable a payment-state handler for it in R1.
2. **Delivery/completion:** #202 consumes per-order OrderPaid with the trusted buyer/warehouse/shipping snapshot. #203 publishes normalized Shipment* changes. #204 applies ShipmentPickedUp -> SHIPPED and ShipmentDelivered -> DELIVERED -> COMPLETED, then emits OrderCompleted. Post-pickup progress (IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_FAILED, RETURNING, RETURNED) also confirms PAID -> SHIPPED if pickup was missed. Delivery received while PAID atomically records PAID -> SHIPPED -> DELIVERED -> COMPLETED; missing orders or PENDING payment remain durably pending. No new order enum is introduced. #242/#212 populate eligibility; #213 applies the unique canonical outcome and later UserRated inputs.
3. **Unpaid cancellation:** #189/#159 cancel the complete group and its still-PENDING children atomically in commerce, publishing OrderGroupCancelled and OrderCancelled per child. Payment closes the immutable unpaid session/instructions. A raced successful receipt remains successful financial evidence; a late confirmation cannot resurrect cancelled orders, and #247 compensates each funded cancelled child's own share.
4. **Paid child cancellation/refund:** R1 allows a PAID seller order to be cancelled until ShipmentPickedUp, with no handover request or request/ACK protocol. #189 publishes the final OrderCancelled; #203 consumes it idempotently. Before pickup, cancel any existing carrier booking through CarrierAdapter, then commit CANCELLED, one history row, one ShipmentCancelled outbox event and the processed marker. After pickup, change nothing and log one WARN for Support for the handled event. A missing shipment completes without error after recording a durable cancellation tombstone, so a later OrderPaid cannot create or book a cancelled order; manual creation then returns 409 SHIPMENT_ORDER_NOT_PAID. Validate the nonblank OrderCancelled reason, at most 500 characters, before any carrier cancellation. Preserve the valid reason in the tombstone and sanitize/truncate to 255 characters for tracking/outbox audit. Carrier cancellation failure remains retryable and must not be marked processed. OrderRefundRequested/Completed identify one child/instruction/refund; never refund the entire multi-seller receipt for each child.
5. **Auction stock and settlement:** #209 AuctionCreated -> inventory hold/InventoryReserved. If the last unit is taken after the create precheck, Inventory writes AuctionStockRejected and Auction cancels the open auction with STOCK_UNAVAILABLE. AuctionFailed/AuctionCancelled -> release any existing hold/InventoryReleased; AuctionSettled -> link orderId without a stock movement; OrderPaid (#160) -> commit/InventoryCommitted; OrderCancelled (#161) -> release or return stock. #171 AuctionWon -> #172 singleton group/AUCTION order -> OrderCreated -> #173 AuctionSettled. The hold remains HELD until the order payment or cancellation outcome. #208 consumes only AUCTION OrderCancelled with PAYMENT_DEADLINE_EXPIRED and emits AuctionPaymentTimeout for #213's winner-only penalty.
6. **Logging/audit:** #192 independently consumes all eight domain topics and records the seven local notification/health types. #217's config audit delivery is a proposed durable internal handoff with no Kafka topic. Local logging does not subscribe to itself or create recursive notification events.

The existing AuctionWon -> order snapshot mapping remains: winnerId -> buyer, sellerId -> seller, productId/skuId/title/imageUrl/quantity/finalPrice -> immutable order item, referenceId=auctionId, and paymentDueAt is preserved. #172 also creates the singleton group/manifest required by the new payment model. The shared AuctionWon payload validates the copied image and the fixed quantity of one before publication.

## R1 payload contracts

### UserUpdated

- Topic: `nexus.user.events`.
- Publisher: [#211](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/211).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit.
- Envelope aggregateId: `userId`; Kafka key: `userId`.

| Payload field | Meaning |
| --- | --- |
| `userId` | Profile/Keycloak identity being administered. |
| `actorId` | Authorized Admin/Support making the change. |
| `changedFields` | Actual supported profile fields changed; exclude credentials/identity documents. |
| `updatedAt` | Committed profile change time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "UserUpdatedPayload",
  "type": "object",
  "properties": {
    "userId": {
      "type": "string",
      "format": "uuid"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedFields": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": [
          "fullName",
          "email",
          "phone",
          "gender",
          "address",
          "dateOfBirth",
          "status"
        ]
      },
      "minItems": 1,
      "uniqueItems": true
    },
    "updatedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "userId",
    "actorId",
    "changedFields",
    "updatedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "userId": "00000000-0000-4000-8000-000000000002",
  "actorId": "00000000-0000-4000-8000-000000000060",
  "changedFields": [
    "fullName",
    "address"
  ],
  "updatedAt": "2026-11-20T10:00:00Z"
}
```

#211 publishes after the permitted profile operation succeeds, using user_db.users.id=Keycloak ID. The audit records actor, target and changed field names without copying an entire profile into the log. This does not add publication to the separate self-profile issue or invent a profile version column. Credentials/password hashes/tokens are never payload fields. Keycloak/profile compensation and permissions remain #211/#238 implementation requirements.

### UserDeleted

- Topic: `nexus.user.events`.
- Publisher: [#211](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/211).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit.
- Envelope aggregateId: `userId`; Kafka key: `userId`.

| Payload field | Meaning |
| --- | --- |
| `userId` | Deleted identity; retain this value even if the profile is hard-deleted. |
| `actorId` | Admin performing deletion. |
| `deletionMode` | Actual mode chosen under USER_SOFT_DELETE. |
| `deletedAt` | Accepted deletion time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "UserDeletedPayload",
  "type": "object",
  "properties": {
    "userId": {
      "type": "string",
      "format": "uuid"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "deletionMode": {
      "type": "string",
      "enum": [
        "SOFT",
        "HARD"
      ]
    },
    "deletedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "userId",
    "actorId",
    "deletionMode",
    "deletedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "userId": "00000000-0000-4000-8000-000000000002",
  "actorId": "00000000-0000-4000-8000-000000000060",
  "deletionMode": "SOFT",
  "deletedAt": "2026-11-20T10:00:00Z"
}
```

#211 records the successful deletion and audit evidence, with actor/target retained independently of the deleted row. USER_SOFT_DELETE selects soft versus hard behavior; both disable Keycloak sign-in. Do not copy the full deleted contact/profile or credentials. The workflow must compensate Keycloak/profile failure; this payload does not claim a distributed SQL transaction.

### UserRoleAssigned

- Topic: `nexus.user.events`.
- Publisher: [#211](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/211).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit.
- Envelope aggregateId: `userId`; Kafka key: `userId`.

| Payload field | Meaning |
| --- | --- |
| `userId` | Keycloak identity whose fixed realm-role set changed. |
| `actorId` | Authorized Admin requesting role assignment. |
| `previousRoles` | Verified previous realm-role set. |
| `roles` | Verified complete resulting realm-role set; an empty set records removal. |
| `assignedAt` | Successful role operation time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "UserRoleAssignedPayload",
  "type": "object",
  "properties": {
    "userId": {
      "type": "string",
      "format": "uuid"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "previousRoles": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": [
          "ADMIN",
          "SELLER",
          "BUYER",
          "SUPPORT_STAFF"
        ]
      },
      "minItems": 0,
      "uniqueItems": true
    },
    "roles": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": [
          "ADMIN",
          "SELLER",
          "BUYER",
          "SUPPORT_STAFF"
        ]
      },
      "minItems": 0,
      "uniqueItems": true
    },
    "assignedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "userId",
    "actorId",
    "previousRoles",
    "roles",
    "assignedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "userId": "00000000-0000-4000-8000-000000000002",
  "actorId": "00000000-0000-4000-8000-000000000060",
  "previousRoles": [
    "BUYER"
  ],
  "roles": [
    "BUYER",
    "SELLER"
  ],
  "assignedAt": "2026-11-20T10:00:00Z"
}
```

#211 reads and assigns the fixed Keycloak realm-role set; role CRUD remains out of scope. Emit only after a real successful set change. Audit both sets and actor, with no tokens/passwords. Client privileges and role definitions are not newly invented by this event. user_db owns no recreated roles/user_roles tables after #238.

### CategoryCreated

- Topic: `nexus.catalog.events`.
- Publisher: [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106).
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip/no template; no notification recipient; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `categoryId`; Kafka key: `categoryId`.

| Payload field | Meaning |
| --- | --- |
| `categoryId` | UUID of the newly created category. |
| `name` | Category name after the change; maximum 150 characters. |
| `slug` | Category slug after the change; maximum 180 characters. |
| `parentId` | Parent category UUID, or null for a root category. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CategoryCreatedPayload",
  "type": "object",
  "properties": {
    "categoryId": {
      "type": "string",
      "format": "uuid"
    },
    "name": {
      "type": "string",
      "minLength": 1,
      "maxLength": 150
    },
    "slug": {
      "type": "string",
      "minLength": 1,
      "maxLength": 180
    },
    "parentId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "categoryId",
    "name",
    "slug",
    "parentId"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "categoryId": "00000000-0000-4000-8000-000000000007",
  "name": "Electronics",
  "slug": "electronics",
  "parentId": null
}
```

#106 writes the category and outbox row in the same CategoryService transaction. A root category may have parentId=null; this nested null does not violate the non-null envelope. #107 skips the event and logs DEBUG without creating a notification.

### CategoryUpdated

- Topic: `nexus.catalog.events`.
- Publisher: [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106).
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip/no template; no notification recipient; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `categoryId`; Kafka key: `categoryId`.

| Payload field | Meaning |
| --- | --- |
| `categoryId` | UUID of the updated category. |
| `name` | Category name after the change; maximum 150 characters. |
| `slug` | Category slug after the change; maximum 180 characters. |
| `parentId` | Parent category UUID after the update/move, or null for a root category. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CategoryUpdatedPayload",
  "type": "object",
  "properties": {
    "categoryId": {
      "type": "string",
      "format": "uuid"
    },
    "name": {
      "type": "string",
      "minLength": 1,
      "maxLength": 150
    },
    "slug": {
      "type": "string",
      "minLength": 1,
      "maxLength": 180
    },
    "parentId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "categoryId",
    "name",
    "slug",
    "parentId"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "categoryId": "00000000-0000-4000-8000-000000000007",
  "name": "Electronics",
  "slug": "electronics",
  "parentId": null
}
```

Publish the snapshot after the update/move, with parentId=null for a root. #106 commits state and outbox together. #107 applies its generic no-template filter; CategoryUpdated is not separately named in its acceptance tests. Verify the create/update/delete ordering required by #106 when multiple relays run.

### CategoryDeleted

- Topic: `nexus.catalog.events`.
- Publisher: [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106).
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip/no template; no notification recipient; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `categoryId`; Kafka key: `categoryId`.

| Payload field | Meaning |
| --- | --- |
| `categoryId` | UUID of the deleted category, retained in the event after the category is no longer readable. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CategoryDeletedPayload",
  "type": "object",
  "properties": {
    "categoryId": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "categoryId"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "categoryId": "00000000-0000-4000-8000-000000000007"
}
```

Publish only the categoryId of the deleted record after successful deletion. A child/product guard rejection or transaction rollback produces no event. The consumer filter neither creates a notification nor repeats the deletion.

### OrderCreated

- Topic: `nexus.commerce.events`.
- Publisher: [#118](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/118), [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172).
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): notify the buyer; auction-service [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173): settle AUCTION orders; catalog-service [#216](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/216): set has_order on the listed products; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `orderId` | orders.id; identity of the newly created order. |
| `buyerId` | orders.user_id; notification recipient from the persisted order. |
| `orderNumber` | orders.order_number; order number shown to the buyer. |
| `orderType` | STANDARD or AUCTION, matching orders.order_type. |
| `referenceId` | Auction UUID for AUCTION orders; null for STANDARD orders. |
| `orderGroupId` | Persisted group ID; singleton group for an auction order. |
| `sellerId` | This child order's seller. |
| `orderVersion` | Committed commerce order version. |
| `productIds` | Unique product IDs from persisted order_items, required by #216. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderCreatedPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1,
      "maxLength": 40
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderVersion": {
      "type": "integer",
      "minimum": 0
    },
    "productIds": {
      "type": "array",
      "items": {
        "type": "string",
        "format": "uuid"
      },
      "minItems": 1,
      "uniqueItems": true
    }
  },
  "required": [
    "orderId",
    "buyerId",
    "orderNumber",
    "orderType",
    "referenceId",
    "orderGroupId",
    "sellerId",
    "orderVersion",
    "productIds"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null,
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "orderVersion": 0,
  "productIds": [
    "00000000-0000-4000-8000-000000000006"
  ]
}
```

**AUCTION order example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "AUC-20261120-0001",
  "orderType": "AUCTION",
  "referenceId": "00000000-0000-4000-8000-000000000003",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "orderVersion": 0,
  "productIds": [
    "00000000-0000-4000-8000-000000000006"
  ]
}
```

#118 creates STANDARD orders under one group with one order per seller; #172 creates an AUCTION singleton group. STANDARD requires referenceId=null; AUCTION requires referenceId=auctionId. For STANDARD checkout, commit group/order/items, OrderCreated per child and the complete CheckoutPaymentRequested manifest together. For AUCTION, commit group/order/items and OrderCreated first; publish CheckoutPaymentRequested only after the winner confirms shipping information and the final total under #172. #107 notifies by buyerId/orderNumber; #173 settles only AUCTION; #216 sets has_order for every productIds entry. Payment must not create a session/QR from an individual OrderCreated. Replayed checkout keys produce no new events. The array comes from the immutable item snapshot, not a later product lookup.

The STANDARD checkout publisher uses the existing `OrderEventPublisher` and transactional `OutboxWriter`, after the inventory reservation calls and persisted checkout writes. A rejected reservation, price conflict or rolled-back checkout produces no creation event. The Kafka relay publishes only committed outbox rows; checkout does not send directly to Kafka.

### OrderCancelled

- Topic: `nexus.commerce.events`.
- Publisher: [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159), [#189](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/189).
- Consumer: shipment-service [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203): cancel at carrier and persist CANCELLED before pickup, or warn Support after pickup; inventory-service [#161](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/161): release HELD order reservations; notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): notify the buyer; payment-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157), [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247): close unpaid instructions or refund the funded child; auction-service [#208](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/208): timeout AUCTION only for PAYMENT_DEADLINE_EXPIRED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `orderId` | orders.id; inventory finds reservations through order_id. |
| `buyerId` | orders.user_id; recipient of the cancellation notification. |
| `orderNumber` | orders.order_number shown in the notification. |
| `orderType` | STANDARD or AUCTION, matching orders.order_type. |
| `referenceId` | Auction UUID for AUCTION orders; null for STANDARD orders. |
| `cancelledAt` | Committed PENDING or eligible PAID -> CANCELED transition time. |
| `reason` | Exact expiry discriminator for #208, otherwise a nonempty validated manual/group reason. |
| `orderGroupId` | Group containing the cancelled seller order. |
| `sellerId` | Seller owning this child order. |
| `orderVersion` | Committed monotonic commerce order version. |
| `paidAmount` | Trusted paid amount for this child only; zero if unpaid. |
| `currency` | Currency of this persisted snapshot; R1 uses VND. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderCancelledPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1,
      "maxLength": 40
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "cancelledAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 500
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderVersion": {
      "type": "integer",
      "minimum": 0
    },
    "paidAmount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$"
    },
    "currency": {
      "const": "VND"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "orderId",
    "buyerId",
    "orderNumber",
    "orderType",
    "referenceId",
    "cancelledAt",
    "reason",
    "orderGroupId",
    "sellerId",
    "orderVersion",
    "paidAmount",
    "currency",
    "actorId"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null,
  "cancelledAt": "2026-11-20T10:00:00Z",
  "reason": "PAYMENT_DEADLINE_EXPIRED",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "orderVersion": 2,
  "paidAmount": "0.00",
  "currency": "VND",
  "actorId": "00000000-0000-0000-0000-000000000000"
}
```

**Paid manual cancellation example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null,
  "cancelledAt": "2026-11-20T10:30:00Z",
  "reason": "Buyer requested cancellation before carrier handover",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "orderVersion": 2,
  "paidAmount": "200000.50",
  "currency": "VND",
  "actorId": "00000000-0000-4000-8000-000000000002"
}
```

#159 expiry and #189 manual cancellation commit state/history/outbox together through #116. PAYMENT_DEADLINE_EXPIRED is the exact #208 timeout discriminator; other reasons must not penalize the buyer. Before payment cancel the whole group, emit OrderGroupCancelled and per-child OrderCancelled atomically; do not reprice one unpaid child under an existing QR. After payment, the #203 R1 rule allows cancellation until ShipmentPickedUp without a handover request/ACK. Shipment-service consumes OrderCancelled, cancels a booking before pickup and commits CANCELLED/history/outbox with its processed marker; a missing shipment completes with a durable cancellation tombstone that blocks late OrderPaid creation/booking, and an already picked-up shipment is unchanged with a WARN for Support. Validate reason up to 500 characters before carrier effects; store a sanitized audit reason of at most 255 characters. Carrier failure remains retryable. paidAmount is immutable child funding evidence, verified against the payment instruction by #247, never a caller override/group total. #161 releases HELD only and skips EXPIRED/CONFIRMED according to the owned stock flow. Payment retains successful incoming evidence and creates at most one instruction-budget refund. Apply source orderVersion so stale redelivery cannot reopen payment. No cross-topic ordering or cross-database transaction is assumed.

### OrderPaid

- Topic: `nexus.commerce.events`.
- Publisher: [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156).
- Consumer: inventory-service [#160](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/160): confirm order reservations; shipment-service [#202](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/202): create one shipment using this trusted snapshot; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `orderId` | orders.id; inventory finds reservations through order_id. |
| `buyerId` | orders.user_id; buyer UUID. |
| `orderNumber` | orders.order_number at payment time. |
| `orderType` | STANDARD or AUCTION, matching orders.order_type. |
| `referenceId` | Auction UUID for AUCTION orders; null for STANDARD orders. |
| `paidAt` | Persisted successful-payment timestamp in UTC. |
| `orderGroupId` | Group context; this remains an individual seller-order event. |
| `sellerId` | Seller owning this child shipment/order. |
| `orderVersion` | Committed commerce order version. |
| `sessionId` | Shared session that funded this instruction. |
| `instructionId` | Funded instruction for this order. |
| `transactionId` | Shared NORMAL/SUCCESS receipt; do not copy its full amount to this child. |
| `paidAmount` | This child's funded amount. |
| `currency` | Currency of this persisted snapshot; R1 uses VND. |
| `warehouseId` | Trusted selected warehouse from the order/preparation snapshot; never a warehouse code. |
| `shippingFee` | This order's frozen shipping fee; do not charge the group total again. |
| `shippingInfo` | Trusted immutable recipient/address/items snapshot. Nullable parcel/pickupAddress require preparation before a carrier call when required. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderPaidPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1,
      "maxLength": 40
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "paidAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderVersion": {
      "type": "integer",
      "minimum": 0
    },
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "instructionId": {
      "type": "string",
      "format": "uuid"
    },
    "transactionId": {
      "type": "string",
      "format": "uuid"
    },
    "paidAmount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$"
    },
    "currency": {
      "const": "VND"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "shippingFee": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$"
    },
    "shippingInfo": {
      "type": "object",
      "properties": {
        "recipient": {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "minLength": 1,
              "pattern": "\\S",
              "maxLength": 150
            },
            "phone": {
              "type": "string",
              "minLength": 1,
              "pattern": "\\S",
              "maxLength": 20
            },
            "address": {
              "type": "object",
              "properties": {
                "line1": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 500
                },
                "wardCode": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 40
                },
                "districtCode": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 40
                },
                "provinceCode": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 40
                }
              },
              "required": [
                "line1",
                "wardCode",
                "provinceCode"
              ],
              "additionalProperties": true
            }
          },
          "required": [
            "name",
            "phone",
            "address"
          ],
          "additionalProperties": true
        },
        "items": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "skuId": {
                "type": "string",
                "format": "uuid"
              },
              "productName": {
                "type": "string",
                "minLength": 1,
                "pattern": "\\S",
                "maxLength": 255
              },
              "quantity": {
                "type": "integer",
                "minimum": 1
              }
            },
            "required": [
              "skuId",
              "productName",
              "quantity"
            ],
            "additionalProperties": true
          },
          "minItems": 1
        },
        "parcel": {
          "anyOf": [
            {
              "type": "object",
              "properties": {
                "weightGrams": {
                  "type": "integer",
                  "minimum": 1
                },
                "lengthCm": {
                  "type": "number",
                  "exclusiveMinimum": 0
                },
                "widthCm": {
                  "type": "number",
                  "exclusiveMinimum": 0
                },
                "heightCm": {
                  "type": "number",
                  "exclusiveMinimum": 0
                }
              },
              "required": [
                "weightGrams",
                "lengthCm",
                "widthCm",
                "heightCm"
              ],
              "additionalProperties": true
            },
            {
              "type": "null"
            }
          ]
        },
        "pickupAddress": {
          "anyOf": [
            {
              "type": "object",
              "properties": {
                "line1": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 500
                },
                "wardCode": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 40
                },
                "districtCode": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 40
                },
                "provinceCode": {
                  "type": "string",
                  "minLength": 1,
                  "pattern": "\\S",
                  "maxLength": 40
                }
              },
              "required": [
                "line1",
                "wardCode",
                "provinceCode"
              ],
              "additionalProperties": true
            },
            {
              "type": "null"
            }
          ]
        }
      },
      "required": [
        "recipient",
        "items",
        "parcel",
        "pickupAddress"
      ],
      "additionalProperties": true
    }
  },
  "required": [
    "orderId",
    "buyerId",
    "orderNumber",
    "orderType",
    "referenceId",
    "paidAt",
    "orderGroupId",
    "sellerId",
    "orderVersion",
    "sessionId",
    "instructionId",
    "transactionId",
    "paidAmount",
    "currency",
    "warehouseId",
    "shippingFee",
    "shippingInfo"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null,
  "paidAt": "2026-11-20T10:00:00Z",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "orderVersion": 1,
  "sessionId": "00000000-0000-4000-8000-000000000021",
  "instructionId": "00000000-0000-4000-8000-000000000022",
  "transactionId": "00000000-0000-4000-8000-000000000023",
  "paidAmount": "200000.50",
  "currency": "VND",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "shippingFee": "15000.00",
  "shippingInfo": {
    "recipient": {
      "name": "Demo Recipient",
      "phone": "0900000000",
      "address": {
        "line1": "1 Demo Street",
        "wardCode": "W01",
        "provinceCode": "P01"
      }
    },
    "items": [
      {
        "skuId": "00000000-0000-4000-8000-000000000005",
        "productName": "Demo item",
        "quantity": 1
      }
    ],
    "parcel": null,
    "pickupAddress": null
  }
}
```

#156 consumes authoritative CheckoutPaymentConfirmed from payment-service under the group design. Commerce validates the complete plan and commits eligible child PAID/history/OrderPaid/processed marker together; payment owns the separate funding/ledger transaction. paidAmount is the instruction share, not the session total. #160 confirms reservations; #202 creates one shipment per seller order using buyerId/warehouseId/shippingInfo and shippingFee. Locality codes shown in the example are synthetic and are not valid GHN booking data. Missing real measurements/pickup data leaves PENDING_TRACKING/preparation; never fabricate them or query commerce_db. For an AUCTION order without order reservations, #160 logs/marks the no-op; #209 owns the auction reservation. Late/cancelled/unreconciled payments emit no OrderPaid for cancelled children. Historical messages lacking new required fields need an explicit backfill/replay or durable validation-retry policy before these consumers are enabled.

### OrderCompleted

- Topic: `nexus.commerce.events`.
- Publisher: [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204).
- Consumer: rating-service [#242](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/242), [#212](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/212): persist rating_eligibility without calling commerce; rating-service [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213): update score and transaction counts; notification-service [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191): deliver the HTML receipt; [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `orderId` | Canonical seller-order UUID; never the order-group UUID. |
| `orderGroupId` | Stored order_groups.id; auction orders also have a singleton group. |
| `buyerId` | Buyer from the immutable commerce order snapshot. |
| `sellerId` | Seller owning this child order. |
| `orderType` | STANDARD or AUCTION, from the persisted order. |
| `referenceId` | Auction UUID for AUCTION; null for STANDARD. |
| `orderVersion` | Committed commerce orders.version, not a schema version or payment version. |
| `completedAt` | Persisted business completion time; maps to rating_eligibility.completed_at. |
| `orderNumber` | Optional human-readable seller-order number from commerce; #204 includes it for the receipt notification. |
| `receiptHtml` | Optional HTML receipt with escaped snapshot fields from this completed seller order; #204 includes the document for delivery through notification-service #191. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderCompletedPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "orderVersion": {
      "type": "integer",
      "minimum": 0
    },
    "completedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1
    },
    "receiptHtml": {
      "type": "string",
      "minLength": 1
    }
  },
  "required": [
    "orderId",
    "orderGroupId",
    "buyerId",
    "sellerId",
    "orderType",
    "referenceId",
    "orderVersion",
    "completedAt"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "orderType": "STANDARD",
  "referenceId": null,
  "orderVersion": 1,
  "completedAt": "2026-11-20T10:00:00Z",
  "orderNumber": "NX-20261120-00000001",
  "receiptHtml": "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Receipt NX-20261120-00000001</title></head><body><h1>Order receipt</h1><p>NX-20261120-00000001</p><p>Completed: 2026-11-20T10:00:00Z</p></body></html>"
}
```

#204 commits the DELIVERED -> COMPLETED transition, history and outbox together after ShipmentDelivered. #242 stores orderId/buyerId/sellerId/completedAt and referenceId as auction_id when applicable. #213 applies each canonical order outcome once per affected user; envelope deduplication alone does not prevent two different eventIds recounting the same order. Buyer/seller must differ. No new rating permission is inferred from a payment or shipment-created event.

#204 also includes `orderNumber` and `receiptHtml` as additive payload fields. The receipt is a snapshot of the completed child order, including its own items and amounts, never the whole group's payment. The event/email document omits the delivery recipient's name, phone and address; those contact details remain available only in the owner's authenticated invoice response. Notification-service uses the buyer ID to resolve the email recipient and queues the HTML document through #191's durable email delivery; commerce does not send SMTP inside its order transaction. These extension fields remain optional for existing event fixtures and consumers; consumers that send a receipt require a nonblank HTML document and never substitute a buyer-supplied URL or product HTML. This limits contact data in newly published events; existing Kafka/audit records are unchanged. Moving the remaining receipt HTML off the shared event requires a separate authenticated receipt retrieval contract.

Shipment facts whose order, payment or pickup prerequisite has not arrived are
retained in commerce's `pending_order_shipment_events` inbox atomically with the
consumer marker. The retry job can apply them after a restart without blocking
the Kafka partition or requiring the carrier to publish again. Successful retry
removes the inbox row together with the order history and completion outbox;
failed effects leave the persisted fact available for another attempt.

### OrderRefundRequested

- Topic: `nexus.payment.events`.
- Publisher: [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247).
- Consumer: commerce-service [#116](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/116): move CANCELED -> REFUND_REQUESTED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `refundId` | The one R1 refund operation for this seller order. |
| `orderId` | Cancelled, funded seller order. |
| `orderGroupId` | Context only; this refund does not refund sibling orders. |
| `sessionId` | Shared payment session, resolved from the local instruction. |
| `instructionId` | Local funded instruction for this order. |
| `incomingTransactionId` | Preserved NORMAL/SUCCESS funding transaction, shared by sibling instructions. |
| `amount` | Positive requested/completed refund amount for this child; bounded by its funded share. |
| `currency` | Currency of this persisted snapshot; R1 uses VND. |
| `status` | Persisted refund status. |
| `reason` | Nonempty stored refund/cancellation reason. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `refundVersion` | Committed refunds.version; serialize completion against duplicate requests. |
| `instructionVersion` | Committed local instruction.version used to order this order's financial snapshots. |
| `requestedAt` | Original request time; retained on completion. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderRefundRequestedPayload",
  "type": "object",
  "properties": {
    "refundId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "instructionId": {
      "type": "string",
      "format": "uuid"
    },
    "incomingTransactionId": {
      "type": "string",
      "format": "uuid"
    },
    "amount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$",
      "not": {
        "pattern": "^0+\\.00$"
      }
    },
    "currency": {
      "const": "VND"
    },
    "status": {
      "const": "REQUESTED"
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "refundVersion": {
      "type": "integer",
      "minimum": 0
    },
    "instructionVersion": {
      "type": "integer",
      "minimum": 0
    },
    "requestedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "refundId",
    "orderId",
    "orderGroupId",
    "sessionId",
    "instructionId",
    "incomingTransactionId",
    "amount",
    "currency",
    "status",
    "reason",
    "actorId",
    "refundVersion",
    "instructionVersion",
    "requestedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "refundId": "00000000-0000-4000-8000-000000000030",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sessionId": "00000000-0000-4000-8000-000000000021",
  "instructionId": "00000000-0000-4000-8000-000000000022",
  "incomingTransactionId": "00000000-0000-4000-8000-000000000023",
  "amount": "200000.50",
  "currency": "VND",
  "status": "REQUESTED",
  "reason": "Buyer cancellation before handover",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "refundVersion": 1,
  "instructionVersion": 1,
  "requestedAt": "2026-11-20T10:00:00Z"
}
```

#247 creates the REQUESTED refund and this outbox row atomically. actorId is refunds.requested_by; automatic compensation uses the system UUID and stable system:cancel-refund:{orderId} identity. At most one refund exists per order, including an API request racing an automatic cancellation. If an amount is supplied, preserve it exactly after validating against the funded instruction; if omitted, resolve that child's budget. Never use the entire session receipt as each seller's refund. No bank transfer has completed yet. Commerce validates the status transition and tracks refund identity/version so late REQUESTED redelivery cannot undo REFUNDED.

### OrderRefundCompleted

- Topic: `nexus.payment.events`.
- Publisher: [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247).
- Consumer: commerce-service [#116](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/116): move REFUND_REQUESTED -> REFUNDED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log and audit.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `refundId` | The one R1 refund operation for this seller order. |
| `orderId` | Cancelled, funded seller order. |
| `orderGroupId` | Context only; this refund does not refund sibling orders. |
| `sessionId` | Shared payment session, resolved from the local instruction. |
| `instructionId` | Local funded instruction for this order. |
| `incomingTransactionId` | Preserved NORMAL/SUCCESS funding transaction, shared by sibling instructions. |
| `amount` | Positive requested/completed refund amount for this child; bounded by its funded share. |
| `currency` | Currency of this persisted snapshot; R1 uses VND. |
| `status` | Persisted refund status. |
| `reason` | Nonempty stored refund/cancellation reason. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `refundVersion` | Committed refunds.version; serialize completion against duplicate requests. |
| `instructionVersion` | Committed local instruction.version used to order this order's financial snapshots. |
| `requestedAt` | Original request time; retained on completion. |
| `completedTransactionId` | The single REFUND/SUCCESS outgoing ledger movement. |
| `completedAt` | Persisted time Support confirms the manual transfer. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderRefundCompletedPayload",
  "type": "object",
  "properties": {
    "refundId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "instructionId": {
      "type": "string",
      "format": "uuid"
    },
    "incomingTransactionId": {
      "type": "string",
      "format": "uuid"
    },
    "amount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$",
      "not": {
        "pattern": "^0+\\.00$"
      }
    },
    "currency": {
      "const": "VND"
    },
    "status": {
      "const": "COMPLETED"
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "refundVersion": {
      "type": "integer",
      "minimum": 0
    },
    "instructionVersion": {
      "type": "integer",
      "minimum": 0
    },
    "requestedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "completedTransactionId": {
      "type": "string",
      "format": "uuid"
    },
    "completedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "refundId",
    "orderId",
    "orderGroupId",
    "sessionId",
    "instructionId",
    "incomingTransactionId",
    "amount",
    "currency",
    "status",
    "reason",
    "actorId",
    "refundVersion",
    "instructionVersion",
    "requestedAt",
    "completedTransactionId",
    "completedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "refundId": "00000000-0000-4000-8000-000000000030",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "sessionId": "00000000-0000-4000-8000-000000000021",
  "instructionId": "00000000-0000-4000-8000-000000000022",
  "incomingTransactionId": "00000000-0000-4000-8000-000000000023",
  "amount": "200000.50",
  "currency": "VND",
  "status": "COMPLETED",
  "reason": "Buyer cancellation before handover",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "refundVersion": 1,
  "instructionVersion": 1,
  "requestedAt": "2026-11-20T10:00:00Z",
  "completedTransactionId": "00000000-0000-4000-8000-000000000031",
  "completedAt": "2026-11-20T10:30:00Z"
}
```

#247 atomically commits Support's completion, its outgoing ledger reference and outbox. actorId is completed_by. Preserve requestedAt and incoming funding evidence; completedAt >= requestedAt. Amount/currency/order/instruction must agree with the original refund. Repeating completion adds no ledger row/event. On missing REQUESTED prerequisites, retain durable retry; do not skip the state machine or mark a failed handler processed.

### AuctionCreated

- Topic: `nexus.auction.events`.
- Publisher: [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209).
- Consumer: inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): hold one unit for the auction; catalog-service [#216](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/216): set product in_auction; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | New auction UUID; reservation lookup uses this source identity. |
| `sellerId` | Seller from the stored auction. |
| `productId` | Product whose flag #216 updates. |
| `skuId` | SKU to hold in inventory. |
| `quantity` | R1 auction holds one unit. |
| `startAt` | Scheduled start. |
| `endAt` | Initial effective end. |
| `auctionVersion` | Committed auction version. |
| `createdAt` | Auction create transaction time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionCreatedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "quantity": {
      "const": 1
    },
    "startAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "auctionVersion": {
      "type": "integer",
      "minimum": 0
    },
    "createdAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "quantity",
    "startAt",
    "endAt",
    "auctionVersion",
    "createdAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "quantity": 1,
  "startAt": "2026-11-20T11:00:00Z",
  "endAt": "2026-11-20T12:00:00Z",
  "auctionVersion": 1,
  "createdAt": "2026-11-20T10:00:00Z"
}
```

#209 writes this event with auction creation. Inventory selects a real warehouse under its stock policy, stores auction_id on the reservation and writes its hold/movement/InventoryReserved/processed marker together. No warehouse UUID is fabricated from an auction column that does not exist. Deduplicate also on auction/SKU/warehouse so a different envelope cannot hold twice. #216 only updates the product flag. startAt < endAt. Auction checks #132 availability before creation and returns 409 for zero stock. Inventory reserves with a conditional balance update when consuming the event. Stock can change between these operations.

### AuctionStarted

- Topic: `nexus.auction.events`.
- Publisher: [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | UUID from auctions.id. |
| `sellerId` | Seller UUID from auctions.seller_id. |
| `productId` | Product UUID from the auction snapshot. |
| `skuId` | SKU UUID from the auction snapshot. |
| `startAt` | Scheduled start time. |
| `endAt` | Effective deadline, including committed anti-sniping extensions. |
| `startedAt` | Actual state-machine transition time to ACTIVE. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionStartedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "startAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "startedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "startAt",
    "endAt",
    "startedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "startAt": "2026-11-20T08:00:00Z",
  "endAt": "2026-11-20T10:00:00Z",
  "startedAt": "2026-11-20T08:00:01Z"
}
```

#145 commits the SCHEDULED to ACTIVE transition and event atomically. startAt must precede endAt; startedAt is the actual transition time and may reflect scheduler delay. Two scheduler instances must not create duplicate events.

### BidPlaced

- Topic: `nexus.auction.events`.
- Publisher: [#146](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/146).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | UUID of the auction receiving the accepted bid. |
| `bidId` | Persisted bid UUID. |
| `bidderId` | UUID of the accepted bidder. |
| `amount` | Positive VND decimal string with exactly two fractional digits; parse/serialize with BigDecimal in Java. |
| `currency` | VND. |
| `placedAt` | Persisted bid timestamp in UTC. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "BidPlacedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "bidId": {
      "type": "string",
      "format": "uuid"
    },
    "bidderId": {
      "type": "string",
      "format": "uuid"
    },
    "amount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$",
      "not": {
        "pattern": "^0+\\.00$"
      }
    },
    "currency": {
      "const": "VND"
    },
    "placedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "bidId",
    "bidderId",
    "amount",
    "currency",
    "placedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "bidId": "00000000-0000-4000-8000-000000000008",
  "bidderId": "00000000-0000-4000-8000-000000000002",
  "amount": "1500000.00",
  "currency": "VND",
  "placedAt": "2026-11-20T10:00:00Z"
}
```

#146 publishes only after the conditional price UPDATE and bid INSERT succeed in one transaction. Rejection or rollback produces no event. amount must be positive and meet currentPrice + bidIncrement. #147 retries with the same Idempotency-Key create no second event. #149 may extend endAt in the same transaction. Outbid remains R2. Use numeric(19,2)/BigDecimal from #66/#67; reconcile the older bigint wording in #140/#146 without truncating decimal values.

### AuctionCancelled

- Topic: `nexus.auction.events`.
- Publisher: [#197](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/197).
- Consumer: inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): release the auction hold; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | Cancelled auction UUID. |
| `sellerId` | Auction seller. |
| `productId` | Stored product context. |
| `skuId` | Stored SKU context. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `cancelledBy` | SELLER or ADMIN for manual cancellation, SYSTEM for automatic stock rejection. |
| `reason` | Stored cancellation reason. |
| `auctionVersion` | Committed auction version. |
| `cancelledAt` | Accepted transition time to CANCELLED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionCancelledPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "cancelledBy": {
      "type": "string",
      "enum": [
        "SELLER",
        "ADMIN",
        "SYSTEM"
      ]
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 255
    },
    "auctionVersion": {
      "type": "integer",
      "minimum": 0
    },
    "cancelledAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "actorId",
    "cancelledBy",
    "reason",
    "auctionVersion",
    "cancelledAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "cancelledBy": "ADMIN",
  "reason": "Cancelled by admin before settlement",
  "auctionVersion": 1,
  "cancelledAt": "2026-11-20T10:00:00Z"
}
```

#197 commits cancellation/reason/actor/outbox through the state machine. Seller permission requires no valid bids; Admin permission and final-state restrictions remain service checks. #209 releases only an existing HELD auction reservation once and emits InventoryReleased in that local transaction. Never release stock twice when AuctionFailed/cancellation/redelivery race; do not manufacture an order ID before settlement.

### AuctionEnded

- Topic: `nexus.auction.events`.
- Publisher: [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145).
- Consumer: catalog-service [#216](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/216): clear product in_auction; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | UUID from auctions.id. |
| `sellerId` | Seller UUID from auctions.seller_id. |
| `productId` | Product UUID from the auction snapshot. |
| `skuId` | SKU UUID from the auction snapshot. |
| `startAt` | Scheduled start time. |
| `endAt` | Effective deadline after all anti-sniping extensions. |
| `endedAt` | Actual state-machine transition time to ENDED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionEndedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "startAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "startAt",
    "endAt",
    "endedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "startAt": "2026-11-20T08:00:00Z",
  "endAt": "2026-11-20T10:00:00Z",
  "endedAt": "2026-11-20T10:00:00Z"
}
```

#145 publishes after ACTIVE becomes ENDED. endAt includes anti-sniping extensions; endedAt must be at or after endAt. #170 selects ENDED auctions from the database. Catalog marks the auction closed on AuctionEnded or AuctionCancelled and clears in_auction once no open auction remains for the product. The consumer commits the state and processed marker together. Closed markers also prevent a late AuctionCreated from reopening the flag.

### AuctionWon

- Topic: `nexus.auction.events`.
- Publisher: [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171).
- Consumer: commerce-service [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172): create the AUCTION order and singleton group; inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): record the winning buyer on the hold; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | UUID of the auction whose winner has been determined. |
| `sellerId` | Seller UUID from the auction snapshot. |
| `productId` | Product UUID from the auction snapshot. |
| `skuId` | SKU UUID from the auction snapshot. |
| `winnerId` | Winning bidder UUID; becomes orders.user_id. |
| `finalPrice` | Nonnegative winning VND decimal string with exactly two fractional digits; BigDecimal under #66/#67. |
| `currency` | VND. |
| `title` | Product title captured at auction creation; becomes order_items.product_name. |
| `imageUrl` | Snapshot image URI; required and non-null under #139/#171. The nullable database source needs an image policy before publication. |
| `quantity` | Proposed fixed quantity of one unit per auction; the auction schema has no quantity column. |
| `endAt` | Effective end time after extensions. |
| `paymentDueAt` | endAt plus auction.payment-deadline-hours under #170; commerce preserves this deadline. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionWonPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "winnerId": {
      "type": "string",
      "format": "uuid"
    },
    "finalPrice": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$"
    },
    "currency": {
      "const": "VND"
    },
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 255
    },
    "imageUrl": {
      "type": "string",
      "minLength": 1,
      "format": "uri",
      "maxLength": 500
    },
    "quantity": {
      "const": 1
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "paymentDueAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "winnerId",
    "finalPrice",
    "currency",
    "title",
    "imageUrl",
    "quantity",
    "endAt",
    "paymentDueAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "winnerId": "00000000-0000-4000-8000-000000000002",
  "finalPrice": "1500000.00",
  "currency": "VND",
  "title": "Auction item",
  "imageUrl": "https://example.com/product.jpg",
  "quantity": 1,
  "endAt": "2026-11-20T10:00:00Z",
  "paymentDueAt": "2026-11-21T10:00:00Z"
}
```

#170 selects the highest valid bid; the earlier bid wins a tie. paymentDueAt is the effective endAt plus the configured auction.payment-deadline-hours, not consumer receive time plus a standard checkout timeout. #171 builds the payload from the auction snapshot and commits WON/outbox together; repeated runs publish nothing. Do not call auction or catalog from commerce to complete an order line.

**AuctionWon to #172 persistence mapping**

| Event input | Auction source / rule | Commerce destination / rule |
| --- | --- | --- |
| `auctionId` | auctions.id | orders.reference_id with order_type=AUCTION; canonical duplicate guard |
| `winnerId` | auctions.winner_id after #170 | orders.user_id and order_groups.buyer_id |
| `sellerId` | auctions.seller_id | orders.seller_id |
| `productId`, `skuId` | Immutable auction product/SKU references | order_items.product_id and sku_id |
| `title` | auctions.title snapshot | order_items.product_name; no later catalog lookup |
| `imageUrl` | auctions.image_url snapshot; valid non-null URI before #171 publishes | order_items.image_url; preserve the snapshot |
| `quantity` | Exactly 1 for the held auction unit | order_items.quantity=1; no new stock reservation |
| `finalPrice` | auctions.final_price, decimal string with two fractional digits | Parse BigDecimal; order_items.unit_price; total_price=unit_price*quantity; initial orders.subtotal/total_amount and order_groups.total_amount |
| `currency` | VND | order_groups.currency |
| `endAt`, `paymentDueAt` | Effective auction end and its configured deadline | orders.payment_due_at and order_groups.due_at use paymentDueAt unchanged; endAt supplies deadline context |

Commerce generates order/group/item IDs, an AUC-prefixed order number, initial PENDING state and its own idempotency key/history locally; these are not missing auction fields. Nullable order_items.sku_code can remain null: skuId is the SKU identity. AuctionWon carries no recipient/address, warehouseId or shipping quote. #172 obtains the linked hold's warehouse under #209 and calculates shipping on the later confirmation step, without a call back into auction-service. It does not invent address data or start a payment session before that step.

**Image and quantity:** #170 requires a valid first Catalog image at creation and copies it into the auction row. AuctionWon publishes that stored URL and a quantity of one. For older auctions with a missing or invalid snapshot, settlement rolls back and the auction stays ENDED for repair and retry.

#172 commits the order and OrderCreated without a payment manifest or a new reservation. #173 settles after OrderCreated, not after payment.

### AuctionFailed

- Topic: `nexus.auction.events`.
- Publisher: [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171).
- Consumer: inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): release the auction hold; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | UUID of the auction that failed because it received no bids. |
| `sellerId` | Seller UUID from the auction snapshot. |
| `productId` | Product UUID from the auction snapshot. |
| `skuId` | SKU UUID from the auction snapshot. |
| `reason` | Proposed NO_BIDS code for the no-bid branch in #170. |
| `endAt` | Effective auction end deadline. |
| `failedAt` | Time of the state-machine transition to FAILED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionFailedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "reason": {
      "const": "NO_BIDS"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "failedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "reason",
    "endAt",
    "failedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "reason": "NO_BIDS",
  "endAt": "2026-11-20T10:00:00Z",
  "failedAt": "2026-11-20T10:00:00Z"
}
```

With no bids, #170/#171 commit FAILED and AuctionFailed together with reason=NO_BIDS. Do not fabricate winnerId or finalPrice; published fields remain non-null under #171. Repeated runs and rollbacks produce no additional event. #172 consumes only AuctionWon. #209 now consumes this outcome to release the HELD auction reservation and publishes InventoryReleased once.

### AuctionPaymentTimeout

- Topic: `nexus.auction.events`.
- Publisher: [#208](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/208).
- Consumer: rating-service [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213): apply the late-payment penalty to the winner once; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | AUCTION OrderCancelled.referenceId, checked against the local auction. |
| `orderId` | Cancelled canonical auction order. |
| `winnerId` | Winning buyer read from the auction, not an arbitrary bidder. |
| `sellerId` | Seller context; never the penalty target. |
| `paymentDueAt` | Original auction payment deadline. |
| `timedOutAt` | Committed transition time to PAYMENT_TIMEOUT. |
| `reason` | Exact #208 cancellation discriminator. |
| `auctionVersion` | Committed auction version. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionPaymentTimeoutPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "winnerId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "paymentDueAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "timedOutAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "const": "PAYMENT_DEADLINE_EXPIRED"
    },
    "auctionVersion": {
      "type": "integer",
      "minimum": 0
    }
  },
  "required": [
    "auctionId",
    "orderId",
    "winnerId",
    "sellerId",
    "paymentDueAt",
    "timedOutAt",
    "reason",
    "auctionVersion"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "winnerId": "00000000-0000-4000-8000-000000000002",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "paymentDueAt": "2026-11-20T10:00:00Z",
  "timedOutAt": "2026-11-20T10:30:00Z",
  "reason": "PAYMENT_DEADLINE_EXPIRED",
  "auctionVersion": 1
}
```

#208 consumes OrderCancelled only for orderType=AUCTION and reason=PAYMENT_DEADLINE_EXPIRED. Ignore STANDARD and unrelated reasons. Read winner/deadline from local auction state, then commit PAYMENT_TIMEOUT/outbox/processed marker together. #213 penalizes winnerId once per auction/order business outcome using configured policy, not all bidders or the seller. A duplicate envelope or another eventId for the same outcome must not penalize twice; timedOutAt >= paymentDueAt.

### AuctionSettled

- Topic: `nexus.auction.events`.
- Publisher: [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173).
- Consumer: inventory-service [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209): attach orderId to the existing HELD reservation without a balance change or stock movement; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `auctionId` | Auction UUID from OrderCreated.referenceId. |
| `orderId` | UUID of the newly created AUCTION order. |
| `settledAt` | Time of the WON to SETTLED transition in UTC. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionSettledPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "settledAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "orderId",
    "settledAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "settledAt": "2026-11-20T10:00:00Z"
}
```

#173 consumes AUCTION OrderCreated, moves WON to SETTLED, stores orderId and publishes one AuctionSettled in the same transaction as the processed marker. Ignore STANDARD orders; for an auction outside WON, log WARN and leave state unchanged. SETTLED means an order exists, not that it is paid. #209 attaches orderId to the existing HELD reservation on this event. Stock balances and movement rows stay unchanged. OrderPaid (#160) commits the hold and publishes InventoryCommitted. OrderCancelled (#161) releases or returns stock. Repeated settlement delivery preserves the same reservation.

### InventoryReserved

- Topic: `nexus.inventory.events`.
- Publisher: [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log only; stock was already changed by inventory.
- Envelope aggregateId: `reservationId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `reservationId` | Stable inventory reservation UUID; retain when linking an auction to its order. |
| `auctionId` | Auction source for the #209 hold. |
| `orderId` | Null before an auction order exists; populated after settlement. |
| `skuId` | Reserved SKU UUID. |
| `warehouseId` | Actual inventory warehouse UUID. |
| `quantity` | Quantity changed once by this transition. |
| `status` | Persisted reservation outcome; movement type is separate. |
| `reservedAt` | Committed inventory transition time. |
| `expiresAt` | Stored reservation expiry deadline; never recalculate historical deadlines. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "InventoryReservedPayload",
  "type": "object",
  "properties": {
    "reservationId": {
      "type": "string",
      "format": "uuid"
    },
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "quantity": {
      "type": "integer",
      "minimum": 1
    },
    "status": {
      "const": "HELD"
    },
    "reservedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "expiresAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "reservationId",
    "auctionId",
    "orderId",
    "skuId",
    "warehouseId",
    "quantity",
    "status",
    "reservedAt",
    "expiresAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "reservationId": "00000000-0000-4000-8000-000000000009",
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "orderId": null,
  "skuId": "00000000-0000-4000-8000-000000000005",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "quantity": 1,
  "status": "HELD",
  "reservedAt": "2026-11-20T10:00:00Z",
  "expiresAt": "2026-11-20T10:30:00Z"
}
```

#209 owns these auction-stock publications. InventoryReserved follows AuctionCreated; InventoryReleased follows AuctionFailed/AuctionCancelled; InventoryCommitted follows OrderPaid (#160) for the linked auction hold. In each case, state, balance, RESERVE/RELEASE/COMMIT movement, outbox and processed marker commit in one inventory transaction. Publish only when the conditional transition changes stock. An event-log consumer does not perform the mutation again. CONFIRMED is the existing reservation status used by the commit operation; no COMMITTED reservation status is invented. Keep the same reservation when order_id is attached after settlement. Order-based #134/#136 publications are not added merely because the schema can describe stock.

### InventoryReleased

- Topic: `nexus.inventory.events`.
- Publisher: [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log only; stock was already changed by inventory.
- Envelope aggregateId: `reservationId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `reservationId` | Stable inventory reservation UUID; retain when linking an auction to its order. |
| `auctionId` | Auction source for the #209 hold. |
| `orderId` | Null before an auction order exists; populated after settlement. |
| `skuId` | Reserved SKU UUID. |
| `warehouseId` | Actual inventory warehouse UUID. |
| `quantity` | Quantity changed once by this transition. |
| `status` | Persisted reservation outcome; movement type is separate. |
| `releasedAt` | Committed inventory transition time. |
| `reason` | Source auction outcome causing this release. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "InventoryReleasedPayload",
  "type": "object",
  "properties": {
    "reservationId": {
      "type": "string",
      "format": "uuid"
    },
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "quantity": {
      "type": "integer",
      "minimum": 1
    },
    "status": {
      "const": "RELEASED"
    },
    "releasedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "type": "string",
      "enum": [
        "AUCTION_FAILED",
        "AUCTION_CANCELLED"
      ]
    }
  },
  "required": [
    "reservationId",
    "auctionId",
    "orderId",
    "skuId",
    "warehouseId",
    "quantity",
    "status",
    "releasedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "reservationId": "00000000-0000-4000-8000-000000000009",
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "orderId": null,
  "skuId": "00000000-0000-4000-8000-000000000005",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "quantity": 1,
  "status": "RELEASED",
  "releasedAt": "2026-11-20T10:00:00Z",
  "reason": "AUCTION_CANCELLED"
}
```

#209 owns these auction-stock publications. InventoryReserved follows AuctionCreated; InventoryReleased follows AuctionFailed/AuctionCancelled; InventoryCommitted follows OrderPaid (#160) for the linked auction hold. In each case, state, balance, RESERVE/RELEASE/COMMIT movement, outbox and processed marker commit in one inventory transaction. Publish only when the conditional transition changes stock. An event-log consumer does not perform the mutation again. CONFIRMED is the existing reservation status used by the commit operation; no COMMITTED reservation status is invented. Keep the same reservation when order_id is attached after settlement. Order-based #134/#136 publications are not added merely because the schema can describe stock.

### InventoryCommitted

- Topic: `nexus.inventory.events`.
- Publisher: [#209](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/209).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log only; stock was already changed by inventory.
- Envelope aggregateId: `reservationId`; Kafka key: `auctionId`.

| Payload field | Meaning |
| --- | --- |
| `reservationId` | Stable inventory reservation UUID; retain when linking an auction to its order. |
| `auctionId` | Auction source for the #209 hold. |
| `orderId` | Canonical auction order linked at settlement. |
| `skuId` | Reserved SKU UUID. |
| `warehouseId` | Actual inventory warehouse UUID. |
| `quantity` | Quantity changed once by this transition. |
| `status` | Persisted reservation outcome; movement type is separate. |
| `committedAt` | Committed inventory transition time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "InventoryCommittedPayload",
  "type": "object",
  "properties": {
    "reservationId": {
      "type": "string",
      "format": "uuid"
    },
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "quantity": {
      "type": "integer",
      "minimum": 1
    },
    "status": {
      "const": "CONFIRMED"
    },
    "committedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "reservationId",
    "auctionId",
    "orderId",
    "skuId",
    "warehouseId",
    "quantity",
    "status",
    "committedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "reservationId": "00000000-0000-4000-8000-000000000009",
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "quantity": 1,
  "status": "CONFIRMED",
  "committedAt": "2026-11-20T10:00:00Z"
}
```

#209 owns these auction-stock publications. InventoryReserved follows AuctionCreated; InventoryReleased follows AuctionFailed/AuctionCancelled; InventoryCommitted follows OrderPaid (#160) for the linked auction hold. In each case, state, balance, RESERVE/RELEASE/COMMIT movement, outbox and processed marker commit in one inventory transaction. Publish only when the conditional transition changes stock. An event-log consumer does not perform the mutation again. CONFIRMED is the existing reservation status used by the commit operation; no COMMITTED reservation status is invented. Keep the same reservation when order_id is attached after settlement. Order-based #134/#136 publications are not added merely because the schema can describe stock.

### InventoryReservationExpired

- Topic: `nexus.inventory.events`.
- Publisher: [#175](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/175).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `reservationId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `reservationId` | UUID of the expired reservation; envelope aggregateId. |
| `orderId` | UUID of the order owning the reservation; proposed Kafka key. |
| `skuId` | UUID of the reserved SKU. |
| `warehouseId` | Warehouse UUID, not the WH_001 warehouse code. |
| `quantity` | Quantity returned to available stock by the expiry transaction. |
| `expiresAt` | Original reservation deadline; configuration refresh does not change persisted deadlines. |
| `releasedAt` | Time the expiry transaction released stock and recorded EXPIRED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "InventoryReservationExpiredPayload",
  "type": "object",
  "properties": {
    "reservationId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "quantity": {
      "type": "integer",
      "minimum": 1
    },
    "expiresAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "releasedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "reservationId",
    "orderId",
    "skuId",
    "warehouseId",
    "quantity",
    "expiresAt",
    "releasedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "reservationId": "00000000-0000-4000-8000-000000000009",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "quantity": 2,
  "expiresAt": "2026-11-20T10:00:00Z",
  "releasedAt": "2026-11-20T10:00:01Z"
}
```

#175 batch-expires overdue HELD reservations, returns quantity to available stock, writes a RELEASE movement and writes the outbox row in the same transaction. releasedAt must be at or after expiresAt. Publish one event per changed reservation; repeated runs change neither stock nor event count. R1 reservations carry orderId and do not use cart_id under #134; warehouseId is the UUID resolved from the warehouse code. InventoryReleased is now specified for #209 auction releases; #175 does not add an extra publication merely from this reclassification. #161 consumes OrderCancelled and skips EXPIRED reservations; stock must not be released again in response to this expiry event.

### AuctionStockRejected

- Topic: `nexus.inventory.events`.
- Publisher: inventory-service #170, when the conditional auction hold finds no available stock.
- Consumer: auction-service #170.
- Envelope aggregateId and Kafka key: `auctionId`.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionStockRejectedPayload",
  "type": "object",
  "properties": {
    "auctionId": { "type": "string", "format": "uuid" },
    "skuId": { "type": "string", "format": "uuid" },
    "reason": { "const": "OUT_OF_STOCK" }
  },
  "required": ["auctionId", "skuId", "reason"]
}
```

Inventory commits the processed AuctionCreated marker and rejection outbox row together, without a hold or movement. Auction locks the matching auction and SKU and cancels only CREATED, SCHEDULED or ACTIVE. It records `cancelledBy=SYSTEM`, the all-zero system actor UUID and reason `STOCK_UNAVAILABLE`, then writes AuctionCancelled in the same transaction as the processed marker. Replays leave the cancellation and stock unchanged.

### InventoryStockUnavailable

- Topic: `nexus.inventory.events`.
- Publisher: inventory-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157).
- Consumer: commerce-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157): cancel the PAID order and publish OrderCancelled for the refund.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `orderId` | UUID of the paid order whose stock is missing; envelope aggregateId. |
| `reservations` | The EXPIRED reservations of the order that available stock can no longer cover. |
| `reservations[].reservationId` | UUID of the expired reservation. |
| `reservations[].skuId` | UUID of the SKU. |
| `reservations[].warehouseId` | Warehouse UUID. |
| `reservations[].quantity` | Quantity the order needs back. |
| `reservations[].availableQuantity` | Available stock of that SKU and warehouse when OrderPaid was handled. |
| `detectedAt` | Time inventory handled the late OrderPaid. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "InventoryStockUnavailablePayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "reservations": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "properties": {
          "reservationId": {
            "type": "string",
            "format": "uuid"
          },
          "skuId": {
            "type": "string",
            "format": "uuid"
          },
          "warehouseId": {
            "type": "string",
            "format": "uuid"
          },
          "quantity": {
            "type": "integer",
            "minimum": 1
          },
          "availableQuantity": {
            "type": "integer",
            "minimum": 0
          }
        },
        "required": [
          "reservationId",
          "skuId",
          "warehouseId",
          "quantity",
          "availableQuantity"
        ],
        "additionalProperties": true
      }
    },
    "detectedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "orderId",
    "reservations",
    "detectedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "reservations": [
    {
      "reservationId": "00000000-0000-4000-8000-000000000009",
      "skuId": "00000000-0000-4000-8000-000000000005",
      "warehouseId": "00000000-0000-4000-8000-000000000010",
      "quantity": 2,
      "availableQuantity": 0
    }
  ],
  "detectedAt": "2026-11-20T10:12:00Z"
}
```

Inventory handles OrderPaid with the order's reservation rows locked. When an EXPIRED hold can be covered by available stock it takes that stock back; when any expired line cannot be covered, no line of the order is confirmed or moved, this event is written to the outbox in the same transaction and the OrderPaid is marked processed. Commerce cancels the order only while it is still PAID (reason STOCK_UNAVAILABLE, which buyers cannot send), so the OrderCancelled it publishes carries paidAmount and payment-service refunds it; #161 then releases any line still HELD. A duplicate event changes nothing; an order that is no longer PAID is logged and left for support.

### ShipmentCreated

- Topic: `nexus.shipment.events`.
- Publisher: [#202](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/202).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recognize/log creation; no order-state move; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `shipmentId` | Shipment UUID; one shipment per seller order. |
| `orderId` | Canonical paid seller order, not the group UUID. |
| `buyerId` | Buyer from the trusted shipping snapshot. |
| `warehouseId` | Dispatch warehouse UUID; no FK/read into inventory_db. |
| `carrier` | Persisted carrier namespace. |
| `trackingCode` | Verified carrier tracking code; null while awaiting tracking. |
| `status` | Internal creation outcome. |
| `shipmentVersion` | Committed local shipment version. |
| `createdAt` | Persisted shipment creation time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentCreatedPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 80
        },
        {
          "type": "null"
        }
      ]
    },
    "status": {
      "type": "string",
      "enum": [
        "CREATED",
        "PENDING_TRACKING"
      ]
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "createdAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "shipmentVersion",
    "createdAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "CREATED",
  "shipmentVersion": 1,
  "createdAt": "2026-11-20T10:00:00Z"
}
```

**Carrier unavailable example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": null,
  "status": "PENDING_TRACKING",
  "shipmentVersion": 1,
  "createdAt": "2026-11-20T10:00:00Z"
}
```

#202 creates one shipment from OrderPaid or authorized idempotent manual creation, using trusted buyer/warehouse/shipping snapshots. A temporary carrier outage may commit the same creation fact with PENDING_TRACKING and trackingCode=null; obtaining tracking later does not create a second shipment or repeat the same creation event. #250's older success-only wording is extended here to describe the #202 outage case and needs shipment-owner review. ShipmentPickedUp, accepted post-pickup progress and ShipmentDelivered drive #204 lifecycle moves, recovering a missed pickup callback without extra shipment facts. Persist the external request identity before retry/reconcile; DB uniqueness alone does not guarantee exactly-once carrier calls.

### ShipmentStatusUpdated

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recognize/log only; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

Exactly one event records an accepted intermediate transition for which no dedicated Shipment* event exists. It does not repeat ShipmentCreated. Both authorized status updates and overrides may skip forward steps after trackingCode assignment. Without trackingCode, manual updates may only repeat the current state or cancel, preserving pending booking retry; delivery retry may move DELIVERY_FAILED to OUT_FOR_DELIVERY. DELIVERED, CANCELLED and RETURNED are terminal, including for overrides. Each actual change commits status/version/history/outbox together; duplicate/no-op changes publish nothing.

The payload uses the same history/version/source/actor fields as ShipmentPickedUp. trackingCode is null when no carrier booking exists. changedAt is the accepted persisted transition time in UTC. reason contains the sanitized manual/cancellation reason, or null for an automated update without a reason.

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentStatusUpdatedPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 80
        },
        {
          "type": "null"
        }
      ]
    },
    "status": {
      "type": "string",
      "enum": [
        "CREATED",
        "PENDING_TRACKING",
        "PICKING",
        "PACKED",
        "READY_TO_SHIP",
        "OUT_FOR_DELIVERY",
        "RETURNING"
      ]
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 255
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "PICKING",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 2,
  "source": "MANUAL",
  "carrierEventId": null,
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": "Support confirmed shipment preparation"
}
```

### ShipmentCancelled

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recognize/log only; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

Records one accepted pre-pickup cancellation from a verified carrier update, an authorized manual update or OrderCancelled. Cancellation after pickup is rejected for HTTP updates; OrderCancelled instead completes unchanged and logs a WARN for Support. A pending shipment with no carrier booking can be cancelled locally. Manual and OrderCancelled cancellation must first cancel an existing booking through CarrierAdapter; a verified carrier cancellation records the carrier fact directly. Carrier failure rolls back the local effect and processed marker for retry. OrderCancelled reason is validated as nonblank and at most 500 characters before remote effects; its sanitized tracking/outbox reason is truncated to 255 characters. When no shipment exists, cancelled_shipment_orders records order_id, event_id, reason (up to 500 characters) and received_at to block late shipment creation/booking. No cancellation request/ACK event is required by R1.

The payload uses the same history/version/source/actor fields as ShipmentPickedUp. trackingCode is null when no carrier booking exists. changedAt is the accepted persisted transition time in UTC. reason contains the sanitized manual/cancellation reason, or null for an automated update without a reason.

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentCancelledPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 80
        },
        {
          "type": "null"
        }
      ]
    },
    "status": {
      "const": "CANCELLED"
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 255
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": null,
  "status": "CANCELLED",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 2,
  "source": "SYSTEM",
  "carrierEventId": null,
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": "Order cancelled before carrier pickup"
}
```

### ShipmentPickedUp

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): PAID -> SHIPPED; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `shipmentId` | Shipment UUID; one shipment per seller order. |
| `orderId` | Canonical paid seller order, not the group UUID. |
| `buyerId` | Buyer from the trusted shipping snapshot. |
| `warehouseId` | Dispatch warehouse UUID; no FK/read into inventory_db. |
| `carrier` | Persisted carrier namespace. |
| `trackingCode` | Carrier tracking code for this shipment. |
| `status` | Normalized internal target state; event name is not a new enum value. |
| `historyId` | History row written with this accepted transition. |
| `shipmentVersion` | Committed shipment version; detect stale transitions and correlate history. |
| `source` | Verified origin of the accepted change. |
| `carrierEventId` | Stable namespaced provider delivery identity when available. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted accepted business transition time; not arbitrary consumer receipt time. |
| `reason` | Sanitized manual/override reason when supplied. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentPickedUpPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 80
    },
    "status": {
      "const": "SHIPPED"
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 255
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "SHIPPED",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 1,
  "source": "CARRIER_WEBHOOK",
  "carrierEventId": "GHN:evt-123",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": null
}
```

#203 normalizes a verified carrier webhook, poller result or authorized manual update to SHIPPED. Commit status/version/history/outbox together; a duplicate or no-op produces no event. #204 uses only accepted forward transitions, through #116 and its own IdempotentConsumer. A late ShipmentCreated or lower shipmentVersion cannot reverse a completed order. Post-pickup ShipmentInTransit/ShipmentFailed/ShipmentReturned and ShipmentStatusUpdated OUT_FOR_DELIVERY/RETURNING confirm SHIPPED when pickup was missed; delivery can recover the missing SHIPPED prerequisite atomically. Early status updates and cancellation do not invent order states. Validate signatures before effects; store provider deduplication identities, and retain durable retry for missing prerequisites. A manual override needs permission and a nonempty reason even when the schema allows null for automated changes.

### ShipmentInTransit

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): confirm PAID -> SHIPPED if the pickup event was missed; no new order state is introduced; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `shipmentId` | Shipment UUID; one shipment per seller order. |
| `orderId` | Canonical paid seller order, not the group UUID. |
| `buyerId` | Buyer from the trusted shipping snapshot. |
| `warehouseId` | Dispatch warehouse UUID; no FK/read into inventory_db. |
| `carrier` | Persisted carrier namespace. |
| `trackingCode` | Carrier tracking code for this shipment. |
| `status` | Normalized internal target state; event name is not a new enum value. |
| `historyId` | History row written with this accepted transition. |
| `shipmentVersion` | Committed shipment version; detect stale transitions and correlate history. |
| `source` | Verified origin of the accepted change. |
| `carrierEventId` | Stable namespaced provider delivery identity when available. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted accepted business transition time; not arbitrary consumer receipt time. |
| `reason` | Sanitized manual/override reason when supplied. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentInTransitPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 80
    },
    "status": {
      "const": "IN_TRANSIT"
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 255
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "IN_TRANSIT",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 1,
  "source": "CARRIER_WEBHOOK",
  "carrierEventId": "GHN:evt-123",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": null
}
```

#203 normalizes a verified carrier webhook, poller result or authorized manual update to IN_TRANSIT. Commit status/version/history/outbox together; a duplicate or no-op produces no event. #204 uses only accepted forward transitions, through #116 and its own IdempotentConsumer. A late ShipmentCreated or lower shipmentVersion cannot reverse a completed order. Post-pickup ShipmentInTransit/ShipmentFailed/ShipmentReturned and ShipmentStatusUpdated OUT_FOR_DELIVERY/RETURNING confirm SHIPPED when pickup was missed; delivery can recover the missing SHIPPED prerequisite atomically. Early status updates and cancellation do not invent order states. Validate signatures before effects; store provider deduplication identities, and retain durable retry for missing prerequisites. A manual override needs permission and a nonempty reason even when the schema allows null for automated changes.

### ShipmentDelivered

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): recover SHIPPED if PAID, then DELIVERED -> COMPLETED and publish OrderCompleted once; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `shipmentId` | Shipment UUID; one shipment per seller order. |
| `orderId` | Canonical paid seller order, not the group UUID. |
| `buyerId` | Buyer from the trusted shipping snapshot. |
| `warehouseId` | Dispatch warehouse UUID; no FK/read into inventory_db. |
| `carrier` | Persisted carrier namespace. |
| `trackingCode` | Carrier tracking code for this shipment. |
| `status` | Normalized internal target state; event name is not a new enum value. |
| `historyId` | History row written with this accepted transition. |
| `shipmentVersion` | Committed shipment version; detect stale transitions and correlate history. |
| `source` | Verified origin of the accepted change. |
| `carrierEventId` | Stable namespaced provider delivery identity when available. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted accepted business transition time; not arbitrary consumer receipt time. |
| `reason` | Sanitized manual/override reason when supplied. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentDeliveredPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 80
    },
    "status": {
      "const": "DELIVERED"
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 255
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "DELIVERED",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 1,
  "source": "CARRIER_WEBHOOK",
  "carrierEventId": "GHN:evt-123",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": null
}
```

#203 normalizes a verified carrier webhook, poller result or authorized manual update to DELIVERED. Commit status/version/history/outbox together; a duplicate or no-op produces no event. #204 uses only accepted forward transitions, through #116 and its own IdempotentConsumer. A late ShipmentCreated or lower shipmentVersion cannot reverse a completed order. Post-pickup ShipmentInTransit/ShipmentFailed/ShipmentReturned and ShipmentStatusUpdated OUT_FOR_DELIVERY/RETURNING confirm SHIPPED when pickup was missed; delivery can recover the missing SHIPPED prerequisite atomically. Early status updates and cancellation do not invent order states. Validate signatures before effects; store provider deduplication identities, and retain durable retry for missing prerequisites. A manual override needs permission and a nonempty reason even when the schema allows null for automated changes.

### ShipmentFailed

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): confirm PAID -> SHIPPED if the pickup event was missed; no new order state is introduced; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `shipmentId` | Shipment UUID; one shipment per seller order. |
| `orderId` | Canonical paid seller order, not the group UUID. |
| `buyerId` | Buyer from the trusted shipping snapshot. |
| `warehouseId` | Dispatch warehouse UUID; no FK/read into inventory_db. |
| `carrier` | Persisted carrier namespace. |
| `trackingCode` | Carrier tracking code for this shipment. |
| `status` | Normalized internal target state; event name is not a new enum value. |
| `historyId` | History row written with this accepted transition. |
| `shipmentVersion` | Committed shipment version; detect stale transitions and correlate history. |
| `source` | Verified origin of the accepted change. |
| `carrierEventId` | Stable namespaced provider delivery identity when available. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted accepted business transition time; not arbitrary consumer receipt time. |
| `reason` | Sanitized failure reason; exclude full recipient contact details. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentFailedPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 80
    },
    "status": {
      "const": "DELIVERY_FAILED"
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 255
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "DELIVERY_FAILED",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 1,
  "source": "CARRIER_WEBHOOK",
  "carrierEventId": "GHN:evt-123",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": "Delivery recipient unavailable"
}
```

#203 normalizes a verified carrier webhook, poller result or authorized manual update to DELIVERY_FAILED. Commit status/version/history/outbox together; a duplicate or no-op produces no event. #204 uses only accepted forward transitions, through #116 and its own IdempotentConsumer. A late ShipmentCreated or lower shipmentVersion cannot reverse a completed order. Post-pickup ShipmentInTransit/ShipmentFailed/ShipmentReturned and ShipmentStatusUpdated OUT_FOR_DELIVERY/RETURNING confirm SHIPPED when pickup was missed; delivery can recover the missing SHIPPED prerequisite atomically. Early status updates and cancellation do not invent order states. Validate signatures before effects; store provider deduplication identities, and retain durable retry for missing prerequisites. A manual override needs permission and a nonempty reason even when the schema allows null for automated changes.

### ShipmentReturned

- Topic: `nexus.shipment.events`.
- Publisher: [#203](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/203).
- Consumer: commerce-service [#204](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/204): confirm PAID -> SHIPPED if the pickup event was missed; no new order state is introduced; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `shipmentId`; Kafka key: `orderId`.

| Payload field | Meaning |
| --- | --- |
| `shipmentId` | Shipment UUID; one shipment per seller order. |
| `orderId` | Canonical paid seller order, not the group UUID. |
| `buyerId` | Buyer from the trusted shipping snapshot. |
| `warehouseId` | Dispatch warehouse UUID; no FK/read into inventory_db. |
| `carrier` | Persisted carrier namespace. |
| `trackingCode` | Carrier tracking code for this shipment. |
| `status` | Normalized internal target state; event name is not a new enum value. |
| `historyId` | History row written with this accepted transition. |
| `shipmentVersion` | Committed shipment version; detect stale transitions and correlate history. |
| `source` | Verified origin of the accepted change. |
| `carrierEventId` | Stable namespaced provider delivery identity when available. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted accepted business transition time; not arbitrary consumer receipt time. |
| `reason` | Sanitized manual/override reason when supplied. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ShipmentReturnedPayload",
  "type": "object",
  "properties": {
    "shipmentId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "carrier": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 40
    },
    "trackingCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 80
    },
    "status": {
      "const": "RETURNED"
    },
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "shipmentVersion": {
      "type": "integer",
      "minimum": 0
    },
    "source": {
      "type": "string",
      "enum": [
        "SYSTEM",
        "CARRIER_WEBHOOK",
        "POLLER",
        "MANUAL"
      ]
    },
    "carrierEventId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 160
        },
        {
          "type": "null"
        }
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 255
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "shipmentId",
    "orderId",
    "buyerId",
    "warehouseId",
    "carrier",
    "trackingCode",
    "status",
    "historyId",
    "shipmentVersion",
    "source",
    "carrierEventId",
    "actorId",
    "changedAt",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "shipmentId": "00000000-0000-4000-8000-000000000040",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "carrier": "GHN",
  "trackingCode": "GHN-12345",
  "status": "RETURNED",
  "historyId": "00000000-0000-4000-8000-000000000041",
  "shipmentVersion": 1,
  "source": "CARRIER_WEBHOOK",
  "carrierEventId": "GHN:evt-123",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "reason": null
}
```

#203 normalizes a verified carrier webhook, poller result or authorized manual update to RETURNED. Commit status/version/history/outbox together; a duplicate or no-op produces no event. #204 uses only accepted forward transitions, through #116 and its own IdempotentConsumer. A late ShipmentCreated or lower shipmentVersion cannot reverse a completed order. Post-pickup ShipmentInTransit/ShipmentFailed/ShipmentReturned and ShipmentStatusUpdated OUT_FOR_DELIVERY/RETURNING confirm SHIPPED when pickup was missed; delivery can recover the missing SHIPPED prerequisite atomically. Early status updates and cancellation do not invent order states. Validate signatures before effects; store provider deduplication identities, and retain durable retry for missing prerequisites. A manual override needs permission and a nonempty reason even when the schema allows null for automated changes.

### NotificationCreated

- Topic: `none`.
- Publisher: [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log.
- Envelope aggregateId: `notificationId`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `notificationId` | New rendered in-app notification UUID. |
| `recipientId` | Recipient from the trusted source event. |
| `notificationType` | Persisted notification type. |
| `sourceEventId` | Original causal envelope eventId; resolve its event_logs.id separately for the notifications.event_id FK. |
| `createdAt` | Persisted notification creation time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "NotificationCreatedPayload",
  "type": "object",
  "properties": {
    "notificationId": {
      "type": "string",
      "format": "uuid"
    },
    "recipientId": {
      "type": "string",
      "format": "uuid"
    },
    "notificationType": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    },
    "sourceEventId": {
      "type": "string",
      "format": "uuid"
    },
    "createdAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "notificationId",
    "recipientId",
    "notificationType",
    "sourceEventId",
    "createdAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "notificationId": "00000000-0000-4000-8000-000000000070",
  "recipientId": "00000000-0000-4000-8000-000000000002",
  "notificationType": "ORDER_CREATED",
  "sourceEventId": "00000000-0000-4000-8000-000000000071",
  "createdAt": "2026-11-20T10:00:00Z"
}
```

#191 integrates this recording point with #107's notification creation; #107 remains owner of the original business consumer. Record NotificationCreated through the #192 in-process recorder in the same notification_db transaction as the notification. This is a local EventEnvelope, with no Kafka send/topic/outbox row requiring a fabricated topic. Never confuse the causal envelope ID with event_logs.id. Do not include the rendered body, full email address or recipient name, and do not turn these log facts into new notifications recursively.

### NotificationSent

- Topic: `none`.
- Publisher: [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log.
- Envelope aggregateId: `deliveryId`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `notificationId` | Notification being delivered. |
| `deliveryId` | Channel delivery row. |
| `attemptId` | Persisted delivery attempt UUID. |
| `recipientId` | Notification recipient identity, not an email address. |
| `channel` | Email channel delivered by #191; no new PUSH/SMS implementation is claimed. |
| `attemptNumber` | Actual attempt_number; runtime retry budget remains configurable. |
| `providerMessageId` | Non-sensitive provider message identity when returned. |
| `sentAt` | Persisted attempt completion time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "NotificationSentPayload",
  "type": "object",
  "properties": {
    "notificationId": {
      "type": "string",
      "format": "uuid"
    },
    "deliveryId": {
      "type": "string",
      "format": "uuid"
    },
    "attemptId": {
      "type": "string",
      "format": "uuid"
    },
    "recipientId": {
      "type": "string",
      "format": "uuid"
    },
    "channel": {
      "const": "EMAIL"
    },
    "attemptNumber": {
      "type": "integer",
      "minimum": 1
    },
    "providerMessageId": {
      "anyOf": [
        {
          "type": "string",
          "minLength": 1,
          "pattern": "\\S",
          "maxLength": 120
        },
        {
          "type": "null"
        }
      ]
    },
    "sentAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "notificationId",
    "deliveryId",
    "attemptId",
    "recipientId",
    "channel",
    "attemptNumber",
    "providerMessageId",
    "sentAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "notificationId": "00000000-0000-4000-8000-000000000070",
  "deliveryId": "00000000-0000-4000-8000-000000000072",
  "attemptId": "00000000-0000-4000-8000-000000000073",
  "recipientId": "00000000-0000-4000-8000-000000000002",
  "channel": "EMAIL",
  "attemptNumber": 1,
  "providerMessageId": "mailpit-123",
  "sentAt": "2026-11-20T10:00:00Z"
}
```

#191 records a SUCCEEDED attempt and delivery SENT with this local log fact in one notification_db transaction after provider success. A duplicate completion adds no second fact; eventId remains stable on recorder retry. SMTP is outside the SQL transaction, so reconcile provider identity before resending after a crash. Clear the retry schedule; do not log email body/address/provider credentials.

### NotificationFailed

- Topic: `none`.
- Publisher: [#191](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/191).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log.
- Envelope aggregateId: `deliveryId`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `notificationId` | Notification being delivered. |
| `deliveryId` | Channel delivery row. |
| `attemptId` | Persisted delivery attempt UUID. |
| `recipientId` | Notification recipient identity, not an email address. |
| `channel` | Email channel delivered by #191; no new PUSH/SMS implementation is claimed. |
| `attemptNumber` | Actual attempt_number; runtime retry budget remains configurable. |
| `errorCode` | Sanitized machine error code. |
| `retryable` | Provider error classification; does not alone imply remaining retry budget. |
| `deliveryStatus` | PENDING if retry scheduled, FAILED on permanent error or exhausted budget. |
| `nextAttemptAt` | Next durable retry time, or null if terminal. |
| `failedAt` | Persisted failure completion time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "NotificationFailedPayload",
  "type": "object",
  "properties": {
    "notificationId": {
      "type": "string",
      "format": "uuid"
    },
    "deliveryId": {
      "type": "string",
      "format": "uuid"
    },
    "attemptId": {
      "type": "string",
      "format": "uuid"
    },
    "recipientId": {
      "type": "string",
      "format": "uuid"
    },
    "channel": {
      "const": "EMAIL"
    },
    "attemptNumber": {
      "type": "integer",
      "minimum": 1
    },
    "errorCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    },
    "retryable": {
      "type": "boolean"
    },
    "deliveryStatus": {
      "type": "string",
      "enum": [
        "PENDING",
        "FAILED"
      ]
    },
    "nextAttemptAt": {
      "anyOf": [
        {
          "type": "string",
          "format": "date-time",
          "pattern": "Z$"
        },
        {
          "type": "null"
        }
      ]
    },
    "failedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "notificationId",
    "deliveryId",
    "attemptId",
    "recipientId",
    "channel",
    "attemptNumber",
    "errorCode",
    "retryable",
    "deliveryStatus",
    "nextAttemptAt",
    "failedAt"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "deliveryStatus": {
            "const": "PENDING"
          }
        }
      },
      "then": {
        "properties": {
          "retryable": {
            "const": true
          },
          "nextAttemptAt": {
            "type": "string",
            "format": "date-time",
            "pattern": "Z$"
          }
        }
      },
      "else": {
        "properties": {
          "nextAttemptAt": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "notificationId": "00000000-0000-4000-8000-000000000070",
  "deliveryId": "00000000-0000-4000-8000-000000000072",
  "attemptId": "00000000-0000-4000-8000-000000000073",
  "recipientId": "00000000-0000-4000-8000-000000000002",
  "channel": "EMAIL",
  "attemptNumber": 1,
  "errorCode": "SMTP_UNAVAILABLE",
  "retryable": true,
  "deliveryStatus": "PENDING",
  "nextAttemptAt": "2026-11-20T10:30:00Z",
  "failedAt": "2026-11-20T10:00:00Z"
}
```

**Retry budget exhausted example**

```json
{
  "notificationId": "00000000-0000-4000-8000-000000000070",
  "deliveryId": "00000000-0000-4000-8000-000000000072",
  "attemptId": "00000000-0000-4000-8000-000000000073",
  "recipientId": "00000000-0000-4000-8000-000000000002",
  "channel": "EMAIL",
  "attemptNumber": 3,
  "errorCode": "SMTP_UNAVAILABLE",
  "retryable": true,
  "deliveryStatus": "FAILED",
  "nextAttemptAt": null,
  "failedAt": "2026-11-20T10:00:00Z"
}
```

#191 records each completed FAILED attempt locally. If retryable and budget remains, deliveryStatus=PENDING and nextAttemptAt>failedAt; otherwise deliveryStatus=FAILED and nextAttemptAt=null. A terminal failure may still have retryable=true when the budget is exhausted. Do not represent a skipped preference as a failed send. attemptNumber is checked against the actual configured limit, not a fixed schema maximum.

### UserRated

- Topic: `nexus.rating.events`.
- Publisher: [#212](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/212).
- Consumer: rating-service [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213): update the rated user's effective rating inputs and score; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `ratingId`; Kafka key: `toUserId`.

| Payload field | Meaning |
| --- | --- |
| `ratingId` | Stable original rating UUID. |
| `orderId` | Completed canonical order from rating_eligibility. |
| `auctionId` | Auction context for an AUCTION order; not a second rating opportunity. |
| `fromUserId` | Authenticated rater, one permitted party of this order. |
| `toUserId` | The other party whose score is updated. |
| `score` | Original submitted rating, not the 0..100 reputation score. |
| `sourceVersion` | Initial immutable rating revision; corrections belong to the dispute workflow. |
| `actorId` | Same authenticated identity as fromUserId. |
| `ratedAt` | Original persisted rating time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "UserRatedPayload",
  "type": "object",
  "properties": {
    "ratingId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "auctionId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "fromUserId": {
      "type": "string",
      "format": "uuid"
    },
    "toUserId": {
      "type": "string",
      "format": "uuid"
    },
    "score": {
      "type": "integer",
      "minimum": 1,
      "maximum": 5
    },
    "sourceVersion": {
      "const": 0
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "ratedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "ratingId",
    "orderId",
    "auctionId",
    "fromUserId",
    "toUserId",
    "score",
    "sourceVersion",
    "actorId",
    "ratedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "ratingId": "00000000-0000-4000-8000-000000000050",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "auctionId": null,
  "fromUserId": "00000000-0000-4000-8000-000000000002",
  "toUserId": "00000000-0000-4000-8000-000000000004",
  "score": 5,
  "sourceVersion": 0,
  "actorId": "00000000-0000-4000-8000-000000000002",
  "ratedAt": "2026-11-20T10:00:00Z"
}
```

#212 checks COMPLETED eligibility locally, forbids self-rating and enforces one rating per permitted party/order. actorId=fromUserId and fromUserId != toUserId. Do not publish a full name, email or free-form review text for a consumer that only needs IDs/score. #213 deduplicates by ratingId/toUserId/sourceVersion as well as eventId and atomically updates inputs/history/outbox/processed marker. Masked display names remain an API concern; a second envelope must not add the rating again.

### ReputationScoreUpdated

- Topic: `nexus.rating.events`.
- Publisher: [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `userId`; Kafka key: `userId`.

| Payload field | Meaning |
| --- | --- |
| `historyId` | Immutable reputation_history row. |
| `userId` | Affected user, distinct from the executing actor. |
| `operationId` | Stable business operation identity retained across retries. |
| `sourceId` | Rating/order/auction source; null only for operations allowed by the history rules. |
| `sourceVersion` | Business source revision; required for rating/dispute inputs. |
| `changeType` | History change classification from #250. |
| `policyVersion` | Version of the policy actually applied. |
| `previousScore` | Stored score before this accepted change. |
| `newScore` | Stored score after calculation/clamping. |
| `delta` | Actual newScore - previousScore, including zero. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted history/change time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ReputationScoreUpdatedPayload",
  "type": "object",
  "properties": {
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "userId": {
      "type": "string",
      "format": "uuid"
    },
    "operationId": {
      "type": "string",
      "format": "uuid"
    },
    "sourceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "sourceVersion": {
      "anyOf": [
        {
          "type": "integer",
          "minimum": 0
        },
        {
          "type": "null"
        }
      ]
    },
    "changeType": {
      "type": "string",
      "enum": [
        "USER_RATED",
        "ORDER_COMPLETED",
        "AUCTION_OUTCOME",
        "AUCTION_PAYMENT_TIMEOUT",
        "ADMIN_ADJUSTMENT",
        "DISPUTE_ADJUSTMENT",
        "POLICY_RECALCULATED"
      ]
    },
    "policyVersion": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    },
    "previousScore": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    },
    "newScore": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    },
    "delta": {
      "type": "integer",
      "minimum": -100,
      "maximum": 100
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "historyId",
    "userId",
    "operationId",
    "sourceId",
    "sourceVersion",
    "changeType",
    "policyVersion",
    "previousScore",
    "newScore",
    "delta",
    "actorId",
    "changedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "historyId": "00000000-0000-4000-8000-000000000051",
  "userId": "00000000-0000-4000-8000-000000000004",
  "operationId": "00000000-0000-4000-8000-000000000052",
  "sourceId": "00000000-0000-4000-8000-000000000050",
  "sourceVersion": 0,
  "changeType": "USER_RATED",
  "policyVersion": "reputation-policy-1",
  "previousScore": 50,
  "newScore": 55,
  "delta": 5,
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z"
}
```

#213 commits aggregate/input counters, immutable history and outbox together. newScore=previousScore+delta and scores stay in 0..100. A zero delta is valid for counter/history-only changes. sourceId is required except permitted ADMIN_ADJUSTMENT/POLICY_RECALCULATED operations; rating/dispute sources require sourceVersion. Stable operation/source/revision prevents recounting even if eventId or policyVersion differs. This result event is logged; it must not recursively trigger another reputation calculation.

### ReputationPenaltyApplied

- Topic: `nexus.rating.events`.
- Publisher: [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `userId`; Kafka key: `userId`.

| Payload field | Meaning |
| --- | --- |
| `historyId` | Immutable reputation_history row. |
| `userId` | Winner receiving the late-payment penalty. |
| `operationId` | Stable business operation identity retained across retries. |
| `sourceId` | Auction causing the one late-payment penalty. |
| `sourceVersion` | Source auction revision when available. |
| `changeType` | Penalty source classification. |
| `policyVersion` | Version of the policy actually applied. |
| `previousScore` | Stored score before this accepted change. |
| `newScore` | Score after policy/clamping. |
| `delta` | Actual nonpositive delta, possibly zero at the floor. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Persisted history/change time. |
| `penaltyPoints` | Policy penalty applied once; default 10 is configurable, not a schema constant. |
| `reason` | Recorded penalty reason. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ReputationPenaltyAppliedPayload",
  "type": "object",
  "properties": {
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "userId": {
      "type": "string",
      "format": "uuid"
    },
    "operationId": {
      "type": "string",
      "format": "uuid"
    },
    "sourceId": {
      "type": "string",
      "format": "uuid"
    },
    "sourceVersion": {
      "anyOf": [
        {
          "type": "integer",
          "minimum": 0
        },
        {
          "type": "null"
        }
      ]
    },
    "changeType": {
      "const": "AUCTION_PAYMENT_TIMEOUT"
    },
    "policyVersion": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    },
    "previousScore": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    },
    "newScore": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    },
    "delta": {
      "type": "integer",
      "minimum": -100,
      "maximum": 0
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "penaltyPoints": {
      "type": "integer",
      "minimum": 1
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S"
    }
  },
  "required": [
    "historyId",
    "userId",
    "operationId",
    "sourceId",
    "sourceVersion",
    "changeType",
    "policyVersion",
    "previousScore",
    "newScore",
    "delta",
    "actorId",
    "changedAt",
    "penaltyPoints",
    "reason"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "historyId": "00000000-0000-4000-8000-000000000051",
  "userId": "00000000-0000-4000-8000-000000000002",
  "operationId": "00000000-0000-4000-8000-000000000052",
  "sourceId": "00000000-0000-4000-8000-000000000003",
  "sourceVersion": 1,
  "changeType": "AUCTION_PAYMENT_TIMEOUT",
  "policyVersion": "reputation-policy-1",
  "previousScore": 50,
  "newScore": 40,
  "delta": -10,
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z",
  "penaltyPoints": 10,
  "reason": "Auction payment deadline expired"
}
```

#213 emits only after a real policy penalty is committed for AuctionPaymentTimeout.winnerId. Record the configured penalty and actual clamped score delta; do not assume they are numerically equal at score zero. One auction/order outcome may penalize its winner once. The seller and losing bidders are not penalized. Same operation/source identity on replay is a no-op; this log event is not itself another penalty command.

### TrustLevelChanged

- Topic: `nexus.rating.events`.
- Publisher: [#213](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/213).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `userId`; Kafka key: `userId`.

| Payload field | Meaning |
| --- | --- |
| `historyId` | History explaining the accepted trust transition. |
| `userId` | User whose level changed. |
| `operationId` | Same stable business operation as the associated score update. |
| `previousLevel` | Previously stored trust level. |
| `newLevel` | New level under the policy actually used. |
| `score` | Post-change reputation score. |
| `policyVersion` | Applied thresholds/formula version. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `changedAt` | Committed history/change time. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "TrustLevelChangedPayload",
  "type": "object",
  "properties": {
    "historyId": {
      "type": "string",
      "format": "uuid"
    },
    "userId": {
      "type": "string",
      "format": "uuid"
    },
    "operationId": {
      "type": "string",
      "format": "uuid"
    },
    "previousLevel": {
      "type": "string",
      "enum": [
        "NEW",
        "BASIC",
        "TRUSTED",
        "TOP"
      ]
    },
    "newLevel": {
      "type": "string",
      "enum": [
        "NEW",
        "BASIC",
        "TRUSTED",
        "TOP"
      ]
    },
    "score": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    },
    "policyVersion": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "historyId",
    "userId",
    "operationId",
    "previousLevel",
    "newLevel",
    "score",
    "policyVersion",
    "actorId",
    "changedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "historyId": "00000000-0000-4000-8000-000000000051",
  "userId": "00000000-0000-4000-8000-000000000004",
  "operationId": "00000000-0000-4000-8000-000000000052",
  "previousLevel": "NEW",
  "newLevel": "BASIC",
  "score": 55,
  "policyVersion": "reputation-policy-1",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "changedAt": "2026-11-20T10:00:00Z"
}
```

#213 emits only when previousLevel != newLevel, in the transaction that updates the aggregate/history. Resolve TRUST_LEVEL_THRESHOLDS at runtime rather than hard-coding SQL/schema thresholds. The operation/history may also produce ReputationScoreUpdated with its own eventId; #192 stores both without treating them as two business score updates.

### SystemConfigUpdated

- Topic: `none`.
- Publisher: [#217](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/217).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): audit through the proposed durable internal handoff; delivery/owner review below.
- Envelope aggregateId: `configKey`; Kafka key: `none` (non-Kafka recording).
- Review status: Record shape specified; #217 owner and durable non-Kafka delivery to #192 require lane sign-off (ADR-009 open question).

| Payload field | Meaning |
| --- | --- |
| `configKey` | Validated SRS 2.6 key; never a secret/API-token key. |
| `serviceName` | Service owning the changed Config Server key. |
| `oldValue` | Previous typed configuration value; no credential material. |
| `newValue` | Accepted typed new value; consumer must preserve its JSON type. |
| `actorId` | Authenticated Admin performing the configuration change. |
| `changedAt` | Time the runtime configuration change was accepted. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "SystemConfigUpdatedPayload",
  "type": "object",
  "properties": {
    "configKey": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 100
    },
    "serviceName": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 50
    },
    "oldValue": {
      "type": [
        "string",
        "number",
        "boolean",
        "null"
      ]
    },
    "newValue": {
      "type": [
        "string",
        "number",
        "boolean",
        "null"
      ]
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "changedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "configKey",
    "serviceName",
    "oldValue",
    "newValue",
    "actorId",
    "changedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "configKey": "NOTIFICATION_RETRY_LIMIT",
  "serviceName": "notification-service",
  "oldValue": 3,
  "newValue": 4,
  "actorId": "00000000-0000-4000-8000-000000000060",
  "changedAt": "2026-11-20T10:00:00Z"
}
```

#217 requires old/new values and actor for #192 audit. Reject unknown keys/wrong types before changing configuration. Topic is none under ADR-009/#186, so do not assign a ninth topic or assume the recorder shares config-service's DB. Proposed delivery is an authenticated internal handoff to the #192 recorder with stable eventId and durable retry; the #217 service owner/route and durability boundary must be confirmed by both lanes. Do not acknowledge successful audit delivery until persisted, and do not claim a cross-service SQL transaction or an existing internal endpoint. This contract defines the record shape; the owning implementation supplies the durable handoff.

### SystemHealthCheckFailed

- Topic: `none`.
- Publisher: [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency.
- Envelope aggregateId: `environment:serviceName`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `serviceName` | Configured health target identity; never a credential-bearing connection URL. |
| `environment` | Deployment environment used in the shared Redis health key. |
| `previousStatus` | Previously confirmed shared target state; recovery follows a known DOWN state. |
| `status` | New confirmed shared state. |
| `healthVersion` | Monotonic target-state version held in shared Redis, used to identify one transition. |
| `checkedAt` | Most recent confirmed check time. |
| `lastFailureAt` | Confirmed last failure time. |
| `lastRecoveredAt` | Most recent recovery time; null if none is known. |
| `reasonCode` | Sanitized check failure code; no raw stacktrace/credentials. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "SystemHealthCheckFailedPayload",
  "type": "object",
  "properties": {
    "serviceName": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 50
    },
    "environment": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 32
    },
    "previousStatus": {
      "type": "string",
      "enum": [
        "UNKNOWN",
        "UP"
      ]
    },
    "status": {
      "const": "DOWN"
    },
    "healthVersion": {
      "type": "integer",
      "minimum": 0
    },
    "checkedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastFailureAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastRecoveredAt": {
      "anyOf": [
        {
          "type": "string",
          "format": "date-time",
          "pattern": "Z$"
        },
        {
          "type": "null"
        }
      ]
    },
    "reasonCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    }
  },
  "required": [
    "serviceName",
    "environment",
    "previousStatus",
    "status",
    "healthVersion",
    "checkedAt",
    "lastFailureAt",
    "lastRecoveredAt",
    "reasonCode"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "serviceName": "catalog-service",
  "environment": "local",
  "previousStatus": "UP",
  "status": "DOWN",
  "healthVersion": 1,
  "checkedAt": "2026-11-20T10:00:00Z",
  "lastFailureAt": "2026-11-20T10:00:00Z",
  "lastRecoveredAt": null,
  "reasonCode": "CONNECTION_FAILED"
}
```

#193 emits one local log fact for a confirmed shared-state transition after its retry policy, not on every failed poll or cache refresh. Health state/timestamps/version live in durable shared Redis per #250; no PostgreSQL health table is added. Include payment/inventory/shipment/rating and Keycloak among configured checks. The #192 recorder writes locally even while Kafka is down, deduplicating stable eventId/target-transition identity. Redis and event_logs are not one SQL transaction: retain durable retry intent until the transition log is recorded. Do not create a notification/system topic or reset lastFailureAt on recovery. For these local events, aggregateId is environment + ":" + target name; key is not applicable.

### SystemHealthCheckRecovered

- Topic: `none`.
- Publisher: [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency.
- Envelope aggregateId: `environment:serviceName`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `serviceName` | Configured health target identity; never a credential-bearing connection URL. |
| `environment` | Deployment environment used in the shared Redis health key. |
| `previousStatus` | Previously confirmed shared target state; recovery follows a known DOWN state. |
| `status` | New confirmed shared state. |
| `healthVersion` | Monotonic target-state version held in shared Redis, used to identify one transition. |
| `checkedAt` | Most recent confirmed check time. |
| `lastFailureAt` | Confirmed last failure time. |
| `lastRecoveredAt` | Time of this confirmed recovery. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "SystemHealthCheckRecoveredPayload",
  "type": "object",
  "properties": {
    "serviceName": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 50
    },
    "environment": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 32
    },
    "previousStatus": {
      "type": "string",
      "enum": [
        "DOWN"
      ]
    },
    "status": {
      "const": "UP"
    },
    "healthVersion": {
      "type": "integer",
      "minimum": 0
    },
    "checkedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastFailureAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastRecoveredAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "serviceName",
    "environment",
    "previousStatus",
    "status",
    "healthVersion",
    "checkedAt",
    "lastFailureAt",
    "lastRecoveredAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "serviceName": "catalog-service",
  "environment": "local",
  "previousStatus": "DOWN",
  "status": "UP",
  "healthVersion": 1,
  "checkedAt": "2026-11-20T10:00:00Z",
  "lastFailureAt": "2026-11-20T09:55:00Z",
  "lastRecoveredAt": "2026-11-20T10:00:00Z"
}
```

#193 emits one local log fact for a confirmed shared-state transition after its retry policy, not on every failed poll or cache refresh. Health state/timestamps/version live in durable shared Redis per #250; no PostgreSQL health table is added. Include payment/inventory/shipment/rating and Keycloak among configured checks. The #192 recorder writes locally even while Kafka is down, deduplicating stable eventId/target-transition identity. Redis and event_logs are not one SQL transaction: retain durable retry intent until the transition log is recorded. Do not create a notification/system topic or reset lastFailureAt on recovery. For these local events, aggregateId is environment + ":" + target name; key is not applicable.

### MessageBrokerUnavailable

- Topic: `none`.
- Publisher: [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency.
- Envelope aggregateId: `environment:brokerName`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `brokerName` | Configured health target identity; never a credential-bearing connection URL. |
| `environment` | Deployment environment used in the shared Redis health key. |
| `previousStatus` | Previously confirmed shared target state; recovery follows a known DOWN state. |
| `status` | New confirmed shared state. |
| `healthVersion` | Monotonic target-state version held in shared Redis, used to identify one transition. |
| `checkedAt` | Most recent confirmed check time. |
| `lastFailureAt` | Confirmed last failure time. |
| `lastRecoveredAt` | Most recent recovery time; null if none is known. |
| `reasonCode` | Sanitized check failure code; no raw stacktrace/credentials. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "MessageBrokerUnavailablePayload",
  "type": "object",
  "properties": {
    "brokerName": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 50
    },
    "environment": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 32
    },
    "previousStatus": {
      "type": "string",
      "enum": [
        "UNKNOWN",
        "UP"
      ]
    },
    "status": {
      "const": "DOWN"
    },
    "healthVersion": {
      "type": "integer",
      "minimum": 0
    },
    "checkedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastFailureAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastRecoveredAt": {
      "anyOf": [
        {
          "type": "string",
          "format": "date-time",
          "pattern": "Z$"
        },
        {
          "type": "null"
        }
      ]
    },
    "reasonCode": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 60
    }
  },
  "required": [
    "brokerName",
    "environment",
    "previousStatus",
    "status",
    "healthVersion",
    "checkedAt",
    "lastFailureAt",
    "lastRecoveredAt",
    "reasonCode"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "brokerName": "kafka",
  "environment": "local",
  "previousStatus": "UP",
  "status": "DOWN",
  "healthVersion": 1,
  "checkedAt": "2026-11-20T10:00:00Z",
  "lastFailureAt": "2026-11-20T10:00:00Z",
  "lastRecoveredAt": null,
  "reasonCode": "CONNECTION_FAILED"
}
```

#193 emits one local log fact for a confirmed shared-state transition after its retry policy, not on every failed poll or cache refresh. Health state/timestamps/version live in durable shared Redis per #250; no PostgreSQL health table is added. Include payment/inventory/shipment/rating and Keycloak among configured checks. The #192 recorder writes locally even while Kafka is down, deduplicating stable eventId/target-transition identity. Redis and event_logs are not one SQL transaction: retain durable retry intent until the transition log is recorded. Do not create a notification/system topic or reset lastFailureAt on recovery. For these local events, aggregateId is environment + ":" + target name; key is not applicable.

### MessageBrokerRecovered

- Topic: `none`.
- Publisher: [#193](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/193).
- Consumer: notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): record locally in event log; no Kafka dependency.
- Envelope aggregateId: `environment:brokerName`; Kafka key: `none` (non-Kafka recording).

| Payload field | Meaning |
| --- | --- |
| `brokerName` | Configured health target identity; never a credential-bearing connection URL. |
| `environment` | Deployment environment used in the shared Redis health key. |
| `previousStatus` | Previously confirmed shared target state; recovery follows a known DOWN state. |
| `status` | New confirmed shared state. |
| `healthVersion` | Monotonic target-state version held in shared Redis, used to identify one transition. |
| `checkedAt` | Most recent confirmed check time. |
| `lastFailureAt` | Confirmed last failure time. |
| `lastRecoveredAt` | Time of this confirmed recovery. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "MessageBrokerRecoveredPayload",
  "type": "object",
  "properties": {
    "brokerName": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 50
    },
    "environment": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 32
    },
    "previousStatus": {
      "type": "string",
      "enum": [
        "DOWN"
      ]
    },
    "status": {
      "const": "UP"
    },
    "healthVersion": {
      "type": "integer",
      "minimum": 0
    },
    "checkedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastFailureAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "lastRecoveredAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "brokerName",
    "environment",
    "previousStatus",
    "status",
    "healthVersion",
    "checkedAt",
    "lastFailureAt",
    "lastRecoveredAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "brokerName": "kafka",
  "environment": "local",
  "previousStatus": "DOWN",
  "status": "UP",
  "healthVersion": 1,
  "checkedAt": "2026-11-20T10:00:00Z",
  "lastFailureAt": "2026-11-20T09:55:00Z",
  "lastRecoveredAt": "2026-11-20T10:00:00Z"
}
```

#193 emits one local log fact for a confirmed shared-state transition after its retry policy, not on every failed poll or cache refresh. Health state/timestamps/version live in durable shared Redis per #250; no PostgreSQL health table is added. Include payment/inventory/shipment/rating and Keycloak among configured checks. The #192 recorder writes locally even while Kafka is down, deduplicating stable eventId/target-transition identity. Redis and event_logs are not one SQL transaction: retain durable retry intent until the transition log is recorded. Do not create a notification/system topic or reset lastFailureAt on recovery. For these local events, aggregateId is environment + ":" + target name; key is not applicable.

### PaymentUnreconciled

- Topic: `nexus.payment.events`.
- Publisher: [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157).
- Consumer: commerce-service [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157): set buyer-readable paymentNote for affected orders without moving their status; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `sessionId`; Kafka key: `orderGroupId`.

| Payload field | Meaning |
| --- | --- |
| `sessionId` | Matched immutable payment session; unknown memo creates no such event. |
| `orderGroupId` | Group whose transfer needs reconciliation. |
| `transactionId` | One matched UNRECONCILED ledger movement. |
| `providerTransactionId` | Verified provider movement identifier stored as text. |
| `buyerId` | Buyer from the matched session. |
| `amount` | Actual received amount once, without allocating a failed receipt to sellers. |
| `currency` | Actual verified receipt currency; preserve it even when it differs from the expected VND. |
| `affectedOrderIds` | Complete planned order IDs affected by this session exception. |
| `reasonCode` | Stable machine reason selected from verified payment/session state. |
| `reason` | Buyer-readable reason; exclude provider secrets and account details. |
| `receivedAt` | First verified server receipt time used for deadline comparison. |
| `providerOccurredAt` | Provider occurrence time when verified; null when not supplied. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "PaymentUnreconciledPayload",
  "type": "object",
  "properties": {
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "transactionId": {
      "type": "string",
      "format": "uuid"
    },
    "providerTransactionId": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 120
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "amount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$",
      "not": {
        "pattern": "^0+\\.00$"
      }
    },
    "currency": {
      "type": "string",
      "pattern": "^[A-Z]{3}$"
    },
    "affectedOrderIds": {
      "type": "array",
      "items": {
        "type": "string",
        "format": "uuid"
      },
      "minItems": 1,
      "uniqueItems": true
    },
    "reasonCode": {
      "type": "string",
      "enum": [
        "LATE_PAYMENT",
        "CANCELLED_ORDER_GROUP",
        "CANCELLED_ORDER",
        "AMOUNT_MISMATCH",
        "CURRENCY_MISMATCH",
        "ALREADY_PAID"
      ]
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 500
    },
    "receivedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "providerOccurredAt": {
      "anyOf": [
        {
          "type": "string",
          "format": "date-time",
          "pattern": "Z$"
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "sessionId",
    "orderGroupId",
    "transactionId",
    "providerTransactionId",
    "buyerId",
    "amount",
    "currency",
    "affectedOrderIds",
    "reasonCode",
    "reason",
    "receivedAt",
    "providerOccurredAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "sessionId": "00000000-0000-4000-8000-000000000021",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "transactionId": "00000000-0000-4000-8000-000000000023",
  "providerTransactionId": "SEPAY-998877",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "amount": "350000.50",
  "currency": "VND",
  "affectedOrderIds": [
    "00000000-0000-4000-8000-000000000001",
    "00000000-0000-4000-8000-000000000011"
  ],
  "reasonCode": "LATE_PAYMENT",
  "reason": "Payment arrived after the payment deadline.",
  "receivedAt": "2026-11-20T10:30:00Z",
  "providerOccurredAt": null
}
```

#157 and the newer #250 exception rules write the matched UNRECONCILED transaction/outbox atomically. No CheckoutPaymentConfirmed, PaymentConfirmed or OrderPaid is produced for this movement. Keep prior successful receipts unchanged, including a distinct second transfer for an already-paid session. Same provider ID/content is a no-op; different content is a reconciliation conflict. Unknown memo returns 200/WARN without inventing a session or ledger/event. Commerce updates only the orders in its verified group membership; the group amount is not credited to each order. Wrong/late amounts are recorded, with no automatic partial-payment funding/resolution in R1.

### CheckoutPaymentRequested

- Topic: `nexus.commerce.events`.
- Publisher: [#118](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/118).
- Consumer: payment-service [#121](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/121), [#240](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/240): create the one immutable session and all seller instructions atomically; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `orderGroupId`; Kafka key: `orderGroupId`.

| Payload field | Meaning |
| --- | --- |
| `orderGroupId` | Stored purchase group, not a new checkout entity. |
| `buyerId` | Verified group buyer. |
| `currency` | Currency of this persisted snapshot; R1 uses VND. |
| `totalAmount` | Exact positive sum of finalized seller-order amounts. |
| `dueAt` | Group deadline, no later than any child paymentDueAt. |
| `occurredAt` | Manifest creation time; required by #118, equal to the envelope occurredAt. |
| `orderCount` | Complete expected child count, checked against stored group membership and orders length. |
| `orders` | Full trusted manifest: one unique order per seller, each amount and deadline/version from the stored order. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CheckoutPaymentRequestedPayload",
  "type": "object",
  "properties": {
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "currency": {
      "const": "VND"
    },
    "totalAmount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$",
      "not": {
        "pattern": "^0+\\.00$"
      }
    },
    "dueAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "occurredAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "orderCount": {
      "type": "integer",
      "minimum": 1
    },
    "orders": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "orderId": {
            "type": "string",
            "format": "uuid"
          },
          "sellerId": {
            "type": "string",
            "format": "uuid"
          },
          "amount": {
            "type": "string",
            "pattern": "^\\d+\\.\\d{2}$"
          },
          "paymentDueAt": {
            "type": "string",
            "format": "date-time",
            "pattern": "Z$"
          },
          "orderVersion": {
            "type": "integer",
            "minimum": 0
          }
        },
        "required": [
          "orderId",
          "sellerId",
          "amount",
          "paymentDueAt",
          "orderVersion"
        ],
        "additionalProperties": true
      },
      "minItems": 1
    }
  },
  "required": [
    "orderGroupId",
    "buyerId",
    "currency",
    "totalAmount",
    "dueAt",
    "occurredAt",
    "orderCount",
    "orders"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "currency": "VND",
  "totalAmount": "350000.50",
  "dueAt": "2026-11-20T10:30:00Z",
  "occurredAt": "2026-11-20T10:00:00Z",
  "orderCount": 2,
  "orders": [
    {
      "orderId": "00000000-0000-4000-8000-000000000001",
      "sellerId": "00000000-0000-4000-8000-000000000004",
      "amount": "200000.50",
      "paymentDueAt": "2026-11-20T10:30:00Z",
      "orderVersion": 0
    },
    {
      "orderId": "00000000-0000-4000-8000-000000000011",
      "sellerId": "00000000-0000-4000-8000-000000000012",
      "amount": "150000.00",
      "paymentDueAt": "2026-11-20T10:30:00Z",
      "orderVersion": 0
    }
  ]
}
```

#118 writes this complete manifest, one OrderCreated per seller order, the group/orders/items and cart change in the initial commerce transaction. Validate exact membership, unique orderId/sellerId, orderCount and Decimal sum, buyer/currency and deadline. #121 creates the session and every instruction/processed marker in one payment transaction; one OrderCreated cannot create a partial QR. Freeze the plan/memo/total: no replacement session, plan revision or unpaid-child repricing. Missing prerequisites use durable retry. This payload deliberately repeats occurredAt because the current #118 manifest requires it; traceId/eventId remain only in the envelope. This is an additional contract outside the 64-name SRS table and the 32-row expansion set.

The publisher reads `dueAt` and each `paymentDueAt` from the refreshed persisted group/order snapshot, without computing another timeout. `EventEnvelopeFactory.createWithTimestamp` supplies one timestamp for both manifest and envelope `occurredAt`. The current checkout inventory adapter returns only acceptance/refusal; #138 must persist its reservation `expiresAt` into those deadline columns when the group reservation API is integrated. This publisher does not fabricate that reservation expiry.

### CheckoutPaymentConfirmed

- Topic: `nexus.payment.events`.
- Publisher: [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156).
- Consumer: commerce-service [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156), [#116](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/116): validate the whole group and commit valid child PAID/history/OrderPaid atomically; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `sessionId`; Kafka key: `orderGroupId`.
- Review status: Authoritative R1 group-payment contract confirmed by MR !82 review under #250 (one immutable session per order group). PaymentConfirmed is deferred to R2, not an alternative R1 confirmation.

| Payload field | Meaning |
| --- | --- |
| `sessionId` | Unique authoritative session for the group. |
| `orderGroupId` | Stored order group whose complete plan was funded. |
| `transactionId` | One NORMAL/SUCCESS provider movement funding every listed share. |
| `buyerId` | Verified session buyer. |
| `currency` | Currency of this persisted snapshot; R1 uses VND. |
| `totalAmount` | Whole receipt/session total, equal to all instruction shares. |
| `paidAt` | Verified accepted session funding time. |
| `sessionVersion` | Payment producer-local transition version; never commerce group.version. |
| `instructions` | Complete funded plan; instructionId/orderId are unique and membership/amounts must match stored commerce orders. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CheckoutPaymentConfirmedPayload",
  "type": "object",
  "properties": {
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "transactionId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "currency": {
      "const": "VND"
    },
    "totalAmount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$",
      "not": {
        "pattern": "^0+\\.00$"
      }
    },
    "paidAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "sessionVersion": {
      "type": "integer",
      "minimum": 0
    },
    "instructions": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "instructionId": {
            "type": "string",
            "format": "uuid"
          },
          "orderId": {
            "type": "string",
            "format": "uuid"
          },
          "amount": {
            "type": "string",
            "pattern": "^\\d+\\.\\d{2}$"
          }
        },
        "required": [
          "instructionId",
          "orderId",
          "amount"
        ],
        "additionalProperties": true
      },
      "minItems": 1
    }
  },
  "required": [
    "sessionId",
    "orderGroupId",
    "transactionId",
    "buyerId",
    "currency",
    "totalAmount",
    "paidAt",
    "sessionVersion",
    "instructions"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "sessionId": "00000000-0000-4000-8000-000000000021",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "transactionId": "00000000-0000-4000-8000-000000000023",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "currency": "VND",
  "totalAmount": "350000.50",
  "paidAt": "2026-11-20T10:00:00Z",
  "sessionVersion": 1,
  "instructions": [
    {
      "instructionId": "00000000-0000-4000-8000-000000000022",
      "orderId": "00000000-0000-4000-8000-000000000001",
      "amount": "200000.50"
    },
    {
      "instructionId": "00000000-0000-4000-8000-000000000024",
      "orderId": "00000000-0000-4000-8000-000000000011",
      "amount": "150000.00"
    }
  ]
}
```

#250/#240 require one accepted movement, funding all instructions and this single outbox event in one payment transaction. Commerce validates buyer/currency/session/group relationship, exact child set/amounts/total; deduplicates; locks group/children deterministically; commits group PAID/paid_at, eligible PENDING -> PAID children/history, individual OrderPaid rows and processed marker in one commerce transaction. Late confirmation never resurrects CANCELED group/children or emits OrderPaid for them. Preserve successful financial evidence and compensate cancelled funded children via #247. No subset payment or repeated group receipt per seller. Implement #156 against this group contract; its older per-order PaymentConfirmed wording is superseded for R1 by MR !82 review.

### OrderGroupCancelled

- Topic: `nexus.commerce.events`.
- Publisher: [#189](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/189), [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159).
- Consumer: payment-service [#121](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/121), [#157](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/157), [#247](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/247): close the immutable unpaid session or compensate funded cancelled children; notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): event log.
- Envelope aggregateId: `orderGroupId`; Kafka key: `orderGroupId`.

| Payload field | Meaning |
| --- | --- |
| `orderGroupId` | Whole unpaid group cancelled/expired by commerce. |
| `buyerId` | Stored group buyer. |
| `groupVersion` | Committed commerce group.version; distinct from sessionVersion. |
| `reason` | Stable expiry code or validated cancellation reason. |
| `actorId` | Authenticated action actor UUID, or the fixed system UUID for a job/consumer; never a viewer-supplied actor. |
| `cancelledAt` | Committed whole-group cancellation time. |
| `orders` | Complete affected seller set with committed order versions; matching per-order OrderCancelled events are also written. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderGroupCancelledPayload",
  "type": "object",
  "properties": {
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "groupVersion": {
      "type": "integer",
      "minimum": 0
    },
    "reason": {
      "type": "string",
      "minLength": 1,
      "pattern": "\\S",
      "maxLength": 500
    },
    "actorId": {
      "type": "string",
      "format": "uuid"
    },
    "cancelledAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "orders": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "orderId": {
            "type": "string",
            "format": "uuid"
          },
          "orderVersion": {
            "type": "integer",
            "minimum": 0
          }
        },
        "required": [
          "orderId",
          "orderVersion"
        ],
        "additionalProperties": true
      },
      "minItems": 1
    }
  },
  "required": [
    "orderGroupId",
    "buyerId",
    "groupVersion",
    "reason",
    "actorId",
    "cancelledAt",
    "orders"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "groupVersion": 1,
  "reason": "PAYMENT_DEADLINE_EXPIRED",
  "actorId": "00000000-0000-0000-0000-000000000000",
  "cancelledAt": "2026-11-20T10:00:00Z",
  "orders": [
    {
      "orderId": "00000000-0000-4000-8000-000000000001",
      "orderVersion": 2
    },
    {
      "orderId": "00000000-0000-4000-8000-000000000011",
      "orderVersion": 2
    }
  ]
}
```

#113/#250 require group CANCELED, every still-PENDING child CANCELED/history, this group event and individual OrderCancelled events in one commerce transaction. #189 owns manual cancellation; #159 owns expiry and must adopt the group boundary. Payment serializes cancellation with webhook funding under session/instruction locks; missing manifest/session is durable retry, not a processed no-op. If funding already won, keep PAID/receipt evidence and create at most the one refund per cancelled funded instruction. No cancellation ACK wait, unpaid-child fence or replacement QR is introduced. Paid child cancellation follows the R1 pre-pickup rule in integration flow 4; no handover request/ACK is introduced.

Checkout #138 uses GHN v3 provinceCode/wardCode. districtCode is optional for historical messages; new orders do not invent a district. Parcel is assembled from seller-owned Catalog shippingParcel measurements and retained in the order snapshot.

## Deferred R2 payload reference

This retained #186 schema/example is outside R1 publication and consumption scope. The other 21 R2 names remain inventory-only entries without payload contracts.

### PaymentConfirmed

- Topic: `nexus.payment.events`.
- Publisher: [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156): historical #186 reference; R2 only, no R1 publication.
- Consumer: commerce-service [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156), notification-service [#192](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/192): historical #186 references; R2 only, no R1 handler.
- Envelope aggregateId: `sessionId`; Kafka key: `orderGroupId`.
- Scope: R2; no R1 publication or payment-state handler.
- Review status: Deferred by MR !82 review. Schema/example and historical #186 references are retained for traceability only; CheckoutPaymentConfirmed is authoritative for R1 group payment.

| Payload field | Meaning |
| --- | --- |
| `sessionId` | One session per order group. |
| `orderGroupId` | Group owning the accepted receipt. |
| `transactionId` | One NORMAL/SUCCESS incoming ledger record; not one copy per seller. |
| `instructionId` | The funded seller-order instruction. |
| `orderId` | Order owning this funded share. |
| `buyerId` | Buyer from the verified plan. |
| `amount` | This instruction's share, not the full bank receipt. |
| `currency` | VND in this retained reference; future R2 scope requires review. |
| `paidAt` | Verified accepted funding time. |
| `sessionVersion` | Producer-local committed session version. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "PaymentConfirmedPayload",
  "type": "object",
  "properties": {
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderGroupId": {
      "type": "string",
      "format": "uuid"
    },
    "transactionId": {
      "type": "string",
      "format": "uuid"
    },
    "instructionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "amount": {
      "type": "string",
      "pattern": "^\\d+\\.\\d{2}$"
    },
    "currency": {
      "const": "VND"
    },
    "paidAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "sessionVersion": {
      "type": "integer",
      "minimum": 0
    }
  },
  "required": [
    "sessionId",
    "orderGroupId",
    "transactionId",
    "instructionId",
    "orderId",
    "buyerId",
    "amount",
    "currency",
    "paidAt",
    "sessionVersion"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "sessionId": "00000000-0000-4000-8000-000000000021",
  "orderGroupId": "00000000-0000-4000-8000-000000000020",
  "transactionId": "00000000-0000-4000-8000-000000000023",
  "instructionId": "00000000-0000-4000-8000-000000000022",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "amount": "200000.50",
  "currency": "VND",
  "paidAt": "2026-11-20T10:00:00Z",
  "sessionVersion": 1
}
```

#186/#156 originally named this event. MR !82 review resolves the R1 path in favor of #250/#240/#118/#121: one CheckoutPaymentConfirmed containing all instructions authorizes the group PAID transition and per-order OrderPaid. PaymentConfirmed is R2, with no R1 publication or payment-state handler, including compatibility output. Do not apply it to pay a subset, generate a QR, emit duplicate OrderPaid, or clone the receipt per seller. This retained schema/example satisfies #186 traceability only; any future R2 publication or consumption requires a separate contract review.

## Review and implementation handoff

MR !82 review resolved the payment-event choice: CheckoutPaymentConfirmed is authoritative for R1, and PaymentConfirmed is R2. #156 implementation follows the one-session-per-group #250 contract; no compatibility publication or second payment-state handler is part of R1. This decision does not imply both backend lanes have approved the merge request.

The file is ready for contract review, with these remaining decisions explicitly visible rather than marked approved:

| Decision / owner | Required resolution |
| --- | --- |
| #217 / #192, both backend lanes | Confirm config write owner/route (ADR-009 leaves it open) and durable authenticated non-Kafka audit handoff. `none` cannot mean sending Kafka to a literal topic named none or using a nonexistent in-process shared DB. |
| #202 / #203 / #241 | Approve trusted OrderPaid shippingInfo/warehouse and status mappings, preparation of missing parcel/pickup details, and ShipmentCreated's PENDING_TRACKING creation case. Historical messages need coordinated backfill/replay; never fake carrier measurements/locality codes. |
| #189 / #203 / #250 | Apply the R1 rule in integration flow 4: a PAID child can be cancelled until ShipmentPickedUp without a handover request/ACK. Keep this distinct from whole-group prepayment cancellation; already-picked-up shipments remain unchanged with a Support WARN. |
| #209 / #197 / #216 | The create precheck returns 409 for zero stock. AuctionStockRejected handles stock lost before the hold. Catalog reconciles the product flag on auction creation, end and cancellation by auction ID. |
| #141 / #171 / #172 | Review the #139 order-line mapping and proposed image/quantity policy; implement the singleton group without another stock reservation. Defer the payment manifest until shipping confirmation. Record both sides' actual !150 approvals. |

The #186 table's consumers are all named here, including explicit filtering where #204 consumes a topic but only two shipment types move order state. New logging subscriptions in #192 replace the old six publisher-only classifications; they do not invent unrelated business handlers.

Runtime implementation checks remain with their owning issues: business/outbox rollback and stable relay identity/per-aggregate order (#104/#106/#118); duplicate/concurrent consumer effects and marker rollback (#105); one canonical auction order after replay/crash (#172/#174); cancellation/expiry stock races (#159/#161/#175); and traced scheduler contexts (#102/#145/#159/#175). This document revision does not claim those integration checks were run.

### Document validation

The execution evidence below is the historical 2026-10-28 baseline. It predates the two additive #203 contracts and is not evidence that the current 71-name catalogue or shipment runtime has been revalidated.

Recorded local validation on 2026-10-28 using Python 3.12 and jsonschema 4.10.3 with Draft 7, UUID checking and explicit standard-library UTC timestamp/URI checks:

```text
PASS inventory: 64 SRS names (43 R1 / 21 R2), 5 additions (4 R1 / 1 R2); 69 names (47 R1 / 22 R2)
PASS scope: 32 current #186 R1 contracts, 2 additional coordinated R1 contracts, 1 retained R2 schema
PASS JSON: 102 blocks; 48 Draft 7 schemas; 52 payload and 2 envelope examples
PASS checks: 433 required-field negatives; 341 UUID/UTC/URI negatives; 48 additive-field cases; 3 exact-decimal group plans
PASS money: 13 string fields; 174 rejected and 72 accepted money cases; exact two-decimal pattern
PASS topic/publisher/consumer table-to-section mappings and local anchors
```

The local checks read this Markdown directly, parsed every JSON block with Decimal arithmetic, checked Draft 7 schemas and format assertions, validated every payload/envelope example, and verified the 64 SRS names plus five additions, 47 R1 payload sections and 22 R2 inventory entries. All 32 names in the current #186 table have topic/publisher/consumer mappings. The retained PaymentConfirmed schema is counted among the 48 documented schemas, not among the R1 contracts. Negative cases covered missing required fields, malformed UUIDs/timestamps/URIs, numeric money and strings without exactly two fractional digits; positive money cases and rejection of zero for positive-only amounts were also checked. Group-plan totals and unique order membership were checked using exact Decimal arithmetic. Table-to-section topic/issue references and local anchors matched. These results do not claim DB/Kafka/provider integration or deployment verification.

### Acceptance evidence for this revision

- [x] All 32 names in #186 have a payload schema, example, named publishing issue and consuming issue.
- [x] Original 64 SRS names retained; promoted names and the five additional names have consistent tables and counts.
- [x] CheckoutPaymentConfirmed is included in the current 32-event #186 expansion; coordinated CheckoutPaymentRequested/OrderGroupCancelled contracts and the retained PaymentConfirmed R2 reference are counted separately.
- [x] Current payment/shipment/rating/inventory topics and owners are used; non-Kafka events explicitly use none.
- [x] InventoryReservationExpired retains its canonical spelling.
- [x] MR !82 payment-event decision applied: CheckoutPaymentConfirmed is authoritative for R1; PaymentConfirmed is deferred to R2 with no R1 compatibility publication.
- [ ] Owners resolve the other decisions listed in the handoff table.
- [ ] Both backend lanes approve the merge request; record the actual GitLab approvals before checking this item.

These document/schema checks provide evidence for the examples and inventory. They do not complete the unchecked review decisions or assert that any Java publisher/consumer, carrier call, refund transfer or migration is implemented.
