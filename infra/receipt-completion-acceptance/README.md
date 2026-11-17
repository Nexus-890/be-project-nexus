# Completion effect acceptance — #220, #257 and #258

This module verifies the real commerce completion transaction and its two
downstream boundaries. It creates isolated PostgreSQL 18 databases, runs each
service's Flyway migrations and starts its actual repositories/services/consumer
components with Hibernate `ddl-auto=validate`.

It also covers the paid-order shipment boundary in `PaidOrderShipmentAcceptanceTest`.
The actual `CheckoutPaymentConfirmedConsumer` processes a payment confirmation
for a persisted PENDING order and writes `OrderPaid` through the real commerce
outbox. Its serialized payload, with no `serviceType` and a null `pickupAddress`,
is delivered to the production shipment consumer and tracking worker against a
separate shipment database. The real GHN adapter talks to a local HTTP provider
fixture. These scenarios verify configured pickup preparation, a GHN code within
one recovery-worker cycle, FAKE/NONE booking with the stored quote fee, and one
remote order plus one fallback-history row after address fallback and timeout.
They do not invoke the SePay webhook, submit an order in the browser or inspect
the GHN dashboard; those remain separate manual acceptance steps.

- DELIVERED and reported-issue orders have no completion outbox event, receipt
  delivery, payout eligibility or payout.
- Buyer confirmation and the due-order job commit one completion. The actual
  serialized outbox envelope is passed to the production notification consumer;
  its real queue/retry/attempt logic and JavaMail sender deliver MIME over TCP to
  a local SMTP inbox. Event replay and an SMTP outage/retry are covered.
- Repeating buyer confirmation after completion returns
  `COMMERCE_ORDER_NOT_DELIVERED` (409), including after automatic completion;
  the conflict does not add another completion event, receipt or payout.
- The same actual completion envelope is passed to the production payment
  consumer. Only matching paid funding qualifies, with one durable eligibility
  row and one ON_HOLD payout after replay when no bank account is verified.
  The actual serialized orderAmount/shippingFee fields determine the frozen
  payout amounts; nonzero shipping and persisted discounted-order examples prove
  that only the buyer-funded proceeds are payable, without platform voucher
  compensation. A reported receipt issue, refund or unfunded instruction
  prevents payout creation.

The broker hop is deliberately explicit in this module: it calls the actual
consumer with the committed record rather than claiming a deployed Kafka relay.
`PayoutEligibilityKafkaTest` in payment-service separately exercises a real
embedded Kafka broker, non-completion events, duplicate and conflicting
deliveries, funding arriving later, refunds/cancellation, identity mismatch,
concurrency and transaction rollback.

The SMTP inbox and recipient-address lookup are local test providers. No real
customer receives email. #257 owns automatic payout creation. These acceptance
fixtures use its production payout service with the sandbox gateway selected,
and stop at ON_HOLD because no verified default account exists. They make no
bank-transfer call. Payment-service's PayoutFlowTest separately verifies sandbox
dispatch, failures/retries, timeout reconciliation, returns and concurrency.
Every sandbox history entry is labelled simulated. #261 owns administrator lists.

Build plain service artifacts into the local Maven repository first. A clean
build matters when a previous build produced a Spring Boot executable jar:

```powershell
# Run from each of commerce-service, notification-service, payment-service and shipment-service:
.\mvnw.cmd clean install '-DskipTests' '-Dspring-boot.repackage.skip=true'

# Run from infra/receipt-completion-acceptance:
mvn clean verify
```

By default the module starts PostgreSQL through Testcontainers. To use a native
PostgreSQL 18 installation when Docker is unavailable:

```powershell
mvn clean verify "-Dnexus.test.jdbc-url=jdbc:postgresql://127.0.0.1:55496/postgres" "-Dnexus.test.jdbc-user=postgres" "-Dnexus.test.jdbc-password="
```

Use a disposable local instance: the test user must have CREATE DATABASE
permission. Every fixture creates a fresh database and resolves only its owning
service's filesystem migrations, avoiding service migration-name collisions.
Test databases are retained for inspection until the disposable instance is
removed. Never configure a shared or production database.

Migration compatibility is additionally verified in commerce's
`ReceiptMigrationCompatibilityTest`: V12, latest develop V13 (payment note), and
consolidated V14 (auction reference) upgrade through V15 (receipt window) and V16
(receipt hold), preserving delivered data and the auction-reference uniqueness
index. MR !171 must reuse V14 for its identical auction SQL when integrating;
its old V13 version now conflicts with develop. Existing development databases
that applied the unmerged earlier #220 V13/V14 drafts need a fresh disposable database;
these tests do not rewrite applied Flyway checksums.

On 2026-11-14 the module passed all five scenarios with native PostgreSQL 18.6:
three receipt-email scenarios and two payout-eligibility scenarios. Commerce's
clean verify passed 460 tests (five existing skips), payment verify passed 274
(three existing skips), before the final synchronization with develop. The
latest run additionally validates all three consolidated migration baselines.
After synchronizing develop `a686c099`, all five acceptance scenarios passed
again; commerce clean verify passed 475 tests and payment clean verify passed
282, with the same five/three existing skips and no failures/errors.

An additional `PaidOrderShipmentSandboxTest` is opt-in and skipped by the normal
suite. With only `GHN_TOKEN` and `GHN_SHOP_ID` supplied privately in the process
environment, run `mvn test '-Dtest=PaidOrderShipmentSandboxTest'` with
`GHN_SANDBOX_SMOKE=true` (and the native JDBC overrides above when needed).
It uses GHN's public `72 Lê Thánh Tôn, Phường Sài Gòn, Hồ Chí Minh` example,
resolves its live administrative codes, processes an actual Commerce payment
confirmation/outbox and commits a pending shipment before the immediate booking
wakeup. One real tracking-worker cycle must obtain a GHN sandbox code. Event
replay, another worker run and a direct client-code lookup must retain the same
code and history count. The test reads no private pickup/recipient settings and
does not prove the browser payment or GHN-dashboard portions of manual T3.
