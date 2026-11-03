# Category events through the outbox (#106)

`CategoryService` writes `CategoryCreated`, `CategoryUpdated` and `CategoryDeleted`
inside the existing category transaction. Created/Updated carry the saved snapshot
(`categoryId`, `name`, `slug`, `parentId`); Deleted carries only `categoryId`.
Both hard and soft deletion emit Deleted after their guards and database writes
succeed. Guard failures and transaction rollback produce no committed event.

The database outbox stores the complete `EventEnvelope`. The nested `payload`
holds the category fields. `aggregate_type` is `Category`, `event_version` is `1`,
and topic is `nexus.catalog.events`. The relay uses `categoryId` as the Kafka key.
The service makes no direct Kafka send and uses the existing DataSource and JPA
transaction manager for both JPA and JDBC writes.

## Run the checks

Use Java 21 and Docker Desktop with Linux containers. From the repository root:

```powershell
Push-Location be-project-nexus/lib
.\mvnw.cmd install -DskipTests
Pop-Location

Push-Location be-project-nexus/catalog-service
.\mvnw.cmd -Dtest=CategoryOutboxIntegrationTest test
.\mvnw.cmd verify
Pop-Location
```

The integration fixture creates its own PostgreSQL 18 and Kafka 4.3.1 containers;
it does not stop the development broker or use the development `catalog_db`.
Kafka's host port remains stable across the stop/start exercise. Containers are
removed after the suite. Reports are in `catalog-service/target/surefire-reports`.

`CategoryEventSchemas` loads the JSON schemas directly from `docs/api/events.md`
and enables format assertions, including UUID validation. The validator is a
test-only dependency; there is no duplicate schema in test resources.

## Acceptance evidence exercised by the tests

| Criterion | Automated check |
|---|---|
| Kafka stopped: create returns 201 and one pending row | Stops only the fixture broker, calls the real controller through MockMvc with the real Spring-proxied service, and queries the committed category/outbox rows. A failed relay run leaves the row pending. |
| Business failure rolls back the outbox | Verifies both INSERTs are visible inside an outer transaction, throws, and queries again after rollback: neither row remains. |
| Create, update, delete publish three messages in order | Restarts the broker, updates/deletes the same category, runs the real relay, and consumes exactly three raw records. Checks event types, the common category key/partition and increasing offsets. No deduplication hides extra records. |
| Every Kafka payload validates | Validates each raw consumed message's payload against its schema in events.md. Also verifies that retry preserves the original Created envelope and Updated contains the new name. |

Additional cases cover root `parentId: null`, child creation, moving a child to
root, hard/soft deletion, child/product guards, duplicate slugs, and a rejected
outbox INSERT rolling back the category. A negative validator test confirms
missing parentId and malformed UUIDs are rejected.

The test replaces the active trace context with a deterministic fixture; JPA,
JDBC, the writer, auto-configuration, Kafka producer and raw consumer are real.
The relay scheduler has a long initial delay in this test so explicit relay calls
control the outage/recovery assertions. This is not a security or gateway test.

## Verified run

Fresh audit on 2026-10-30, Java 21 and UTC: `mvnw.cmd verify` passed again:
**55 tests, 0 failures, 0 errors, 0 skipped**, including all 10 real PostgreSQL/
Kafka category outbox integration cases. No production code change was needed.
The branch was checked in an isolated Git worktree to preserve other issue files.

On 2026-10-29 with Java 21 and Docker Desktop:

- `catalog-service/mvnw.cmd verify`: **55 tests, 0 failures, 0 errors, 0 skipped**,
  including all **10 CategoryOutboxIntegrationTest** cases.
- The packaged catalog JAR started against a separate PostgreSQL 18 database
  with Flyway migrations, `ddl-auto=validate` and the outbox relay enabled;
  `/actuator/health` returned `UP`.
- The standalone startup smoke used a separate local configuration with Config
  Server/discovery disabled and relay scheduling delayed. It used JVM timezone
  UTC because PostgreSQL 18 rejects the Windows `Asia/Saigon` timezone alias.
  No development database or service configuration was changed.

## Manual check

Start the normal local stack and catalog-service, then stop Kafka after the
service has started. Send an authenticated POST to `/api/v1/categories` with a
fresh slug. The response is HTTP 201 and the category ID is in `data.id`.

Query `catalog_db`, substituting that UUID:

```sql
SELECT id, name, slug FROM categories WHERE id = 'CATEGORY_UUID'::uuid;

SELECT id, event_type, topic, published_at, jsonb_pretty(payload)
FROM outbox
WHERE aggregate_id = 'CATEGORY_UUID'
ORDER BY occurred_at;
```

There must be one Created row with `published_at IS NULL`. Restart Kafka; after
a successful relay run that same row has a publication timestamp. PATCH the name
and DELETE the same category. Observe Created, Updated, Deleted on the catalog
topic with the same key and increasing offsets within one partition. Database
sorting alone does not prove Kafka delivery order. Delete returns HTTP 204.

## Existing relay limits

The current common-outbox relay is at-least-once: a crash between Kafka ACK and
the database commit can cause a retry with the same eventId. Consumers must be
idempotent.

This issue's transport test exercises one relay. The existing relay's
`FOR UPDATE SKIP LOCKED` batch claims do not guarantee per-aggregate order across
multiple relay instances. The category key selects a partition but cannot repair
events sent in a different order by different producers. Cross-instance ordering
requires a shared relay design change; it is not established by this test. See
`lib/common-outbox/README.md` for the existing semantics.
