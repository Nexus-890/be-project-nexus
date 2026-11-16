# Automatic seller payouts (#257)

#258 decides when a seller order becomes COMPLETED: buyer confirmation or the
receipt deadline job. Shipment delivery and buyer payment alone never authorize
a payout. Both completion paths use `OrderCompletionService` and publish the
same `OrderCompleted` snapshot, including `orderAmount` and `shippingFee` as
decimal strings. Existing rating/receipt fields, including `receiptHtml`, remain.
Payment never reads commerce_db or user_db.

## Ownership and migrations

- user-service V8 creates `user_db.seller`, using the Keycloak user UUID as its
  primary key. #210 writes the application/review fields of this table and does
  not create another migration. Existing SELLER users get a lazy ACTIVE row on
  their first own-profile read, with the system actor as reviewer.
- payment-service V6 adds PAYOUT/PAYOUT_RETURN to the transaction type enum. V7
  creates `seller_bank`, `payouts` and append-only `payout_history`. V8 adds
  nullable money fields to the existing completion projection. Applied V1–V5
  remain unchanged; their existing completion/eligibility evidence is retained.
  These are four new business tables across two services, not a limit on the
  total database table count.
- #261 owns only administrator lists of payouts and accounts awaiting review.
  No payable-list, payout-rule table or manual create-payout endpoint is added.

The existing durable completion consumer and independent
`payment-payout-eligibility` group keep their funding/identity/version checks and
pending-completion retry. Payout creation joins this local transaction.
`processed_events` prevents duplicate deliveries; `UNIQUE(payouts.order_id)` also
prevents another payout when a different envelope describes the same order.

## Amount and eligibility

The instruction must be PAID and backed by its successful NORMAL funding
transaction in the same session/currency. Seller/group/buyer/version must match
the trusted checkout/completion projection. Refunds and pending cancellations
block eligibility. The declared order amount must equal the funded instruction
amount. The calculation is per seller order, never the whole group receipt:

```text
base         = payment_instructions.amount - OrderCompleted.shippingFee
platform_fee = round_half_up(base × platform_fee_rate, 2)
net_amount   = base - platform_fee
```

The fee rate comes from `nexus.payout.platform-fee-rate` (default `0.00`, allowed
`0.00`–`1.00`, at most four decimal places). The payout stores its rate and money
snapshot; later config changes affect only newly created payouts. Net amount
must be positive. A default rate of zero pays the buyer's actual order payment
minus shipping, which the platform pays GHN. Platform-funded voucher compensation
is outside this R1 contract.

A missing active, verified default bank account creates ON_HOLD and HELD history.
Verification releases the seller's held payouts as PENDING. A bank changed or
deactivated before dispatch is checked again; it cannot authorize a send merely
because it was verified when the payout was created.

Bank verification requires `expectedVersion`, taken from the exact bank-account
row the administrator reviewed. The service checks that version under the seller
lock and updates with `WHERE version = expectedVersion`. A stale approval or
rejection returns `409 PAYMENT_SELLER_BANK_ACCOUNT_CONFLICT`, writes no decision
and releases no held payout. The administrator must reload and review the changed
account; clients must not silently retry the decision with a newer version.

Legacy completion events without the two money fields remain valid for existing
consumers/projections, but cannot create an automatic payout. NULL shipping is
not zero shipping. Reconcile from trusted original commerce evidence before
backfilling/replaying an upgraded completion; never infer a shipping cost or
read commerce_db directly from payment-service.

## Durable transfer attempts

The dispatcher claims due PENDING rows using `FOR UPDATE SKIP LOCKED`, freezes
the recipient, increments the attempt and writes PROCESSING/SENT in a committed
transaction before contacting the gateway. Each send uses
`payout:{payoutId}:{attempt}`. Two workers cannot claim the same attempt.

Acceptance means PROCESSING. Confirmed provider success means SUCCEEDED and
writes one PAYOUT/SUCCESS transaction for that attempt on the order's session.
FAILED records a definitive rejection and retries with configured backoff until
the attempt limit. UNKNOWN records TIMED_OUT; reconciliation looks up the same
key before another attempt may begin. Reconciliation also recovers a stale
PROCESSING row after a process stops between commit and the provider response.
If lookup authoritatively returns NOT_FOUND, the provider never accepted that
key: recovery may resend the same frozen recipient, attempt and idempotency key.
An unknown lookup result does not authorize a new attempt. NOT_FOUND is an
internal lookup result and is rejected in callbacks.

Each reconciliation claim stores `next_reconcile_at` before any provider lookup,
including a lookup that returns UNKNOWN/ACCEPTED or throws. Workers atomically
claim one due row with `FOR UPDATE SKIP LOCKED`, then release the DB transaction
before network IO. The lease covers the configured interval and lookup/send
timeout window; an interrupted worker becomes eligible again after that lease.
Ordering by the next due time lets later rows progress instead of repeatedly
selecting the oldest 100 unresolved attempts. Payment V9 adds this scheduling
column/index; existing migrations stay unchanged.

The completion retry job also walks all candidate pages using the immutable
`(recorded_at, order_id)` cursor, with an upper bound captured at the beginning
of each run. Rejected or failed rows cannot block the next page. An immutable
nonpositive base (`instruction amount - shipping fee`) or declared amount
mismatch is excluded from retry candidates. A zero net caused only by the
current configurable fee rate remains retryable when that rate changes.

A confirmed bank return writes PAYOUT_RETURN/SUCCESS and sets RETURNED.
Administrator retry is allowed only for FAILED/RETURNED, requires an
Idempotency-Key, and records the administrator actor. The seller must first have
an active verified default account. A replayed administrator request cannot
enqueue another transfer; its request key is retained in history.

**Ledger key refinement:** a single `payout:{id}` transaction key cannot represent
success → return → successful retry. PAYOUT movements therefore use
`payout:{id}:{attempt}` and returns use `payout-return:{id}:{attempt}`. The payout
still has one row/order; its transaction pointers describe the current attempt.
History retains transaction links and recipient snapshots for earlier attempts.
Provider references and transaction keys deduplicate money movements; retries
never delete or overwrite an earlier movement.

History is ordered by `event_seq`, unique within a payout, and database triggers
reject UPDATE, DELETE and TRUNCATE. It records CREATED, HELD, RELEASED, SENT,
SUCCEEDED, FAILED, TIMED_OUT, RECONCILED, RETRIED and RETURNED. Every sent attempt
retains an encrypted recipient snapshot; changing the bank later preserves the
old evidence. API DTOs expose only the last four account digits and sanitized
provider status/errors, never ciphertext, credentials or full account numbers.
Automatic actions use UUID `00000000-0000-0000-0000-000000000000`.

## Sandbox and provider configuration

Default `nexus.payout.provider: sandbox` selects `SandboxPayoutGateway`.
Every associated payout/history/event is explicitly `simulated: true`.
Sandbox success is demonstration evidence, not confirmation of a real transfer.

| Setting | Default / meaning |
| --- | --- |
| `nexus.payout.platform-fee-rate` | `0.00`; snapshot per payout |
| `nexus.payout.provider` | `sandbox`; alternative `http` |
| `nexus.payout.dispatch-cron` | `*/10 * * * * *`; polls due PENDING payouts |
| `nexus.payout.jobs-enabled` | `true`; disable scheduled sends/reconciliation for focused tests |
| `nexus.payout.max-attempts` | `3` total attempts before terminal FAILED |
| `nexus.payout.retry-backoff` | `1m` |
| `nexus.payout.reconcile-interval` | `1m` |
| `nexus.payout.provider-timeout` | `10s` |
| `nexus.payout.sandbox-outcome` | `SUCCESS`; alternatives FAILURE, TIMEOUT, RETURN, ACCEPTED |
| `nexus.payout.account-encryption-key` | `PAYOUT_ACCOUNT_ENCRYPTION_KEY`; required base64 of exactly 32 bytes, no default secret |
| `nexus.payout.provider-url` | HTTPS gateway contract base URL for `http` |
| `nexus.payout.provider-token` | Outbound gateway credential for `http`, supplied securely |
| `nexus.payout.callback-secret` | HMAC callback secret, supplied securely |

FAILURE produces a definitive failure; TIMEOUT is unresolved on send and succeeds
on lookup; ACCEPTED stays PROCESSING until lookup; RETURN first succeeds and is
returned on lookup. These outcomes exercise the state machine without moving
funds. Change the configured scenario deliberately between runs when testing a
failed transfer followed by successful retry.

Bank encryption uses AES-256-GCM with a random 12-byte nonce, a 16-byte tag and
AAD `nexus:seller-bank:v1`. The stored versioned value is
`v1:base64(nonce || ciphertext || tag)`. Account numbers are strings so leading
zeroes survive. Keep the encryption key available for historical snapshots;
changing it without a controlled re-encryption/rotation strategy makes those
snapshots unreadable. Never commit real bank details or keys as examples.

The `http` adapter implements the project's generic contract: POST `/transfers`
with an Idempotency-Key and transfer body; GET `/transfers/by-key/{key}` to look
up the same attempt; results contain `state`, `reference`, `providerStatus`.
Lookup may return NOT_FOUND only when the provider can authoritatively confirm
that the original key was never accepted. Drain unresolved attempts on the
current provider before switching configuration: callbacks accept only the
configured provider, and reconciliation selects that provider's attempts.
It is not evidence of a signed contract or compatibility with a named live bank
provider. Actual transfers require a funded source account, credentials and a
provider implementing this contract (or an adapter changed to its documented
contract). SePay/VietQR receive money and do not provide this outbound capability.

Callbacks are POST `/api/v1/payments/payouts/callbacks/{provider}`. They require
`X-Payout-Timestamp` in Unix seconds and `X-Payout-Signature`, the hex HMAC-SHA256
of `timestamp + "." + raw body`, within 300 seconds. A callback names payoutId,
attempt, idempotencyKey, state, reference and providerStatus; it must match the
persisted attempt. Native acknowledgement is outside ApiResponse; signature
failures remain Problem Details. Existing buyer reads count NORMAL/REFUND only,
so payout traffic cannot alter buyer confirmation, receipt or refund totals.

Scheduled reconciliation also rechecks held sellers, so a bank verification
racing payout creation cannot leave an otherwise ready payout held indefinitely.

## Recovering seller identities created before V5

V5 left historical seller IDs NULL instead of trusting a completion to establish
ownership. Recovery replays the complete original CheckoutPaymentRequested
envelope from the commerce outbox/archive with its original eventId, keyed by
orderGroupId. Preserve the original processed marker.

The separate `payment-checkout-seller-backfill-v1` transaction locks the group,
requires the original source ID/V4 manifest digest and checks the entire stored
plan before filling missing seller IDs. A conflicting seller or plan rolls back
the repair and marker. Funding, refunds and financial events remain unchanged.
Kafka offsets are not rewound automatically. Missing or conflicting original
evidence requires reconciliation; never hand-edit the digest or processed marker.
