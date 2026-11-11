# Order completion and HTML receipt — #204

Commerce consumes accepted shipment transitions from `nexus.shipment.events`:
`ShipmentPickedUp` moves `PAID -> SHIPPED`; `ShipmentDelivered` moves
`SHIPPED -> DELIVERED -> COMPLETED`. The delivered/completed history rows,
processed marker and one `OrderCompleted` outbox row commit together. The event
includes the child order's HTML receipt for notification-service's #191 email
delivery, omitting the delivery recipient's name, phone and address. Those contact
details remain in the owner's authenticated invoice response. No payment-group
total is copied into each seller receipt.

## Local prerequisites

Run commands from `be-project-nexus` in PowerShell. Use a disposable local
seller-order fixture already `PAID`, with items and its buyer/seller/group
snapshots, plus a separate `PENDING` order owned by the same buyer. Record the
initial history count and version of the paid order. Use fresh orders and event
IDs for each independent run; replay checks below deliberately retain IDs.

Start the configured local infrastructure with the existing local `.env`:

```powershell
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml up -d postgres kafka kafka-init mailpit
```

Run Config Server, Discovery, Gateway, commerce-service and notification-service
from this branch. Apply migrations normally, including commerce V9 and notification V7; keep the
commerce outbox relay enabled (default). Host Kafka defaults to `localhost:29092`;
the console tools inside `nexus-kafka` use `kafka:9092`. Mailpit SMTP defaults to
port 1025 and its UI to port 8025; Compose uses the configured `MAILPIT_*_PORT`
values. Set `EMAIL_NOTIFICATION_ENABLED=true` for notification-service and supply
its existing `KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET`. The Keycloak
notification-service client needs its configured recipient lookup access, and
the buyer account must have an email address. No credentials belong in evidence.

Obtain two JWTs with `ORDER.VIEW`: the buyer's token and another user's token.
Use `NOTIFICATION.VIEW` on the buyer token when checking notification deliveries.
The HTTP receipt is owner-only, including when the other caller has ADMIN.

## Publish accepted shipment facts

Use the seller-order UUID as the Kafka key, and the shipment UUID as the envelope
`aggregateId`. These IDs serve different purposes. Fill the existing fixture
IDs below; the sample UUIDs are placeholders, not seeded records. All fourteen
shipment payload fields from [the event contract](../api/events.md#shipmentpickedup)
are supplied. A manual source requires a nonblank reason; this fixture uses
`POLLER` for an accepted automated transition.

```powershell
$taskOrderId = '20400000-0000-4000-8000-000000000001'
$taskBuyerId = '20400000-0000-4000-8000-000000000002'
$taskShipmentId = '20400000-0000-4000-8000-000000000003'
$taskWarehouseId = '20400000-0000-4000-8000-000000000004'
$taskChangedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$taskPickup = [ordered]@{
    eventId = [guid]::NewGuid().ToString()
    eventType = 'ShipmentPickedUp'
    aggregateId = $taskShipmentId
    occurredAt = $taskChangedAt
    traceId = 'manual-order204'
    payload = [ordered]@{
        shipmentId = $taskShipmentId
        orderId = $taskOrderId
        buyerId = $taskBuyerId
        warehouseId = $taskWarehouseId
        carrier = 'GHN'
        trackingCode = 'GHN-ORDER204'
        status = 'SHIPPED'
        historyId = [guid]::NewGuid().ToString()
        shipmentVersion = 1
        source = 'POLLER'
        carrierEventId = 'GHN:order204:pickup'
        actorId = '00000000-0000-0000-0000-000000000000'
        changedAt = $taskChangedAt
        reason = $null
    }
}
$taskPickupLine = $taskOrderId + '|' + ($taskPickup | ConvertTo-Json -Depth 8 -Compress)
$taskPickupLine | docker exec -i nexus-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic nexus.shipment.events --property parse.key=true --property 'key.separator=|'
```

Wait until `GET /api/v1/orders/{id}` reports `SHIPPED`; history must have exactly
one additional `PAID -> SHIPPED` row. Publish the exact `$taskPickupLine` again:
status, history count and version must remain unchanged.

Create a new accepted delivery fact for the same order/shipment, with its own
event/history/provider IDs and the next shipment version:

```powershell
$taskDelivery = $taskPickup | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$taskDelivery.eventId = [guid]::NewGuid().ToString()
$taskDelivery.eventType = 'ShipmentDelivered'
$taskDelivery.occurredAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$taskDelivery.payload.status = 'DELIVERED'
$taskDelivery.payload.historyId = [guid]::NewGuid().ToString()
$taskDelivery.payload.shipmentVersion = 2
$taskDelivery.payload.carrierEventId = 'GHN:order204:delivered'
$taskDelivery.payload.changedAt = $taskDelivery.occurredAt
$taskDeliveryLine = $taskOrderId + '|' + ($taskDelivery | ConvertTo-Json -Depth 8 -Compress)
$taskDeliveryLine | docker exec -i nexus-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic nexus.shipment.events --property parse.key=true --property 'key.separator=|'
```

Expect `COMPLETED`, with two more history rows (`SHIPPED -> DELIVERED` and
`DELIVERED -> COMPLETED`), so three new rows from the original PAID baseline.
There must be one `OrderCompleted` outbox row for this order. Replay the exact
delivery line and then the pickup line: neither creates history or another
completion outbox row. A late pickup cannot move the order backwards.

Read commerce's own tables through its normal development DB connection:

```sql
SELECT id, status, version FROM orders WHERE id = '<orderId>';
SELECT previous_status, new_status, changed_by, reason, created_at
FROM order_status_history WHERE order_id = '<orderId>' ORDER BY created_at, id;
SELECT id, event_type, topic, aggregate_id, published_at, payload
FROM outbox WHERE aggregate_id = '<orderId>' AND event_type = 'OrderCompleted';
```

The outbox envelope has key/aggregate `orderId`; its payload contains the actual
buyer/seller/group, committed order version, persisted commerce completion time,
`orderNumber` and `receiptHtml`. The envelope ID remains stable through relay
retries. One persisted completion/outbox fact is the deduplication criterion;
Kafka transport redelivery is allowed. Events received before the order,
payment or pickup prerequisite are stored in commerce's
`pending_order_shipment_events` table with the processed marker, so Kafka can
continue reading the partition. The shipment retry job applies the stored event
once prerequisites exist, then removes it in the same transaction as history and
outbox. `ORDER_SHIPMENT_RETRY_INTERVAL` defaults to `5s` in Config Server.
For an ordering check, publish delivery before pickup: no transition should occur
until pickup arrives; the retry job must then complete the order without another
delivery publication. A service restart must preserve the pending event.

## Receipt access and email

Set `$taskOwnerToken` and `$taskOtherToken` locally, then inspect status, headers
and body, for example:

```powershell
curl.exe -i -H "Authorization: Bearer $taskOwnerToken" -H 'Accept: text/html' "http://localhost:8080/api/v1/orders/$taskOrderId/invoice"
curl.exe -i -H "Authorization: Bearer $taskOtherToken" -H 'Accept: text/html' "http://localhost:8080/api/v1/orders/$taskOrderId/invoice"
```

| Request | Expected result |
| --- | --- |
| Completed order, buyer owner + ORDER.VIEW | 200 `text/html;charset=UTF-8`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; receipt contains stored item/pricing snapshots and totals; no JSON envelope |
| Same completed order, another user + ORDER.VIEW | 403 `application/problem+json`, `COMMERCE_FORBIDDEN` |
| Own PENDING order + ORDER.VIEW | 409 Problem Details, `COMMERCE_ORDER_NOT_COMPLETED` |
| Another user's PENDING order + ORDER.VIEW | 403 `COMMERCE_FORBIDDEN`, checked before completion |
| Unknown order UUID + ORDER.VIEW | 404 `COMMERCE_ORDER_NOT_FOUND` |
| Missing token / missing ORDER.VIEW | 401 / 403 Problem Details at Gateway; never reaches the service |

Open `http://localhost:8025` (or the configured Mailpit UI port). The buyer
receives an order-completed email with the HTML receipt's items and totals, without
the "Delivered to" contact section. The owner-only invoice retains that section.
Product text
containing `<`, `>`, `&` or quotes must remain text, without executing markup.
`GET /api/v1/notifications` identifies the buyer's `ORDER_COMPLETED` notification;
`GET /api/v1/notifications/{id}/deliveries` shows its single EMAIL delivery and
attempt history. The stored HTML snapshot is reused on asynchronous retries;
ordinary in-app notification content remains plain text.

For retry verification, use a fresh paid fixture and temporarily stop the local
Mailpit service before publishing its pickup/delivery. The order must still
complete, with an EMAIL delivery queued in notification_db. Observe a retryable
failed SMTP attempt and `nextAttemptAt`, then start Mailpit before the default
three-attempt budget expires. Expect `SENT` after retry without republishing
`OrderCompleted`. The delivery job polls every 2 seconds with configured backoff;
an exhausted budget is `FAILED`, not a false success. Restore Mailpit after the
check. Replaying the original completion envelope must not create a second
notification or EMAIL delivery. SMTP itself retains #191's delivery guarantees.

## Automated evidence and limits

Listener regression (11/11/2026): `ShipmentEventConsumerKafkaTest` publishes pickup,
delivery and replays through an embedded Kafka broker with producer/listener
observation disabled and PostgreSQL/Flyway/outbox enabled. The test opens no span,
keeps the retry job delayed for one hour, and checks `COMPLETED`, three history
rows, one traced `OrderCompleted` and replay idempotency after a partition barrier.
`ShipmentCompletionTest` also calls the listener without a test-created span.
`OrderReceiptRendererTest` covers canonical and legacy contact snapshots: event/email
HTML excludes contact data while the owner's invoice retains escaped details.

Run the focused regression suite from `commerce-service`:

```powershell
.\mvnw.cmd '-Duser.timezone=UTC' '-Dtest=ShipmentEventConsumerTest,ShipmentEventConsumerKafkaTest,ShipmentCompletionTest,OrderReceiptRendererTest,OrderReceiptHttpTest' test
```

Receipt HTML and transaction details still travel in `OrderCompleted`; removing
HTML entirely needs an authenticated internal retrieval contract. This change does
not remove contact data from previously persisted Kafka or audit records.

Verified on 07/11/2026 with Java 21 and UTC test JVM:

- `commerce-service`: `mvn -Duser.timezone=UTC clean verify` passed with **312 tests,
  5 skipped**, no failures/errors. The five existing catalog simulation tests need
  `CATALOG_CONTRACT_SIMULATION_URL`. PostgreSQL/Flyway tests cover history, duplicate
  and concurrent delivery, rollback, and an invoice matching the outbox document.
  HTTP tests cover owner/other/admin access, incomplete statuses, UTF-8 and escaping.
  Durable retry tests cover delivery before pickup, a recreated retry job, payment
  arriving later, outbox failure retaining the inbox row, and 101 waiting events
  allowing another ready order to complete. The retry job creates its own trace.
- `notification-service`: `mvn -Duser.timezone=UTC verify` passed **74 tests**, no
  failures/errors/skips. Real PostgreSQL and Mailpit verify HTML delivery, duplicate
  suppression, SMTP failure/recovery and the stored receipt surviving email retry.
- `api-gateway`: `mvn -Duser.timezone=UTC verify` passed **76 tests**, no failures,
  errors or skips. Includes invoice token/ORDER.VIEW rejection and original bearer
  forwarding using local JWKS/downstream fixtures; it does not prove ownership in
  commerce or live email delivery.
- `node docs/api/check-contract.mjs`: **62 delivered operations** match, including
  the receipt controller's HTML operation.
- Redocly CLI **2.63.0** validates `docs/api/openapi/commerce-service.yaml` without
  warnings; the `OrderCompleted` schema/example are valid JSON.
- `git diff --check` passes.

Rerun service tests from `commerce-service` and `notification-service` with
`mvn -Duser.timezone=UTC verify`; Docker is required for their PostgreSQL
integration tests. Build/install `lib` first when shared artifacts are absent.
Run contract checking and Redocly from `be-project-nexus`.

The manual Kafka/HTTP/Mailpit procedure above is a reproduction guide, not a claim
that a live end-to-end run passed. Shipment publisher #203 is still open at the
task's GitLab inspection; the full carrier-webhook-to-receipt flow is not verified.
Hand-published accepted shipment facts exercise #204 independently of that
publisher. #203 must publish the agreed Kafka key `orderId` while its envelope
aggregate is `shipmentId`; the existing shared relay defaults the key to the
aggregate, so its publisher integration must explicitly support the shipment
contract. Local tests and contract lint do not establish remote CI success.
