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
