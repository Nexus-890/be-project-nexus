# Shipment status handling — #203

## Package layout

The implementation follows the existing shipment-service package layout:

| Package below `com.group5.shipmentservice.shipping` | Classes |
| --- | --- |
| `controller` | `CarrierWebhookController`, `ShipmentStatusController` |
| `service` | `ShipmentStatusService`, `ShipmentTransitions` |
| `dto.request` | `ShipmentStatusRequest`, `ShipmentOverrideRequest` |
| `dto.response` | `ShipmentTrackingEntryResponse`, `ShipmentTrackingResponse`, `CarrierWebhookResponse` |
| `event` | `OrderCancelledConsumer` |
| `event.payload` | `ShipmentStatusChanged` |
| `job` | `TrackingPollJob` |
| `repository` | `ShipmentStatusReceiptRepository` |

The receipt repository owns the webhook and cancellation SQL. Services retain transaction orchestration and delegate database access to repositories. Controller tests mirror the `controller` package; integration tests remain alongside the existing shipment integration tests.

The implementation uses the local #201 carrier adapter and #202 shipment creation foundation. Status updates belong to shipment-service; the gateway routes `/api/v1/webhooks/carriers/**` there. Shipment events use `nexus.shipment.events`.

## Configuration and callback authentication

Set `GHN_WEBHOOK_SECRET` to a dedicated callback secret and configure GHN's custom header `X-Carrier-Signature` with the same value. `GHN_TOKEN` and `GHN_SHOP_ID` configure outbound GHN detail/cancel calls; the callback `ShopID` must equal that configured shop. Do not reuse the outbound API token as the callback secret.

GHN documents configurable callback headers, rather than a native HMAC body signature. Here, `X-Carrier-Signature` is the configured shared callback authentication token. The adapter compares it in constant time before parsing the callback or changing persisted state, and rejects missing/mismatched headers or missing secret configuration with 401 `SHIPMENT_INVALID_SIGNATURE`.

The fake adapter uses `FAKE_WEBHOOK_SECRET` with the same header and PascalCase callback fields. Fake `Status` values are internal shipment enum names; GHN uses its own status strings. Fake callbacks omit `ShopID`. A successful callback, including a duplicate, returns HTTP 200 with the protocol body:

```json
{"code":200,"message":"Success"}
```

The acknowledgement is intentionally excluded from the application `ApiResponse` wrapper by `@ProtocolResponse`. Its errors still use `application/problem+json` and shipment-service `SHIPMENT_*` codes.

The callback identity hashes carrier, `OrderCode`, `Type`, raw `Status` and the instant represented by `Time`. Including status preserves distinct transitions sharing a provider timestamp. Changes to `Reason`, JSON formatting or an equivalent timestamp offset do not create a new identity.

Operational configuration under `nexus.shipping`:

| Property | Environment override | Default |
| --- | --- | --- |
| `status-poll-enabled` | `SHIPPING_STATUS_POLL_ENABLED` | `true` |
| `status-poll-interval-ms` | `SHIPPING_STATUS_POLL_INTERVAL_MS` | `60000` per shipment |
| `status-poll-job-ms` | `SHIPPING_STATUS_POLL_JOB_MS` | `5000` worker delay |
| `status-poll-initial-delay-ms` | `SHIPPING_STATUS_POLL_INITIAL_DELAY_MS` | `60000` |
| `order-cancelled-consumer-enabled` | `SHIPPING_ORDER_CANCELLED_CONSUMER_ENABLED` | `true` |

Official provider contracts:

- [GHN status callback](https://developer.ghn.vn/en/docs/webhook/callback-order-status.md)
- [GHN status catalogue](https://developer.ghn.vn/en/docs/master-data/order-status.md)
- [GHN order detail](https://developer.ghn.vn/en/docs/order/info.md)
- [GHN cancellation](https://developer.ghn.vn/en/docs/order/cancel.md)

## API checks

Obtain an existing shipment with a tracking code through the #202 creation flow. Use a buyer or trusted seller JWT owning that shipment to read tracking, or an Admin/Support Staff JWT with `SHIPPING.VIEW_SHIPMENT`. UUIDs and carrier tracking codes in the examples are placeholders for local fixtures.

```http
GET /api/v1/shipments/{shipmentId}/history?page=0&size=20
Authorization: Bearer {token}
```

Expect `ApiResponse` with a history array in `data` and sibling `page` containing `number`, `size`, `totalElements` and `totalPages`. History sorts by `changedAt` descending, then `id` descending. An empty page returns `data: []`; requesting another buyer's shipment returns the same 404 `SHIPMENT_NOT_FOUND` as a missing shipment. Invalid pagination returns 400.

Admin or Support Staff requires the corresponding privilege in addition to the role:

```http
PATCH /api/v1/shipments/{shipmentId}/status
Authorization: Bearer {token-with-SHIPPING.UPDATE_STATUS}
Content-Type: application/json

{"status":"IN_TRANSIT","reason":"Carrier progress confirmed by Support"}
```

```http
POST /api/v1/shipments/{shipmentId}/override
Authorization: Bearer {token-with-SHIPPING.MANUAL_OVERRIDE}
Content-Type: application/json

{"status":"OUT_FOR_DELIVERY","reason":"Support confirmed delivery attempt"}
```

Expect HTTP 200 `ApiResponse<ShipmentResponse>`. Override requires a nonblank reason, maximum 255 characters. Both operations use the same transition rules: forward steps may be skipped, `DELIVERY_FAILED -> OUT_FOR_DELIVERY` permits a delivery retry, and `DELIVERED`, `CANCELLED`, `RETURNED` are terminal. Without an assigned tracking code, manual updates may only repeat the current state or cancel; every other change returns 409 `SHIPMENT_INVALID_TRANSITION` to preserve the pending booking retry. Repeating the current status adds no history/event.

Example GHN callback:

```http
POST /api/v1/webhooks/carriers/ghn
X-Carrier-Signature: {configured-callback-secret}
Content-Type: application/json

{"ShopID":123456,"OrderCode":"GHN-12345","Type":"switch_status","Status":"transporting","Time":"2026-11-11T10:00:00Z","Reason":""}
```

Use the actual configured ShopID and tracked GHN shipment. To test locally without contacting GHN, send the same shape to `/api/v1/webhooks/carriers/fake`, omit `ShopID`, use the fake tracking code and set `Status` to `IN_TRANSIT`.

GET /api/v1/shipments/{id}/tracking retains the #206 summary object with shipmentId, orderId, carrier, trackingCode, status and chronological history. The separate /history endpoint returns the paginated audit array above. Both enforce buyer/seller ownership. Seller ownership comes from the top-level OrderPaid.sellerId or commerce shipping-snapshot.sellerId, stored in shipments.seller_id by V4; shippingInfo cannot grant ownership. Legacy rows with no trusted seller remain hidden to sellers until a trusted OrderPaid replay supplies the missing owner.

PATCH may omit reason; the service then stores "Manual shipment status update" so MANUAL lifecycle events satisfy the commerce consumer contract. Override still requires an explicit nonblank reason.

Same-status carrier observations advance last_carrier_update_at without changing the shipment business version/history/outbox. Older observations cannot reopen a failed delivery attempt.

Commerce accepts verified post-pickup status events as evidence of SHIPPED. A delivery with no prior pickup atomically records PAID -> SHIPPED -> DELIVERED -> COMPLETED. PENDING orders and missing orders are retried durably after payment/order creation; duplicates never repeat completion.

## Acceptance checks

Record initial shipment status/version and count its `shipment_history` and status outbox rows before each isolated case.

| Case | Expected result |
| --- | --- |
| Valid GHN callback with a new forward status | One normalized target status, one new history row and one Shipment* outbox row |
| Missing/wrong callback token | 401 `SHIPMENT_INVALID_SIGNATURE`; no shipment/history/outbox changes |
| Identical callback delivered twice | Both replies 200; status/history/event effects occur once |
| Same OrderCode/Type/Time but two distinct forward statuses | Distinct callback identities; each accepted transition has one history/event |
| Poller and webhook observe the same target concurrently | One accepted transition, one history row and one event |
| `DELIVERED -> IN_TRANSIT` by status update or override | 409 `SHIPMENT_INVALID_TRANSITION`; no state/history/event change |
| Any manual progress before tracking code assignment | 409 `SHIPMENT_INVALID_TRANSITION`; pending booking retry remains active |
| Override has missing/blank reason | 400 `SHIPMENT_VALIDATION_FAILED`; no changes |
| Buyer attempts status update | 403 even when a token carries a mutation privilege without the required operations role |
| Unknown carrier status | 422 `SHIPMENT_INVALID_CARRIER_STATUS`; no changes |
| Malformed callback or wrong GHN ShopID | 400 `SHIPMENT_INVALID_WEBHOOK`; no changes |
| OrderCancelled before pickup with a booking | CarrierAdapter cancellation, CANCELLED, one history and one ShipmentCancelled outbox row |
| OrderCancelled before pickup without a booking | CANCELLED locally; no attempt to cancel a nonexistent carrier booking |
| Duplicate OrderCancelled | One processed marker for `(eventId, consumer)`; no repeated local effects |
| OrderCancelled after pickup | No shipment/history/outbox changes; one WARN for Support for the handled event |
| OrderCancelled without a shipment | Consumer completes without error and stores a cancellation tombstone |
| Late OrderPaid after the cancellation tombstone | No shipment creation or carrier booking |
| Manual creation after the cancellation tombstone | 409 `SHIPMENT_ORDER_NOT_PAID` |
| Valid OrderCancelled reason of 256–500 characters | Accepted; audit reason is sanitized/truncated to 255 before tracking/outbox writes |
| OrderCancelled reason longer than 500 characters | Rejected before calling carrier; no cancellation effect or processed marker |
| Carrier cancellation fails | Local cancellation and processed marker do not commit; delivery remains retryable |

The status/version/history/outbox changes share one local transaction. Carrier calls and Kafka delivery are outside that SQL transaction. Outbox relay redelivery preserves the event identity; downstream consumers still need idempotency. This does not claim an exactly-once external carrier request.

`cancelled_shipment_orders` stores `order_id`, `event_id`, `reason` (maximum 500 characters) and `received_at`. The durable tombstone prevents a valid cancellation arriving before shipment creation from being lost when OrderPaid is delivered later. OrderCancelled validates its nonblank reason and 500-character bound before external effects; the tracking/outbox audit uses a sanitized reason truncated to 255 characters.

The poller queries active tracked shipments to repair missed callbacks. `TrackingRetryJob` from #202 instead retries obtaining a tracking code after creation; these are separate jobs. A stale carrier result cannot reverse progress or reopen a terminal shipment.

R1 allows a PAID order to be cancelled until `ShipmentPickedUp`, without a handover request or request/ACK protocol. The consumer follows integration flow 4 in [events.md](../api/events.md#integration-flows-and-compatibility).

## Verification commands

From `be-project-nexus`:

```powershell
mvn -f lib/pom.xml install
mvn -f shipment-service/pom.xml verify
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/shipment-service.yaml
```

Record actual executed test results separately. Provider sandbox calls and real Kafka/PostgreSQL integration require the corresponding local infrastructure and credentials; OpenAPI lint alone does not verify those paths.

Static checks executed on 2026-11-11:

- `check-contract.mjs`: all 82 application operations match their OpenAPI inventory, including 11 shipment-service operations.
- Redocly CLI 2.63.0: shipment-service OpenAPI passes lint.
- Event catalogue: all 106 JSON blocks parse; 50 schemas, 64 SRS names plus 7 additions and 50 publisher rows agree. The two #203 schema/example pairs pass validation; 28 missing-required-field cases and 6 invalid UUID/time/status cases are rejected.

Runtime verification executed on 2026-11-11 using Java 21.0.12.1:

- Shared libraries installed successfully, then `mvn -f shipment-service/pom.xml verify` completed with BUILD SUCCESS: 419 tests, zero failures, zero errors, zero skipped.
- After moving classes into the existing layered packages and extracting receipt SQL into `ShipmentStatusReceiptRepository`, `mvn -f shipment-service/pom.xml clean verify` also passed all 419 tests, including the architecture rule forbidding JDBC access from services.
- PostgreSQL 18 integration tests exercised migration validation, concurrent webhook deduplication, history/outbox rollback, polling retry, cancellation, reason bounds and cancellation arriving before OrderPaid.
- Kafka 4.3.1 integration tests sent real duplicate OrderPaid and OrderCancelled messages, waited for committed offsets, and verified the relayed shipment event identity, order key, trace and history/version correlation.
- GHN HTTP adapter tests used a local HTTP fixture. No live GHN sandbox booking or cancellation was performed.
- No code comments were added. Work is on `ngothanhtu8989/feature/203-be-shipment-status-webhook-poller-manual-update`, based on local #202 commit `66063d1`, with the #201 carrier foundation already present in that branch.
