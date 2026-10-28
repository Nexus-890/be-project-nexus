# Commerce persistence

`V4__enum_types.sql` converts cart, order, order-history and the six promotion
status columns to PostgreSQL enums. `V5__audit_columns.sql` adds actor UUIDs to
the 13 cart, order and promotion business tables and preserves existing promotion
authors. Historical rows use the system actor `00000000-0000-0000-0000-000000000000`
where no author was stored. Infrastructure tables (`outbox`, `processed_events`,
`shedlock`) are excluded.

V1 and V2 are unchanged. Flyway V3 is reserved for the payment-service split in a
separate change; its `V3__payment_moved.sql` is not included here. These enum and
audit migrations do not alter `payment_transactions`, create payment enums, or
move payment data. Until the split is applied, the legacy payment table created
by V1 remains with its existing schema.

V1 defined promotion statuses as unrestricted text and this checkout contains no
promotion Java enums or canonical status catalogue. The migration establishes these
explicit initial contracts:

| Table | Allowed status values |
| --- | --- |
| `promo_mega_sale_events` | `DRAFT`, `REGISTRATION_OPEN`, `REGISTRATION_CLOSED`, `ACTIVE`, `ENDED`, `CANCELED` |
| `promo_vouchers` | `DRAFT`, `ACTIVE`, `PAUSED`, `EXPIRED`, `CANCELED` |
| `promo_event_sellers` | `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN` |
| `promo_customer_vouchers` | `CLAIMED`, `USED`, `EXPIRED` |
| `promo_voucher_sellers` | `ACTIVE`, `WITHDRAWN` |
| `promo_voucher_usages` | `RESERVED`, `REDEEMED`, `RELEASED`, `EXPIRED`, `REVERSED` |

Any existing value outside these contracts causes the migration to fail atomically;
the migration never rewrites a status or silently accepts unknown labels. Review
these initial promotion contracts alongside the promotion workflow specification
before applying them to a database with promotion data. Future additions belong
in new migrations.

Run `mvn test` with Docker running. `CartPersistenceTest` uses a disposable
PostgreSQL 18 container, runs the production migrations, validates the Hibernate
mapping and verifies JPA/native auditing. It also checks migration preservation
and PostgreSQL rejection of unknown enum values.

The EXPLAIN checks load 10,000 rows and confirm that active-cart lookup uses
`idx_carts_one_active`, and the unpublished outbox relay uses
`idx_outbox_unpublished` with `ORDER BY occurred_at LIMIT 20 FOR UPDATE SKIP LOCKED`.
Those existing indexes cover these queries, so no additional index is required.
