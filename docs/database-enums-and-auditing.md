# PostgreSQL enums and actor auditing

The six business databases use native PostgreSQL enums for 31 status/type columns.
The changes ship as new Flyway migrations; previously applied migrations are unchanged.

| Service | Enum migration | Audit migration |
| --- | --- | --- |
| user | V4 | V5 |
| catalog | V5 | V6 |
| commerce | V3 | V4 |
| auction | V3 | V4 |
| notification | V2 | V3 |
| fulfillment | V3 | V4 |

Recheck the next available version per service when merging concurrent schema work.
Enums cover users and reputation, products and SKUs, carts/orders/order history/payments,
the six promotion statuses, auctions/bids/visibility, notification channels/delivery
statuses, and fulfillment status/movement columns. Unrestricted names, event types,
provider names and promotion configuration strings remain text.

The promotion status catalogue was not defined by the old schema or Java code.
[Commerce persistence](../commerce-service/README.md) lists the initial allowed values.
An existing value outside a declared enum causes migration rollback; statuses are never
silently rewritten. Future allowed values must be added in a new migration.

## Actor semantics

All 39 business tables have non-null UUID `created_by` and `updated_by` columns.
`AuditedEntity` supplies `@CreatedBy`/`@LastModifiedBy` and the JPA entity listener.
`JpaAuditingAutoConfiguration` enables auditing for services with an entity manager.
`CurrentUserAuditor` resolves the authenticated `CurrentUser` or resource-server JWT.
Anonymous writes and background consumers use `00000000-0000-0000-0000-000000000000`.
The database defaults use the same UUID for raw JDBC consumer inserts.

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
mvn -f be-project-nexus/user-service/pom.xml test
mvn -f be-project-nexus/catalog-service/pom.xml test
mvn -f be-project-nexus/commerce-service/pom.xml test
mvn -f be-project-nexus/auction-service/pom.xml test
mvn -f be-project-nexus/notification-service/pom.xml test
mvn -f be-project-nexus/fulfillment-service/pom.xml test
mvn -f be-project-nexus/infra/db-verification/pom.xml test
```

The integration suites create disposable PostgreSQL 18 containers. They verify fresh
schemas, upgrades with existing rows, invalid enum INSERT rejection (`22P02`), defaults,
Hibernate `ddl-auto=validate`, enum persistence, creator/editor/system auditing, and
seller creation with `created_by = 68000000-0000-4000-8000-000000000002`.
The user-service database tests now use PostgreSQL Testcontainers instead of H2.
The registration HTTP suite also checks production auditing auto-configuration.

Validation on 2026-10-23 completed 345 tests: 343 passed, no failures/errors, and two
existing provider-dependent webhook tests were skipped. The user HTTP integration
suite starts the complete application with validation enabled. The other five actual
applications also started against isolated PostgreSQL 18.6 databases with
`ddl-auto=validate`; each returned HTTP 200 and `UP` from `/actuator/health`.
Temporary application processes and containers were stopped after verification.

`infra/db-verification` checks every expected enum/audit column across all six schemas,
compares business rows before and after upgrading, and checks outbox relay plans.
It includes connected legacy fixtures for all commerce promotion workflows,
auction/bid records, notification delivery attempts, and fulfillment records.

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
