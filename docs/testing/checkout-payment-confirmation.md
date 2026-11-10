# Checkout payment confirmation — #156

The authenticated SePay webhook funds one immutable checkout session and its
complete seller-instruction plan. Payment commits the receipt, funding and one
CheckoutPaymentConfirmed outbox row together. Commerce commits its idempotent
claim, group payment evidence, eligible child status/history and one OrderPaid
per child together. HTTP 200 acknowledges the webhook before commerce completes.
The integration target is completion within 10 seconds under normal local
operation; a unit test or an outbox row alone does not prove that latency.

## Contract and stored data

`POST /api/v1/webhooks/sepay` keeps the #155 raw-body HMAC verification, memo
normalization and native `{"success":true}` acknowledgement. Signature failures
return 401 `PAYMENT_UNAUTHENTICATED`; malformed signed JSON and invalid matched
incoming receipt fields return 400 `PAYMENT_INVALID_WEBHOOK_REQUEST`. The exact
path stays public at gateway and payment-service. Do not send a bearer token.

The SePay transaction id is retained as `payment_transactions.txn_reference`.
`idempotency_key=sepay:{id}` is a durable global receipt identity, protected
against concurrent deliveries before any funding change. Deduplication remains
effective beyond the SRS §2.6 callback-window baseline of 60 minutes; it does not
make an old signed HTTP request pass the separate 300-second authentication
freshness check. Re-sign a synthetic duplicate with a current timestamp when
testing it later.

Only an incoming amount equal to the still-PENDING, unexpired session total and
a complete payable instruction plan becomes NORMAL/SUCCESS. Every instruction
becomes PAID with `funded_transaction_id` pointing to that receipt. Amount
mismatch or an unpayable session leaves one UNRECONCILED receipt, one WARN and
no CheckoutPaymentConfirmed. Outgoing transfers and unmatched memos produce no
funding changes. No partial payment is implemented.

Payment calls `RefundService.applyPendingCancellations(orderGroupId)` after
funding, inside the same transaction. An earlier paid-order cancellation stored
in `payment_pending_order_cancellations` creates one REQUESTED refund using
`system:cancel-refund:{orderId}`, writes one OrderRefundRequested and removes its
pending row. Failure rolls the entire receipt/funding/outbox/refund operation back.

CheckoutPaymentConfirmed uses `nexus.payment.events`, Kafka key `orderGroupId`
and envelope aggregate `sessionId`. The payment-specific OutboxEventKeyResolver
selects the group key from the persisted payload; the shared relay preserves
the stored envelope, event id, timestamp and trace. Missing/malformed required
keys remain unpublished for retry. Other payment events retain their aggregate
keys. No shared library contains payment-domain event selection rules.

Commerce checks the envelope/key, buyer, VND currency, exact order membership,
unique instruction/order ids, each share and the total against its own stored
group. It serializes against cancellation by locking the group and child rows
in a consistent order. Repeated envelopes or another envelope for the same
accepted receipt do not duplicate histories/events. CANCELED/EXPIRED groups and
cancelled children are never resurrected. Payment retains financial evidence
and owns compensation.

Commerce migration `V10__order_group_payment_identity.sql` adds nullable
`payment_session_id` and `payment_transaction_id` to its own `order_groups` table.
They are logical UUID references to accepted payment evidence, with no cross-DB
foreign key or payment_db read. The first accepted confirmation binds them in
the same transaction as PAID. A later confirmation with another receipt cannot
overwrite that binding, including one using a different eventId. Existing paid
groups need an explicit historical evidence/backfill decision before replay;
the migration does not invent payment identities for old data.

OrderPaid uses each order's stored warehouse UUID, shipping fee and recipient
snapshot and rebuilds its item list from `order_items`. Money is emitted as
decimal strings with two fractional digits. Address ward/district/province
codes must equal the stored values; no catalog, inventory or shipment lookup
fills missing data. Parcel and pickupAddress stay null when unavailable. Until
#138 provides the real checkout snapshot, use complete test `shipping_info`
fixtures, including `warehouseId` and `recipient.address` locality codes. An
incomplete snapshot must not produce a malformed OrderPaid or commit a partial
group transition.

## Automated verification

Use JDK 21 and a fresh isolated PostgreSQL database or Docker Desktop with Linux
containers. From `be-project-nexus`, build the shared libraries before compiling
services that use the relay extension:

```powershell
Push-Location lib
mvn '-Duser.timezone=UTC' install
Pop-Location
Push-Location payment-service
mvn '-Duser.timezone=UTC' clean verify
Pop-Location
Push-Location commerce-service
mvn '-Duser.timezone=UTC' clean verify
Pop-Location
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/payment-service.yaml docs/api/openapi/commerce-service.yaml
```

Check payment receipt replay/concurrency, mismatch, rollback and pending-refund
cases against PostgreSQL. Check commerce duplicate delivery, wrong buyer/group/
membership/amount, cancellation, missing snapshot and all-or-nothing rollback.
Validate actual emitted OrderPaid payloads against the Draft 7 schema extracted
from `docs/api/events.md`, including UUID/date-time formats. The relay key test
must observe a group Kafka key and a session aggregate on the serialized wire.

## End-to-end acceptance

1. Prepare a fresh group with two PENDING orders of 200000 and 150000 VND,
   complete stored shipping snapshots, a PENDING 350000 VND session and two
   matching instructions. Keep the payment and commerce fixtures in their own
   databases. Record the relevant inventory balances read-only.
2. With payment/commerce listeners, Kafka and both outbox relays running, send
   an independently signed incoming SePay receipt for 350000. Verify native
   HTTP 200, exactly one SUCCESS receipt, one CheckoutPaymentConfirmed with two
   instructions and correct group key/session aggregate. Within 10 seconds
   verify group/order PAID, paid_at, one PAID history per order and two OrderPaid.
3. Replay the receipt sequentially and concurrently, then redeliver the stored
   confirmation to commerce. Counts and payment bindings must stay unchanged.
4. For a separate fresh group, send 300000 against 350000. Verify one
   UNRECONCILED receipt, no confirmation and unchanged PENDING commerce state.
5. For a separate fixture, deliver OrderCancelled with paidAmount > 0 before
   funding. Verify its pending row first; after valid funding verify one
   REQUESTED refund, its system key, one refund outbox row and no pending row.
6. Validate OrderPaid schemas and exact stored warehouse/fee/address/item values,
   then compare inventory balances to the initial read-only snapshot. #160 owns
   downstream inventory confirmation; stop or isolate that consumer when checking
   this flow's no-inventory-write boundary.

Use synthetic ids, memos and test signatures for fixtures. Keep provider secrets,
raw authorization headers and personal shipping data out of committed evidence.
The historical #155 captured-delivery tests demonstrate signature handling,
not these settlement or Kafka acceptance criteria. Record actual test counts,
skips and end-to-end timing separately; the commands above are reproducible
instructions rather than a claim that a run has passed.

## Local verification — 10 November 2026

Verified on the primary integration checkout with JDK 21, PostgreSQL 18 and
isolated Kafka fixtures. Payment verify: 208 cases, zero failures/errors, three
existing provider-capture cases skipped because their external headers/secret
were not supplied. Commerce clean verify: 325 cases, zero failures/errors, five
existing external Catalog-simulation cases skipped. The new payment cases cover
receipt concurrency, durable provider identity (including legacy keys), mismatch,
pending refunds and database-enforced rollback/retry. Commerce validates actual
OrderPaid payloads against this repository's event schema, duplicate/concurrent
delivery, immutable payment binding, snapshot fidelity and rollback. Its Kafka
test observes both PAID orders/history/outbox within ten seconds of publication.

Shared outbox: 38 unit/database cases and ten isolated PostgreSQL/Kafka integration
cases passed without skips, including an actual Kafka key different from the
unchanged envelope aggregate. Gateway: 77 cases passed. Auction merge regression:
152 cases plus ten concurrency repetitions (500 requests) passed without skips.
API inventory validation passed for all 67 delivered operations.

These are local component/integration results. The complete live HTTP webhook ->
payment relay -> commerce -> inventory balance comparison was not run, and no
remote CI result is claimed. GitLab issue #138 remains open on this date; real
checkout shipping snapshots are still its dependency. Verification uses complete
stored shipping fixtures, as #156 explicitly permits until #138 is delivered.
