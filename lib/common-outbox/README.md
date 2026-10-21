# common-outbox

Transactional outbox for the six PostgreSQL business databases. Uses the existing
`V1__init.sql` tables and partial indexes; no migration or scheduler lock is added.

## Enable in a business service

Install the shared libraries from `be-project-nexus/lib` with `bash ./mvnw install`,
then add the library to the service's POM:

```xml
<dependency>
    <groupId>com.group5</groupId>
    <artifactId>common-outbox</artifactId>
    <version>${project.version}</version>
</dependency>
```

Boot discovers `OutboxAutoConfiguration` from the JAR. A single candidate
`JdbcTemplate` and `PlatformTransactionManager` are required (a primary candidate
also works). They must refer to the **same service-owned DataSource**. The library
supports JDBC transactions and JDBC participating in a `JpaTransactionManager`.
It does not configure a second transaction manager or scan JPA entities.

The writer is available independently of Kafka availability. The scheduled relay
also requires the named `EventKafka.TEMPLATE` bean from `common-events`, not the
default string-valued Kafka template. User-provided writer/relay beans take
precedence over the defaults.

This issue adds the shared library, not new domain events or service dependencies.
**Do not activate a relay alongside the current commerce `KafkaDemoPublisher`.**
That demo inserts into this same table, sends directly, and marks the row without
a surrounding transaction; the relay could send its row concurrently. A caller
adopting the library must replace that flow with writer-only publication first.
Existing pending rows must contain the envelope format described below.

## Write inside the business transaction

```java
@Transactional
public UUID createProduct(CreateProduct command) {
    Product product = products.save(Product.from(command));
    EventEnvelope<ProductCreated> event = envelopes.create(
            "ProductCreated", product.getId().toString(), ProductCreated.from(product));
    return outbox.write("Product", Topics.CATALOG, event);
}
```

The service supplies `OutboxWriter` and `EventEnvelopeFactory` through constructor
injection. `EventEnvelopeFactory` requires an active trace, including when used
from a listener or job.

`write(aggregateType, topic, envelope)` defaults `event_version` to `1`.
`write(aggregateType, eventVersion, topic, envelope)` accepts an explicit positive
version. Both return the event UUID and do **no Kafka I/O**. Programmatic
`PROPAGATION_MANDATORY` rejects calls outside a transaction even without a proxy.
Business code controls the outer rollback rules (Spring's default rolls back on
runtime exceptions).

Persisted contract:

| Column | Value |
|---|---|
| `id` | Original `envelope.eventId()`; never generated again by the relay |
| `aggregate_type`, `event_version`, `topic` | Explicit writer arguments |
| `aggregate_id`, `event_type`, `trace_id`, `occurred_at` | Original envelope metadata |
| `payload` | Complete serialized `EventEnvelope`, not only its nested business payload |
| `published_at` | NULL until a relay receives a successful send result |

`aggregate_type` and `event_version` remain database metadata: the current
`EventEnvelope` wire contract has no corresponding fields. Kafka records use the
stored topic, `aggregate_id` as key, and the original envelope as JSON value with
no nested/double-encoded envelope. Retry preserves the original event ID, time,
trace and business payload; it does not call `EventEnvelopeFactory` again.

## Relay and failure semantics

Each run opens its own database transaction and claims at most 100 rows:

```sql
SELECT * FROM outbox
WHERE published_at IS NULL
ORDER BY occurred_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

It holds those row locks while awaiting Kafka acknowledgements and updating
`published_at`. Other instances skip the locked rows and can work on another
batch. No ShedLock or global scheduler lock is involved.

- An acknowledged row is marked with the database's current clock time.
- The first failed, interrupted, timed-out or invalid-envelope send stops that
  run. That row and the unattempted rows stay unpublished; earlier acknowledged
  updates commit. The next scheduled run retries pending rows.
- A database error rolls back the batch rather than being hidden as a send error.
- A timeout does **not** establish that Kafka did not receive the message.
- Kafka acknowledgement followed by a process crash or failed DB commit can
  produce another Kafka record on retry. This is **at-least-once**, not a
  distributed exactly-once transaction. Consumers must deduplicate by event ID,
  for example with the existing `(event_id, consumer)` `processed_events` key in
  the same transaction as their side effect.
- `SKIP LOCKED` and the aggregate key do not establish strict per-aggregate or
  global event ordering across instances. There is no dead-letter/quarantine
  policy here; a permanently invalid oldest row needs operator correction.

Configure in the consuming service's Config Server YAML:

```yaml
nexus:
  outbox:
    relay:
      enabled: true           # default; false disables only the relay and its scheduler
      fixed-delay: 1000       # milliseconds between completed runs
      initial-delay: 1000     # milliseconds before the first run
      send-timeout: 3s        # default; positive duration, at least 1ms
```

The default of three seconds bounds the acknowledgement wait and therefore
keeps a slow Kafka broker from holding a claimed database batch for too long.
It is still configurable per service. The future timeout does not bound Kafka's separate
`max.block.ms` metadata/buffer wait inside `send()`. Configure Kafka
`max.block.ms`, `request.timeout.ms` and `delivery.timeout.ms` consistently with
the desired database lock duration and operational retry budget. Keep the
shared producer's `acks=all` and `enable.idempotence=true`; producer idempotence
does not deduplicate a new application-level relay attempt.

## Verification

Use Java 21, as configured in the parent POM. Fast unit/context checks, no external services:

```bash
cd be-project-nexus/lib
bash ./mvnw -pl common-outbox -am verify
```

PostgreSQL 18 + real Kafka acceptance checks require Docker, Java 21 and Maven-wrapper
network/cache access. Use the platform-specific script:

Linux/macOS (Bash):

```bash
bash be-project-nexus/lib/common-outbox/verify-integration.sh
```

Windows (PowerShell):

```powershell
& .\be-project-nexus\lib\common-outbox\verify-integration.ps1
```

The Windows script invokes `mvnw.cmd`; start Docker Desktop with Linux containers first.
Pass `-KafkaPort 29094` when a fixed, available broker port is needed; otherwise the
script selects a test-only port. If the execution policy blocks a repository script, run
PowerShell with `-ExecutionPolicy Bypass` for that one invocation rather than changing
the machine-wide policy.

Each script creates uniquely named disposable PostgreSQL/Kafka containers using
the project's existing image versions, selects test-only ports, and removes its
containers/volumes on exit. It stops and restarts **only its own Kafka container**
for the outage test. Existing local development containers and databases are not
changed. `OUTBOX_TEST_KAFKA_PORT` can select the test broker's host port.

The `outbox-integration` Maven profile runs Failsafe `OutboxIT`; missing fixture
environment variables fail the suite rather than silently skipping it. It checks:

- All six baseline outbox definitions match; the fixture loads the actual DDL.
- Calling the writer outside a transaction fails without inserting a row.
- A JPA business write and JDBC outbox insert roll back together.
- A business transaction commits with Kafka stopped; a failed relay leaves the
  row pending; restart plus the next run publishes the unchanged envelope.
- Two independent application contexts hold concurrent, disjoint 100-row claims
  against 200 rows. A database audit trigger observes one published update per
  row, and a raw Kafka consumer reads 200 records, not merely 200 deduped IDs.
- Earlier successful rows commit on a later send failure; the next run sends
  only remaining rows.
- A DB failure after a real Kafka acknowledgement allows duplicate delivery with
  the same event ID, exercising the documented at-least-once boundary.
- Boot auto-configuration starts and the real scheduled task publishes a row.
- With 10,000 rows, exactly 5 unpublished, `ANALYZE outbox` followed by
  `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` on the actual claim query selects
  `idx_outbox_unpublished`, without disabling sequential scans.

Reports: `common-outbox/target/{surefire-reports,failsafe-reports}`.
Query-plan evidence: `common-outbox/target/outbox-explain.json`.
