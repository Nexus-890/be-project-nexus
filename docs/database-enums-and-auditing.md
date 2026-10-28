# PostgreSQL enums and actor auditing

The six business databases use native PostgreSQL enums for 27 status/type columns.
The changes ship as new Flyway migrations; previously applied migrations are unchanged.

| Service | Enum migration | Audit migration |
| --- | --- | --- |
| user | V4 | V5 |
| catalog | V5 | V6 |
| commerce | V4 | V5 |
| auction | V3 | V4 |
| notification | V2 | V3 |
| inventory | V4 | V5 |

Inventory retains the existing `V3__drop_shipment_tables.sql` migration from the service
split. Its new enum and audit migrations cover only warehouse and inventory tables;
the shipment tables removed by V3 are not recreated or altered.

Commerce reserves V3 for the payment-service split in a separate change; that
`V3__payment_moved.sql` is not included here. V1/V2 stay unchanged, and V4/V5 exclude
`payment_transactions` and its enum types. The legacy payment table remains until
the separate split removes it. These migrations neither move payment data nor
change the payment-service schema.

Recheck the next available version per service when merging concurrent schema work.
Enums cover users and reputation, products and SKUs, carts/orders/order history,
the six promotion statuses, auctions/bids/visibility, notification channels/delivery
statuses, and inventory status/movement columns. Unrestricted names, event types,
provider names and promotion configuration strings remain text.

The promotion status catalogue was not defined by the old schema or Java code.
[Commerce persistence](../commerce-service/README.md) lists the initial allowed values.
An existing value outside a declared enum causes migration rollback; statuses are never
silently rewritten. Future allowed values must be added in a new migration.

## Actor semantics

All 36 business tables covered by these migrations have non-null UUID `created_by`
and `updated_by` columns. The legacy commerce payment table is outside this scope.
`AuditedEntity` supplies `@CreatedBy`/`@LastModifiedBy` and the JPA entity listener.
`JpaAuditingAutoConfiguration` enables auditing for services with an entity manager.
`CurrentUserAuditor` resolves the authenticated `CurrentUser` or resource-server JWT.
Anonymous writes and background consumers use `00000000-0000-0000-0000-000000000000`.
The database defaults use the same UUID for raw JDBC consumer inserts.
Notification consumer JPA writes use the shared auditor and the same system actor.
The cart expiry job explicitly records that actor in its native update.

Historical actors that were not recorded use the system UUID. Existing promotion
authors are preserved, and existing product creators are backfilled from `seller_id`.
JPA keeps the creator immutable and records the current editor on update. Native
cart/item writes explicitly supply the authenticated actor because they bypass JPA listeners.
Any future native/JDBC update must also set `updated_by` explicitly.

Administrative roles/permissions and their joins, `outbox`, `processed_events`, and
`shedlock` retain their existing schemas. Services without business JPA entities
currently use the migrated schema and database defaults.

Java enum fields use `@Enumerated(EnumType.STRING)` and
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` with an explicit PostgreSQL type definition.
SKU and cart statuses are Java enums; response JSON retains the same string values.
Native SQL binding a text enum parameter must use `CAST(:status AS <enum_type>)`.
Current production enum filters use mapped JPQL/derived queries or fixed SQL literals.

## Verification

Run with Java 21, Maven and Docker available, from the repository root:

```powershell
mvn -f be-project-nexus/lib/pom.xml install
mvn -f be-project-nexus/user-service/pom.xml verify
mvn -f be-project-nexus/catalog-service/pom.xml verify
mvn -f be-project-nexus/commerce-service/pom.xml verify
mvn -f be-project-nexus/auction-service/pom.xml verify
mvn -f be-project-nexus/notification-service/pom.xml verify
mvn -f be-project-nexus/inventory-service/pom.xml verify
mvn -f be-project-nexus/infra/db-verification/pom.xml verify
```

The integration suites create disposable PostgreSQL 18 containers. They verify fresh
schemas, upgrades with existing rows, invalid enum INSERT rejection (`22P02`), defaults,
Hibernate `ddl-auto=validate`, enum persistence, creator/editor/system auditing, and
seller creation with `created_by = 68000000-0000-4000-8000-000000000002`.
The user-service database tests now use PostgreSQL Testcontainers instead of H2.
The registration HTTP suite also checks production auditing auto-configuration.

Before the inventory service split, validation on 2026-10-23 completed 345 tests:
343 passed, no failures/errors, and two
existing provider-dependent webhook tests were skipped. The user HTTP integration
suite starts the complete application with validation enabled. The other five actual
applications also started against isolated PostgreSQL 18.6 databases with
`ddl-auto=validate`; each returned HTTP 200 and `UP` from `/actuator/health`.
Temporary application processes and containers were stopped after verification.

Before the commerce payment exclusion, merge verification on 2026-10-28 completed
the commands above against `develop` at `05330c8`, including the inventory service
split and notification consumer. Across
the shared library tests and service/schema suites, 381 tests were reported:
372 passed, no failures/errors, and nine were skipped (two provider-dependent
webhook tests and seven environment-gated idempotent-consumer tests). All builds
succeeded. PostgreSQL regression tests also cover the cart expiry system actor,
notification insert/update auditing, and inventory upgrades from V3 to V5.

After excluding commerce payment and renumbering its migrations to V4/V5,
`verify` passed for commerce, notification and `infra/db-verification` against
`develop` at `2b19e2e`: 94 tests reported, 92 passed, two existing provider-dependent
webhook tests skipped, and no failures/errors. Commerce upgrade tests cover both
the legacy payment table and its absence. Notification starts the full application
with shared auditing and Hibernate validation to check the merged configuration.

`infra/db-verification` checks every expected enum/audit column across all six schemas,
compares business rows before and after upgrading, and checks outbox relay plans.
It includes connected legacy fixtures for all commerce promotion workflows,
auction/bid records, notification delivery attempts, and inventory records. Inventory
upgrades start from V3, preserving the existing warehouse, balance, reservation and
movement rows while keeping the removed shipment tables absent.

EXPLAIN checks confirm that the existing `idx_products_category`,
`idx_carts_one_active`, and `idx_outbox_unpublished` indexes support the requested queries.
No duplicate index migration was needed. See the
[catalog query plan](../catalog-service/docs/query-plans.md) and commerce README.

To inspect a migrated database:

```sql
SELECT table_name, column_name, udt_name
FROM information_schema.columns
WHERE table_schema = 'public' AND data_type = 'USER-DEFINED'
ORDER BY table_name, column_name;
```
