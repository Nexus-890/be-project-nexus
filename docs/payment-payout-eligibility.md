# Completed-order payout eligibility

Buyer #220's completion acceptance requires downstream payout preparation to
start only after COMPLETED. The payment service has no bank payout implementation
on this base; #261 owns payout instructions, verified bank accounts and execution.
This change adds a durable eligibility handoff, without issuing transfers.

`PayoutEligibilityConsumer` consumes only canonical `OrderCompleted` records from
the commerce topic in the independent `payment-payout-eligibility` group. A paid
manifest or shipment delivery alone never creates a completion projection or an
eligibility row. The immutable projection validates order/group/buyer/seller,
version, timestamp and order type, and refuses conflicting replays. Consumer
markers, projection and eligibility writes share the local transaction.

Eligibility requires the matching instruction and session to be PAID, backed by
a successful NORMAL transaction, correct identities/currency/version, a positive
amount and payment before completion. Refunds and pending cancellations block it.
The same group lock used by funding and refunds serializes this check. Replayed
events with the same or different envelope ID cannot create another row.

A completion arriving before its funding remains durable. The retry job selects
qualifying pending completions and rechecks conditions under the same lock.
`nexus.payment.payout-eligibility.cron` defaults to `*/30 * * * * *`; `-` disables
the scheduled job for focused tests. Legacy instructions with no seller identity
remain ineligible until a verified immutable manifest replay populates it.

Payment migration V5 adds nullable `payment_instructions.seller_id`,
`payment_order_completions` and `payout_eligibility`. The latter is a snapshot for
#261, not a permanently valid authorization to move funds. A future payout
instruction/executor must revalidate financial state, including later refunds,
and apply the owning bank/fee policies. No HTTP endpoint or public access policy
is added by this internal event consumer.

`PayoutEligibilityKafkaTest` verifies a real embedded Kafka broker and PostgreSQL:
PAID/delivery events do nothing; completion qualifies once; early events retry;
refund/cancellation, identity/version mismatch and failed transactions block it;
concurrent/conflicting replay and rollback retain atomicity. The
[completion acceptance module](../infra/receipt-completion-acceptance/README.md)
also feeds actual commerce outbox envelopes into this production consumer.


## Recovering paid plans created before V5

V5 deliberately leaves historical seller IDs empty instead of guessing them from
a completion event. Recovery uses the complete original immutable
CheckoutPaymentRequested envelope from the commerce outbox/archive, with its
original eventId. Replay it through the existing commerce topic, keyed by
orderGroupId. Do not delete the old payment-checkout-requested processed marker.

An already processed checkout enters the separate
payment-checkout-seller-backfill-v1 consumer transaction. It locks the payment
group, requires the stored source event ID and V4 manifest digest to match, and
checks the complete stored plan before filling only missing seller IDs. A
conflicting seller or plan rolls back the entire recovery and its new marker.
Session status, funding, refunds, business versions and financial events are
unchanged. Repeated/concurrent replay repairs once. The existing payout retry
job then rechecks completion and paid funding before qualifying the order.

Kafka offsets that were already committed are not automatically rewound. Operators
must explicitly replay retained original envelopes for affected groups. Missing
source envelopes or mismatched evidence require reconciliation; never substitute
seller IDs from OrderCompleted or hand-edit the original hash/processed markers.

LegacySellerReplayUpgradeTest creates actual V4 PAID/funded rows with their
original processed marker, migrates to V5, and replays that exact source envelope.
It covers payout after upgrade, tampered ownership, atomic group rollback,
concurrent replay, closed orders and unchanged financial state.

Verification on 2026-11-14: payment-service mvnw.cmd verify completed with
287 tests, zero failures/errors and three pre-existing skips on PostgreSQL 18.6.
All five LegacySellerReplayUpgradeTest scenarios passed with actual Flyway
V4-to-V5 migration and Hibernate ddl-auto=validate. Existing checkout replay
and embedded Kafka payout tests also passed in that run.