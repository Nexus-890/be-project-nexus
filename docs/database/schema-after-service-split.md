# Database Schema After the Service Split — Project Nexus

**Issue:** [#250 — Schema, ERD and data dictionary after the service split](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/250).  
**Updated:** 24 October 2026, Asia/Saigon time zone.  
**Source baseline:** `develop`, commit `8f5276388d0274bb9ec0d9f3bdbae9dc2421c960`.  
**Status:** target specification updated with the user's decisions of 24 October 2026 and checked against the SRS/BR. The R1 design uses checkout as the purchase flow, order_groups as the stored purchase group, and one shared payment session per group, following the user and leader decisions. Promotion enums/rules and the separate item-return eligibility/API contract await reviewer confirmation; this documentation task has not executed the target migrations.

This document specifies every application-owned table after the service split: fields, PostgreSQL types, nullability, defaults, keys, constraints, indexes, relationships, lifecycle rules and the layer that enforces each business rule. It distinguishes user decisions, requirements found in code/SRS/BR, and implementation choices made in this design. Pending reviewer decisions cover promotion enums/rules and the separate item-return eligibility/API contract. Full-order cancellation compensation and selective item returns are distinct flows; the selected shared-payment design does not reinterpret an automatic cancellation refund as an item-return request. New implementation choices are not presented as verbatim SRS requirements.

## Reading Guide and Scope

The document uses three labels:

| Label | Meaning |
|---|---|
| **Existing / K** | Present in migrations or code at the baseline above. |
| **Required / I** | A change or behavior specified by a source issue; its target migration may not exist yet. |
| **Design choice / P / D** | An implementation detail selected within the user's delegated scope; it does not imply that a migration exists. **PENDING REVIEWER** identifies unresolved promotion enums/rules and the separate item-return eligibility/API contract; the user has selected multi-shop checkout with a stored order group and per-order payment instructions. |

- The type column includes length/precision/scale, for example `varchar(100)` and `numeric(19,2)`. `—` or “None” in DEFAULT means **no declared default**, not a text value stored in the database.
- `NULL? Yes` identifies an optional column; both audit actor columns use NOT NULL and the system UUID default.
- Requiring a new column does not mean that its proposed type/default/CHECK has already been approved in the source issue. Read the source and status in the relevant table.
- Applied migrations remain unchanged. This document describes the target schema; it neither edits V1/V2 nor executes DDL against a running database.
- Business data is organized into nine application databases. `auth_db` belongs to `auth-service`, which runs Keycloak: the document specifies ownership and identity contracts without redesigning Keycloak's internal schema.
- `api-gateway`, `config-service` and `discovery-service` do not acquire business tables merely because they expose endpoints/configuration. #217 continues to store each service's keys in its Config Server file; future persistence requires a separate decision.

**Terminology:** checkout is the buyer operation that validates the cart, confirms pricing/shipping and places the purchase. An **order group** is the stored aggregate in `commerce_db.order_groups`; references use `order_group_id` in the database and `orderGroupId` in payloads. Each shop has a child order, and `payment_sessions` stores the group's one shared payment request. `CheckoutPaymentRequested` and `CheckoutPaymentConfirmed` remain proposed event names for this checkout flow; their payloads identify the order group and its child instructions.

### Navigation

- [Common conventions](#common-rules)
- [auth-service / auth_db](#auth-db)
- [user-service / user_db](#user-db)
- [catalog-service / catalog_db](#catalog-db)
- [commerce-service / commerce_db](#commerce-db)
- [auction-service / auction_db](#auction-db)
- [inventory-service / inventory_db](#inventory-db)
- [payment-service / payment_db](#payment-db)
- [shipment-service / shipment_db](#shipment-db)
- [rating-service / rating_db](#rating-db)
- [notification-service / notification_db](#notification-db)
- [Source-to-target mapping and migration handoff](#migration-map)
- [Applied decisions and pending review](#review-decisions)

### Target Inventory

The 24 October review baseline below contains **66 application-owned tables: 43 business tables and 23 technical tables**, with **719 field definitions**. The #257 addendum adds four business tables (`seller`, `seller_bank`, `payouts`, `payout_history`); this historical baseline count excludes those additions and later completion/eligibility projections. These counts include proposed fields/tables; they do not describe what is already deployed. Keycloak's internal tables are excluded.

| Database | Business tables | Technical tables | Total tables | Field definitions |
|---|---|---|---|---|
| `user_db` | 1 | 3 | 4 | 33 |
| `catalog_db` | 5 | 2 | 7 | 67 |
| `commerce_db` | 14 | 3 | 17 | 213 |
| `auction_db` | 3 | 3 | 6 | 63 |
| `inventory_db` | 4 | 3 | 7 | 62 |
| `payment_db` | 4 | 2 | 6 | 79 |
| `shipment_db` | 2 | 2 | 4 | 44 |
| `rating_db` | 4 | 2 | 6 | 72 |
| `notification_db` | 6 | 3 | 9 | 86 |

### Sources

| Scope | Source |
|---|---|
| Documentation requirements | [#250](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/250), current description read on 23 October 2026. |
| Architecture / provisioning | [#234](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/234), [#235](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/235), local ADR-009. |
| Auth/profile | #236, #238, #211; user V1 and V3; existing User/Gender/UserStatus. |
| Schema corrections | [#66](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/66), [#67](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/67), [#249](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/249). |
| Payment/refund | #240, #120, #121, #155, #156, #157, #207, #247; commerce V1/V2; ADR-007; user/leader-selected order-group/session/instruction design of 24 October 2026. |
| Shipping | #241, #201, #202, #203; fulfillment V1/V2. |
| Rating | #242, #212, #213; user V1; OrderCompleted contract. |
| Inventory/auction | #243, #130, #131, #134, #136, #199, #200, #140, #146, #147; fulfillment/auction V1/V2. |
| Commerce | #113, #116, #188, #240, #157, #249; commerce V1/V2; user-selected multi-shop checkout extension of 24 October 2026. |
| Catalog | V1/V2/V3/V4, catalog code; #67, #249, #215, #216. |
| Notification/log/health | #107, #191, #192, #193, #249; notification V1. |
| Communication | #186, #246, `docs/api/events.md`, and the applicable endpoint/gateway contracts. |

Issue numbers without individual URLs refer to the same project as #250. The OUTPUT sections and relevant rules of these issues were checked during this work, rather than inferred from their titles. The local six V1 schemas contain **60 tables and 516 columns** before the new split. Commerce V2 adds deadline/memo/index definitions; catalog/auction V2 correct money/uniqueness; fulfillment V2, user V3 and catalog V3/V4 contain seed data within the reviewed scope. The target is not forced to retain 60 tables because auth tables are removed and new tables are required.

**Resolving source differences:** existing migrations describe the baseline data; current split issues specify target ownership. For example, #235 now uses `auth-service`/`auth_db`, whereas local ADR-009 still uses `keycloak_db` and the Proposed status. The target uses the current issue names and records the difference. Older “bigint VND” descriptions must not reverse the approved `numeric(19,2)` corrections in #66/#67. Unresolved decisions remain explicitly listed for review rather than being marked agreed.

### Additional Code and Source Review on 24 October 2026

- Audit/enum implementation: local ref `origin/fsa_ChungTQ/chore/249-db-postgresql-enum-types-for-status-columns-and-audit-columns`, commit `0bc9da1`. It is not merged into the `develop` baseline; the user selected this existing implementation style for the target schema.
- `CurrentUserAuditor.SYSTEM_ID` = `00000000-0000-0000-0000-000000000000`; JPA AuditedEntity uses @CreatedBy/@LastModifiedBy, both nullable=false. Audit migrations assign the same system UUID default. This is a technical actor, not a user account that must exist.
- SRS: `D:/KyOJT/TaiLieu/srs-nexus-ecommerce-auction-v1.docx`, v1.0; primary BR workbook: `D:/KyOJT/Bussiness Rule.xlsx`, worksheet `Trang tính1`; cross-check: `D:/KyOJT/TaiLieu/SQL_ERD_BR_Review.xlsx` (BR_Review/BR_Source/BR_Tieng_Viet).
- Precedence: explicit user decisions → current service split/issues → SRS/BR → baseline code details. The SePay decision in ADR-007 replaces the SRS default STRIPE; auth/ownership follows #234–#243. The user's decision not to add full-text schema is recorded separately and does not complete #215.

<a id="common-rules"></a>

## Common Target Schema Conventions

### Types, Keys and Time

- Preserve `uuid` and each existing writer's generator: existing user/reputation/rating entities use Hibernate VERSION_7; category/product/native cart writers and EventEnvelopeFactory use UUID.randomUUID(); SKU/image entities use GenerationType.UUID/@UuidGenerator without forcing VERSION_7. Preserve the source DB default uuidv7() for INSERTs that omit an ID; new tables/services use the source default until a writer exists. After #238, `users.id` comes from Keycloak; natural/composite keys do not gain an extra generated UUID. Do not convert all application IDs to a new UUID version.
- Money uses `numeric(19,2)` and Java BigDecimal under #66/#67; preserve each column's nonnegative CHECK. Percentages use `numeric(5,2)` where the source specifies it. JSON wire formats do not imply different SQL types.
- Timestamps use `timestamptz`; exchange ISO-8601 values with an offset/UTC. `date` is a calendar date, not a timestamp. `DEFAULT now()` runs only on INSERT; updates to `updated_at` require an explicitly defined writer/JPA/trigger behavior.
- Text lengths, nullability and defaults are specified per table. The user permits email/phone reuse after soft deletion, so users changes to partial UNIQUE indexes for active records; other unique rules remain as sourced unless a specific rule requires a change.
- `version bigint` provides optimistic locking only when every writer increments/checks it correctly. Do not add it to every table or assume the column alone makes concurrent updates safe.
- JSONB validates JSON syntax, not the business schema. Long-lived snapshots need an agreed schema/version and event source; JSONB does not preserve raw HTTP key order or whitespace.

### UUID Generation by Existing Backend Writer

| Table/group | Existing backend writer | DB default / target rule |
|---|---|---|
| users after auth split | Keycloak ID; no new local ID | Remove the users.id generator/default in #238. |
| user_reputations, ratings, user outbox | Hibernate UuidGenerator VERSION_7 | Preserve source UUIDs/default uuidv7() for INSERTs that omit the ID. |
| categories, products | UUID.randomUUID() in service/entity | Preserve the writer; DB default uuidv7() is a fallback and does not prove app-generated IDs are v7. |
| skus, product_images | GenerationType.UUID | Preserve the provider generator without forcing VERSION_7. |
| carts, cart_items | Native flow UUID.randomUUID(); entity UuidGenerator without VERSION_7 | Preserve supplied UUIDs; do not replace all cart IDs. |
| product_stats | @MapsId from product_id | No independent UUID. |
| EventEnvelopeFactory, commerce demo outbox | UUID.randomUUID() | Keep eventId stable across retries; never regenerate it in the relay. |
| Auction/inventory/payment/shipment entities absent from baseline | No business writer from which to infer the app UUID version | Use source/default uuidv7() for a PK when no ID is supplied; this is a new design choice, not existing application code. |
| rating_eligibility, processed_events, join tables | Reference/composite keys | Use order/event IDs supplied by the source; avoid unnecessary surrogate IDs. |

### Foreign Keys and Cross-Service Relationships

- Every business table has exactly one service with write ownership. References to another service's entities use logical UUIDs, without cross-database `REFERENCES`; services must not read/write each other's databases.
- Retain local FKs when both tables share an owner. Where DDL omits actions, the behavior is `ON DELETE NO ACTION ON UPDATE NO ACTION`, not an implicit CASCADE. Baseline FKs are not declared deferred.
- Cardinalities use 0..1, 1 and 0..N. FK + NULL + UNIQUE determine physical cardinality; PostgreSQL does not automatically validate the existence of logical references.
- An old FK crossing a new boundary may be replaced by a **local** FK to a projection with a UNIQUE key in the target DB, but that is a new design choice and requires verified backfill.
- `NOT VALID` on an FK/CHECK only skips the existing-data scan when the constraint is created; PostgreSQL still checks new INSERTs/UPDATEs. Run `VALIDATE CONSTRAINT` after backfill/verification; NOT VALID must not be treated as permission for invalid new rows.

### Enums and Auditing Under #249

- Preserve the actual type names from the #249 implementation branch: user_status/gender, product_status/sku_status, cart_status/order_status/order_type, auction_status/auction_visibility/bid_status, notification_channel/notification_delivery_status/notification_attempt_status, warehouse_status/inventory_reservation_status/inventory_movement_type, trust_level and payment_transaction_status/payment_transaction_type. Add values required by later issues, such as UNRECONCILED and PENDING_TRACKING, in their new owners. Promotion enums remain PENDING REVIEWER.
- **User decision:** every business table has `created_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid` and the same definition for `updated_by`. Do not add these columns to outbox/processed_events/shedlock.
- Under CurrentUserAuditor/AuditedEntity in #249, authenticated requests use CurrentUser/JWT user ID; anonymous self-registration and consumers/jobs without an authenticated principal use the system UUID. Set both actors on creation; do not change created_by on UPDATE. Actor columns have no FK to users/auth_db.
- Native/JDBC INSERTs must supply the correct actor or use the system default; native/JDBC UPDATEs must explicitly set updated_by because neither JPA listeners nor SQL defaults run automatically for those updates.
- Follow #249 backfill code: historical products use created_by=seller_id; promotions retain existing created_by and derive updated_by through COALESCE(creator,system); unknown actors use the all-zero system UUID. System backfill records technical provenance, not a fabricated user attribution. Apply NOT NULL/default after backfill; nullable audit columns are not part of the target.
- Apply this to product_stats, eligibility, history and new business tables. Do not automatically add created_at/updated_at to source tables lacking them when #249 only requires actor columns.

### Transactions and Idempotency

Related business rows, outbox rows and processed markers must commit within **one DB** transaction. Kafka, Keycloak Admin API and carrier APIs are outside that PostgreSQL transaction. Consumers deduplicate on `(event_id, consumer)`; domain keys must also protect order/provider transaction/idempotency identities when different envelopes describe the same operation. Commit the marker with the business changes only after the handler succeeds; a marker may be inserted/claimed at transaction start to block concurrent duplicates, but must roll back if processing fails. Retry/timeout/threshold settings belong to service validation/runtime policy, not fixed CHECK constants that become stale when configuration changes.

<a id="auth-db"></a>

## auth-service / auth_db

**Ownership:** auth-service is a container running the Keycloak image under the current OUTPUT of #235; its database is `auth_db`. Keycloak owns credentials, password policy, brute-force lockout, realm/client roles, privileges, sessions, refresh tokens and password reset under #236/#238. Keycloak manages its internal tables and migrations; copying the six user_db V1 auth tables into auth_db would not define a valid Keycloak schema.

| Integration contract | Target definition | Source |
|---|---|---|
| Realm | `nexus` | #236 |
| Web client | `nexus-web`, public client, PKCE | #236 |
| Realm roles | `ADMIN`, `SELLER`, `BUYER`, `SUPPORT_STAFF` | #236 |
| Privileges | The 65 SRS 2.7 privileges represented as client roles, assigned through the approved matrix | #236; do not recreate a permissions table in user_db |
| Token mapping | `sub`, flat `roles` and `privileges` claims; audience `nexus-services` | #236/#237 |
| Profile ID | `user_db.users.id` equals the Keycloak user ID under the project's UUID contract | #238 |
| Registration | Create account, assign BUYER, store profile; compensate if a later step fails | #238; no cross-DB transaction |
| User administration | Role/password/unlock operations call Keycloak Admin API; user-service retains profile ownership | #211/#238 |
| Demo identities | Retain the fixed IDs referenced by business seeds; do not break seeds by creating new identities | #236/#68/#69 |

Business databases have no physical FK to Keycloak tables. Preserve IDs during account migration; a user registering again receives a new ID. Keep last_login_at; retain LOCKED because it exists in V1, while brute-force counters/lockout remain in Keycloak. The profile/Keycloak workflow synchronizes locking, contact tombstones and LOGIN projections as described below; those adapters are new implementation work, not code already present at the baseline.

<a id="user-db"></a>

## user-service / user_db

Target scope: profiles and service-local technical tables only. Authentication belongs to `auth-service`, running Keycloak on `auth_db` (#235, #236, #238). `users.id` must equal the Keycloak account ID; do not generate a separate profile ID and then add an unnecessary mapping.

Sources checked: `user-service/src/main/resources/db/migration/V1__init.sql`, V3 account/role seeds, the `User` entity, `UserStatus`/`Gender`, #238, #211 and #249. V3 adds no tables/columns. Technical tables are specified in their dedicated sections below.

Source notation: “Retain V1” refers to the baseline structure; auth changes follow #238; actors/enums follow #249 implementation commit 0bc9da1, not yet merged into develop. user_status=(ACTIVE,LOCKED,DELETED) and gender=(MALE,FEMALE,OTHER) are the actual names/domains in that branch. Changing the owner or target migration does not itself change the enum domain.

### user_db.users

Purpose: account contact and personal profile data. Retain the V1 table, remove password/lockout details under #238 and add audit actors under #249.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | No default; use Keycloak Admin API ID | Profile ID, token subject and Keycloak account ID | PK; target #238. **Implementation choice:** remove existing `DEFAULT uuidv7()` and the JPA ID generator to avoid creating a different identity. |
| `email` | `varchar(255)` | No | None | Profile contact email | Required; UNIQUE only where deleted_at IS NULL; lowercase/trim in every writer; reuse after soft deletion is permitted by the user. |
| `phone` | `varchar(20)` | No | None | Contact phone number | Required; UNIQUE only where deleted_at IS NULL; normalize consistently before storage; reuse after soft deletion is permitted. |
| `full_name` | `varchar(150)` | No | None | Display name | Retain V1; service validates input. |
| `identity_number` | `varchar(255)` | Yes | None | Identification information in the existing profile model | Retain V1; no separate UNIQUE/CHECK exists and none is added implicitly by this split. |
| `gender` | `gender` | Yes | None | Gender: `MALE`, `FEMALE`, `OTHER` | Type gender follows #249 code; baseline varchar(10) + CHECK has the same values. |
| `address` | `text` | Yes | None | Profile address | Retain V1; does not replace the order's address snapshot. |
| `date_of_birth` | `date` | Yes | None | Date of birth | Retain V1; no birth-date CHECK in the baseline. |
| `status` | `user_status` | No | `'ACTIVE'::user_status` | Profile state: `ACTIVE`, `LOCKED`, `DELETED` | Retain all three values: LOCKED exists in V1 and #249 user_status. Keycloak still manages brute-force locking. |
| `last_login_at` | `timestamptz` | Yes | None | Latest successful Keycloak LOGIN time | Retained by user decision; NULL before first login. A trusted LOGIN adapter updates max(existing,event.occurredAt); reading a profile is not a login event. |
| `created_at` | `timestamptz` | No | `now()` | Profile creation time | Retain V1; DB INSERT default does not change on UPDATE. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Latest profile update time | Retain V1; JPA/service updates it; no automatic baseline trigger. |
| `deleted_at` | `timestamptz` | Yes | None | Soft-deletion time | Retain V1; `USER_SOFT_DELETE` under #211 must coordinate with Keycloak. |
| `version` | `bigint` | No | `0` | Profile optimistic-lock version | Retain V1 and JPA `@Version`. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creator actor; immutable after INSERT | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Latest writer/update actor; append-only history is not rewritten | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |

Constraints and indexes:

- Retain PK `(id)`. Drop the two source whole-table UNIQUE constraints before creating `CREATE UNIQUE INDEX uq_users_email_active ON users(email) WHERE deleted_at IS NULL` and `uq_users_phone_active ON users(phone) WHERE deleted_at IS NULL`. Both are partial UNIQUE B-tree indexes: deleted profiles may share contact details, but only one nondeleted profile may hold each contact value.
- Retain `idx_users_status (status, deleted_at)`. Repository duplicate checks must filter deleted_at IS NULL and exclude the current ID on UPDATE. The unique index resolves concurrent races; the service maps uniqueness violations to conflicts.
- Preserve the existing lowercase/trim email normalization; this change does not switch to citext or add a lower(email) index. Use one canonical phone normalization. Restoring a deleted profile must check whether a new account has reused its contacts.
- No physical FK to `auth_db`. The logical relationship is one Keycloak account ↔ at most one profile with the same UUID. #238 creates the account, assigns BUYER, stores the profile and compensates on profile-storage failure; this is not a cross-DB PostgreSQL transaction.
- The `status` enum enforces the domain in the DB. State changes, soft deletion, version checks and account enabled/disabled synchronization belong to the service; `user_db` no longer maintains `locked_until` or login counters.

Removed or transferred data:

| Source data | Target / action | Requirement source |
|---|---|---|
| `users.password_hash`, `users.failed_login_count`, `users.locked_until` | Remove from `user_db`; Keycloak manages passwords and brute-force lockout | #238, #236 |
| `roles`, `permissions`, `user_roles`, `role_permissions` | Remove from `user_db`; use Keycloak realm/client roles and Admin API for role/privilege reads | #238, #236, #211 |
| `refresh_tokens`, `password_reset_tokens` | Remove from `user_db`; Keycloak manages sessions, refresh and password reset | #238 |
| `ratings`, `user_reputations` | Move to `rating_db`; remove FKs to `user_db.users` | #242 |

**Chosen auth/profile synchronization:** retain LOCKED for profile locking by the administration workflow, without restoring failed_login_count/locked_until in user_db. Keep last_login_at nullable and project only successful Keycloak LOGIN events through an authenticated internal adapter. Provisioning/implementing that adapter belongs to the auth/profile issue and is absent from the current code. Duplicate or late events use MAX timestamp so last_login_at never moves backwards.

**Reusing contacts in Keycloak as well:** on soft deletion, disable the old account and revoke sessions, rename its Keycloak username/email to a unique tombstone based on the old ID, then complete the local deletion marker. Retry/compensate failures using an operation ID; do not report success while the login identity remains occupied. Preserve the old profile/history UUID; registration creates a new account UUID. Matching contacts must not cause the old user's business references to be reassigned to the new user.

### user_db.seller

#257 / user-service V8 owns one company/application profile per user. #210 reuses this table; the seller UUID remains the existing Keycloak user ID.

| Field | PostgreSQL type | NULL? | Rule / purpose |
| --- | --- | --- | --- |
| `user_id` | `uuid` | No | PK and local FK users(id), ON DELETE NO ACTION; no generated seller ID. |
| `company_name` | `varchar(200)` | No | Nonblank company name. |
| `contact_phone` | `varchar(20)` | No | Nonblank contact phone. |
| `status` | `seller_status` | No | PENDING (default), ACTIVE, REJECTED. |
| `application_note` | `text` | Yes | Application text written by #210. |
| `rejection_reason` | `text` | Yes | Required/nonblank only when REJECTED; clear on reapply. |
| `applied_at` | `timestamptz` | No | DEFAULT now(); application time. |
| `reviewed_by`, `reviewed_at` | `uuid`, `timestamptz` | Yes | Both required for ACTIVE/REJECTED and NULL for PENDING. |
| `version` | `bigint` | No | DEFAULT 0, CHECK >= 0; writer increments on change. |
| `created_at`, `updated_at` | `timestamptz` | No | DEFAULT now(); updates explicitly write updated_at. |
| `created_by`, `updated_by` | `uuid` | No | DEFAULT all-zero system actor; no identity FK. |

CHECK enforces coherent review metadata and no rejection reason for PENDING/ACTIVE. Index `(status, applied_at, user_id)` serves application review. Existing users with SELLER but no row receive an ACTIVE row on first GET /sellers/me, copying local full_name/phone and using the system reviewer/time. No cross-service backfill or replacement seller UUID.

### user_db.outbox

**Owner / source:** user_db; user V1. Retain the V1 definition; no transfer to another database.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraint |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Outbox row ID; proposed to remain identical to eventId across publication/retries | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Aggregate type name for the event | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Text aggregate ID, which may be a UUID or business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID connecting the request/event | `No additional constraint` |
| `occurred_at` | `timestamptz` | No | `now()` | Time the event occurred | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not marked published | `No additional constraint` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is defined in the field; no FK to business tables or another database. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business rules:** the business change and outbox INSERT share one local transaction. The proposed row ID equals the envelope eventId; relay retries retain eventId, aggregate key and payload rather than creating a new ID. A relay may resend after crashing between the Kafka acknowledgement and published_at update: consumers must deduplicate, and outbox does not provide exactly-once delivery by itself. Store the complete envelope in payload JSONB as the demo code does; outbox.id equals eventId, and the relay sends the original envelope without double-wrapping or changing eventId. `aggregate_id` is varchar(100), not automatically a UUID or FK, because some aggregates use text keys. Retain published outbox rows for at least 90 days; never purge unpublished rows. Domain ledger/unique rules and the processed_events retention policy protect the corresponding replays.

### user_db.processed_events

**Owner / source:** user_db; user V1. Retain the V1 definition; no transfer to another database.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraint |
|---|---|---|---|---|---|
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name distinguishing handlers of the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Local processing completion time | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)` must not become event_id alone. No FK to another service's outbox/event_logs.

**Business rules:** write the processed marker and business/outbox changes in the same transaction, rolling both back if the handler fails. The marker's UNIQUE key prevents processing the same event twice in one handler, but not different eventIds for the same operation; the domain still needs order/provider-reference uniqueness. If an envelope lacks prerequisites, do not mark it successfully processed and discard it. Retain ordinary consumer markers for at least 90 days; critical order/payment/refund markers remain for 5 years or the ledger lifetime. Replays outside that window require a controlled restore/backfill workflow and business uniqueness; deduplication is not implicitly permanent after purging. Consumer names identify stable handlers, not instances.

### user_db.shedlock

**Owner / source:** user_db; user V1. Retain the V1 definition; no transfer to another database.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraint |
|---|---|---|---|---|---|
| `name` | `varchar(64)` | No | — | Job lock name, unique within the database | `PRIMARY KEY` |
| `lock_until` | `timestamptz` | No | — | Lease expiration time | `NOT NULL` |
| `locked_at` | `timestamptz` | No | — | Lock acquisition time | `NOT NULL` |
| `locked_by` | `varchar(255)` | No | — | Identifier of the instance holding the lease | `NOT NULL` |

**Keys/constraints/indexes:**
- PK `(name)`; no secondary index or FK; no defaults for lease times/owner.

**Business rules:** the scheduler acquires and renews a lease through the local lock provider. This table does not replace business idempotency or FK enforcement. lockAtMostFor/lockAtLeastFor belong to job configuration, not fixed CHECK literals. Preserve the existing definition; add scheduler lock tables to new services only when the implementation issue selects them.

<a id="catalog-db"></a>

## catalog-service / catalog_db

Retain catalog ownership of `categories`, `products`, `skus`, `product_images` and `product_stats`; `outbox` and `processed_events` remain local technical tables. Do not add inventory tables to catalog or FKs to user/inventory. Sources: V1, V2 schema corrections (#67), V3/V4 seeds (no additional DDL), existing entities/repositories/services and #249.

Target SQL enums under #249 are product_status and sku_status, both containing DRAFT, ACTIVE and INACTIVE. The implementation branch has these types; the develop baseline still uses varchar/CHECK. Money retains numeric(19,2) from V2, not bigint.

### catalog_db.categories

Purpose: category tree and materialized paths. Retain the table and all V1 fields; add audit actors under #249.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | DB `uuidv7()` when INSERT omits the ID | Category ID | PK; retain V1. The service generates a UUID; the DB default does not force every ID to be UUIDv7. |
| `parent_id` | `uuid` | Yes | None | Parent category; NULL denotes a root | Local FK `categories(id)`; retain V1. |
| `name` | `varchar(150)` | No | None | Category name | Retain V1; service trims/validates. |
| `slug` | `varchar(180)` | No | None | Category slug | Whole-table UNIQUE; retain V1. |
| `description` | `text` | Yes | None | Description | Retain V1. |
| `level` | `smallint` | No | `1` | Category depth; root = 1 | User decision and current CategoryService code; target CHECK level >= 1, with maximum depth from runtime configuration. |
| `path` | `varchar(500)` | Yes | None; computed by service | Materialized path such as `/slug-cha/slug-con/` | Retain V1; baseline permits NULL; new writes must compute a complete path. |
| `product_count` | `integer` | No | `0` | Product count maintained by catalog logic/projection | `CHECK (product_count >= 0)`; retain V1; service maintains the correct count. |
| `created_at` | `timestamptz` | No | `now()` | Creation time | Retain V1. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Update time | Retain V1; service/JPA updates it; no baseline trigger. |
| `deleted_at` | `timestamptz` | Yes | None | Soft-deletion marker when product relationships remain | Retain V1. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creator actor; immutable after INSERT | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Latest writer/update actor; append-only history is not rewritten | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |

Constraints, indexes and relationships:

- `PRIMARY KEY (id)`; `UNIQUE (slug)`; `CHECK (product_count >= 0)`.
- `parent_id REFERENCES categories(id)` with V1 default `ON DELETE NO ACTION`, `ON UPDATE NO ACTION`. A parent reference is optional; a parent has 0..n children.
- Nonunique indexes `idx_categories_parent (parent_id)` and `idx_categories_path (path)`; do not replace the path B-tree with another operator/pattern index without a query plan.
- Current DDL has no cycle-prevention CHECK. The service checks that the parent exists/is not deleted and is neither the node itself nor a descendant. Moving a subtree updates descendant paths and levels in the same transaction.
- The service enforces configured depth limits, the 500-character path limit and slug format. The DB only enforces `varchar(500)` and UNIQUE; runtime settings must not become fixed CHECK literals.
- Current code rejects deletion when undeleted children exist; rejects it when products remain and configuration forbids deletion; marks `deleted_at` while preserving FKs when products remain and deletion is allowed; and may hard-delete when no products remain. Physical references can still prevent hard deletion. Tree/product cascading must not be assumed.

**Level/path decision:** root nodes have parent_id NULL and level 1; children have level = parent.level + 1. Add CHECK level >= 1 after normalizing historical data against the actual tree, rather than blindly adding 1 when code already stores root level 1. Preserve nullable path to avoid an unrelated schema change; new writers always compute canonical paths, and historical paths are backfilled from verified parent relationships.

### catalog_db.products

Purpose: product information and catalog state flags. Retain V1; convert status to an enum and add audit actors under #249.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | DB `uuidv7()` if omitted | Product ID | PK; retain V1; entity currently generates a UUID. |
| `category_id` | `uuid` | No | None | Product category | Local FK `categories(id)`; retain V1. |
| `seller_id` | `uuid` | No | None; from CurrentUser | Seller profile ID | Logical reference to `user_db.users.id`, no cross-DB FK; retain V1. |
| `name` | `varchar(255)` | No | None | Product name | Retain V1. |
| `slug` | `varchar(300)` | No | None; generated by service | Stable application-defined path | Whole-table UNIQUE; retain V1. |
| `description` | `text` | Yes | None | Description | Retain V1. |
| `status` | `product_status` | No | `'DRAFT'::product_status` | `DRAFT`, `ACTIVE`, `INACTIVE` | Type/cast follows #249 implementation; preserve the domain. Baseline uses `varchar(20)` + CHECK. |
| `auction_enable` | `boolean` | No | `false` | Product may be listed for auction | Retain V1; no implicit FK to auction. |
| `has_order` | `boolean` | No | `false` | Flag/projection indicating an existing order | Retain V1; does not replace contract-defined order checks. |
| `in_auction` | `boolean` | No | `false` | Flag/projection indicating auction participation | Retain V1; service/events synchronize it. |
| `created_at` | `timestamptz` | No | `now()` | Creation time | Retain V1. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Update time | Retain V1; JPA/service updates it. |
| `deleted_at` | `timestamptz` | Yes | None | Soft-deletion time | Retain V1; do not replace it by adding `DELETED` to the enum. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creator actor; immutable after INSERT | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Latest writer/update actor; append-only history is not rewritten | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |

Constraints, indexes, relationships and lifecycle:

- `PRIMARY KEY (id)`; `UNIQUE (slug)`; `category_id REFERENCES categories(id)` with `ON DELETE NO ACTION`, `ON UPDATE NO ACTION`. Each product has exactly one category; a category has 0..n products.
- Nonunique indexes: `idx_products_category (category_id, status)`, `idx_products_seller (seller_id, status)`, `idx_products_status (status, deleted_at)`. None has a predicate in the baseline.
- `seller_id` is a logical user-service UUID. Do not read `user_db` directly or create a cross-DB FK.
- Current public reads filter `status = 'ACTIVE' AND deleted_at IS NULL`; seller lists also exclude `deleted_at IS NOT NULL`. Do not replace every index with a partial index merely because public queries filter this way; #249 requires EXPLAIN before adding indexes.
- ProductStatus currently permits `DRAFT → ACTIVE`, `ACTIVE → INACTIVE`, `INACTIVE → ACTIVE`. The service enforces transitions; the DB enum only enforces the value domain.
- The current product deletion API soft-deletes through `deleted_at`, checking permissions/ownership and order references according to configuration. SKUs/images/stats remain. The FKs below cascade only on physical product deletion.

### catalog_db.skus

Purpose: sellable product variants. Money types and uniqueness follow V2, not V1 alone.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | DB `uuidv7()` if omitted | SKU ID | PK; retain V1. |
| `product_id` | `uuid` | No | None | Product owning the SKU | Local FK `products(id)` `ON DELETE CASCADE`; retain V1. |
| `sku_code` | `varchar(60)` | No | None | SKU code | Whole-table UNIQUE; retain V1. |
| `color` | `varchar(50)` | Yes | None | Variant color component | Retain V1; part of V2 variant uniqueness, including NULLs. |
| `size` | `varchar(50)` | Yes | None | Variant size component | Retain V1; V2 variant uniqueness also covers NULLs. |
| `price` | `numeric(19,2)` | No | None | Unit SKU price | V2/#67 replaces V1 `bigint`; retain `CHECK (price >= 0)`. |
| `currency` | `char(3)` | No | `'VND'` | Currency code | Retain V1; baseline has no VND-only CHECK or currency FK. |
| `status` | `sku_status` | No | `'ACTIVE'::sku_status` | `DRAFT`, `ACTIVE`, `INACTIVE` | PostgreSQL/Java enum under #249; type/cast follows the implementation; baseline `varchar(20)` + CHECK. |
| `created_at` | `timestamptz` | No | `now()` | Creation time | Retain V1. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Update time | Retain V1; JPA/service updates it. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creator actor; immutable after INSERT | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Latest writer/update actor; append-only history is not rewritten | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |

Constraints, indexes and relationships:

- `PRIMARY KEY (id)`; `UNIQUE (sku_code)`; `CHECK (price >= 0)`.
- `product_id REFERENCES products(id) ON DELETE CASCADE ON UPDATE NO ACTION`; a product has 0..n SKUs and every SKU belongs to exactly one product.
- `idx_skus_product ON skus (product_id)` is nonunique.
- V2: `CREATE UNIQUE INDEX idx_skus_variant ON skus (product_id, color, size) NULLS NOT DISTINCT`. Preserve column order and `NULLS NOT DISTINCT`: two SKUs for the same product with color=NULL and size=NULL still conflict. Ordinary nullable UNIQUE is not equivalent.
- The baseline has no SKU `deleted_at`. Visibility uses status and the parent product's visibility under the API contract; do not add soft deletion merely to match products.
- Inventory stores the logical SKU UUID in its own DB; stock quantities do not belong in this table. Commerce price snapshots have no reverse FK that updates them when the SKU price changes.

### catalog_db.product_images

Purpose: product images and display order. Retain V1 and add audit actors under #249.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | DB `uuidv7()` if omitted | Image record ID | PK; retain V1. |
| `product_id` | `uuid` | No | None | Product owning the image | FK `products(id)` `ON DELETE CASCADE`; retain V1. |
| `image_url` | `varchar(500)` | No | None | Image object URL | Retain V1; no URL UNIQUE or stored blob bytes. |
| `sort_order` | `smallint` | No | `0` | Display order | Retain V1; no baseline nonnegative CHECK or per-product/order UNIQUE. |
| `created_at` | `timestamptz` | No | `now()` | Image association time | Retain V1. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creator actor; immutable after INSERT | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Latest writer/update actor; append-only history is not rewritten | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |

Constraints/indexes/relationships: PK `(id)`; FK `product_id → products(id)` `ON DELETE CASCADE`, `ON UPDATE NO ACTION`; nonunique `idx_product_images_product (product_id, sort_order)`. A product has 0..n images. The API sorts by `sort_order`, then `id` for ties; display order is not implicitly unique. Removing metadata and deleting the object from storage are separate operations governed by the image upload/deletion issue; an FK cannot delete an external storage object.

### catalog_db.product_stats

Purpose: statistics/projection with one row per product. Retain V1; this is not the source of truth for commerce orders.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `product_id` | `uuid` | No | None | Product being summarized | Both PK and FK `products(id)` `ON DELETE CASCADE`; retain V1. |
| `order_count` | `integer` | No | `0` | Order/activity count under the product statistics rules | `CHECK (order_count >= 0)`; retain V1; service/events define increments/decrements. |
| `view_count` | `integer` | No | `0` | View count | `CHECK (view_count >= 0)`; retain V1. |
| `auction_end_at` | `timestamptz` | Yes | None | Auction end time for display/projection | Retain V1; no FK to auction-service. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Projection update time | Retain V1; writer updates it. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creator actor; immutable after INSERT | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Latest writer/update actor; append-only history is not rewritten | #249/0bc9da1 and user decision: NOT NULL/system default; authenticated writers supply the actor; no cross-DB FK. |

Constraints/relationships: PK `(product_id)`; FK `product_id → products(id)` `ON DELETE CASCADE`, `ON UPDATE NO ACTION`; nonnegative CHECKs on both counters. The PK permits at most one row/product, but a product may have no statistics row yet. No secondary index or `created_at` exists in the baseline; add neither without a query/requirement. Idempotent projection updates by services/consumers must coordinate with catalog's `processed_events`; counters are not inherently safe against duplicate events.

### Agreed Catalog Scope

**User decision:** do not add products.search_document, GIN full-text indexes, unaccent or search triggers in this issue's schema. Retain existing catalog fields/indexes; #215 remains a separate search issue and is not completed by this specification.

Under #216, has_order/in_auction are existing projections updated with processed_events in a local transaction. Receiving a flag-setting event does not justify incrementing unrelated counters. Under #249, product_stats also has two NOT NULL audit actors. UUIDs retain each entity's existing generator; no system-wide UUIDv7 conversion is required.

### catalog_db.outbox

**Owner / source:** catalog_db; catalog V1. Retain the V1 definition; no transfer to another database.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraint |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Outbox row ID; proposed to remain identical to eventId across publication/retries | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Aggregate type name for the event | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Text aggregate ID, which may be a UUID or business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID connecting the request/event | `No additional constraint` |
| `occurred_at` | `timestamptz` | No | `now()` | Time the event occurred | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not marked published | `No additional constraint` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is defined in the field; no FK to business tables or another database. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business rules:** the business change and outbox INSERT share one local transaction. The proposed row ID equals the envelope eventId; relay retries retain eventId, aggregate key and payload rather than creating a new ID. A relay may resend after crashing between the Kafka acknowledgement and published_at update: consumers must deduplicate, and outbox does not provide exactly-once delivery by itself. Store the complete envelope in payload JSONB as the demo code does; outbox.id equals eventId, and the relay sends the original envelope without double-wrapping or changing eventId. `aggregate_id` is varchar(100), not automatically a UUID or FK, because some aggregates use text keys. Retain published outbox rows for at least 90 days; never purge unpublished rows. Domain ledger/unique rules and the processed_events retention policy protect the corresponding replays.

### catalog_db.processed_events

**Owner / source:** catalog_db; catalog V1. Retain the V1 definition; no transfer to another database.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraint |
|---|---|---|---|---|---|
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name distinguishing handlers of the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Local processing completion time | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)` must not become event_id alone. No FK to another service's outbox/event_logs.

**Business rules:** write the processed marker and business/outbox changes in the same transaction, rolling both back if the handler fails. The marker's UNIQUE key prevents processing the same event twice in one handler, but not different eventIds for the same operation; the domain still needs order/provider-reference uniqueness. If an envelope lacks prerequisites, do not mark it successfully processed and discard it. Retain ordinary consumer markers for at least 90 days; critical order/payment/refund markers remain for 5 years or the ledger lifetime. Replays outside that window require a controlled restore/backfill workflow and business uniqueness; deduplication is not implicitly permanent after purging. Consumer names identify stable handlers, not instances.

<a id="commerce-db"></a>

## commerce-service / commerce_db

**Owner:** `commerce-service`. **Baseline sources:** `commerce-service/src/main/resources/db/migration/V1__init.sql` and `V2__schema_corrections.sql`. This section specifies **14 business tables** (one order-group header, five cart/order tables and eight promotion tables), plus three technical tables. Checkout is the cart-submission/pricing operation; `order_groups` is the persisted purchase grouping produced by it. Each group has exactly one payment session/QR, while seller orders retain independent fulfillment and paid-cancellation/refund lifecycles. R1 has no payment-plan revisions, replacement QR sessions or duplicate group payment-state fields. `payment_transactions` belongs to payment_db under #240.

**Source labels:** `Existing V1`/`Existing V2` identify definitions in local migrations; they do not claim deployment to every environment. `Proposed` identifies a proposed design requiring review/a new migration. `—` in Default means no DEFAULT is declared; it is neither a stored string nor an automatically generated SQL value. A PK is NOT NULL even when SQL does not state that separately. FKs without explicit actions use PostgreSQL defaults `ON UPDATE NO ACTION ON DELETE NO ACTION`; CASCADE is not implied.

**Audit policy:** follow code #249 and the user's decision: created_by/updated_by are UUID NOT NULL with the all-zero system UUID as default; JPA/native writers, backfill, and immutable creator semantics follow the common conventions.

**Enums from #249:** cart_status, order_status, and order_type use their actual names from implementation branch 0bc9da1. The six promotion status enums below list provisional values already present on that branch for reviewer comparison; the four promotion classification types remain review designs. The user leaves all enum/promotion definitions PENDING REVIEWER; provisional branch code does not constitute business approval. Enums restrict allowed values but do not enforce transition graphs. Sort by business order/timestamps rather than an enum's internal order.

| Proposed target enum name | Complete value set | Source/status |
|---|---|---|
| `cart_status` | `ACTIVE`, `CHECKED_OUT`, `EXPIRED`, `MERGED` | Values already constrained by V1 CHECK; PostgreSQL enum name follows implementation #249. |
| `order_type` | `STANDARD`, `AUCTION` | Values already constrained by V1 CHECK; PostgreSQL enum name follows implementation #249. |
| `order_status` | `PENDING`, `PAID`, `CANCELED`, `SHIPPED`, `DELIVERED`, `COMPLETED`, `REFUND_REQUESTED`, `REFUNDED` | The eight values follow #116/#249 and match the V1 CHECK set; PostgreSQL enum name follows implementation #249. Do not add PROCESSING/FAILED/UNRECONCILED to order statuses. |
| `promo_mega_sale_event_status` | `DRAFT`, `REGISTRATION_OPEN`, `REGISTRATION_CLOSED`, `ACTIVE`, `ENDED`, `CANCELED` | PENDING REVIEWER: the entire value set is proposed; V1 only has varchar(20) NOT NULL, with no CHECK/default. |
| `promo_voucher_status` | `DRAFT`, `ACTIVE`, `PAUSED`, `EXPIRED`, `CANCELED` | PENDING REVIEWER: the entire value set is proposed; V1 has no CHECK/default. |
| `promo_event_seller_status` | `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN` | PENDING REVIEWER: the entire value set is proposed; V1 has no CHECK/default. |
| `promo_customer_voucher_status` | `CLAIMED`, `USED`, `EXPIRED` | PENDING REVIEWER: the entire value set is proposed; V1 has no CHECK/default. |
| `promo_voucher_seller_status` | `ACTIVE`, `WITHDRAWN` | PENDING REVIEWER: the entire value set is proposed; V1 has no CHECK/default. |
| `promo_voucher_usage_status` | `RESERVED`, `REDEEMED`, `RELEASED`, `EXPIRED`, `REVERSED` | PENDING REVIEWER: the entire value set is proposed; V1 only has varchar(25) NOT NULL. |
| `commerce_voucher_source_type` | `PLATFORM`, `SELLER`, `MEGA_SALE` | PENDING REVIEWER: proposed classifications; V1 has no CHECK/default. Do not assume approval by an API/business rule. |
| `commerce_discount_type` | `FIXED_AMOUNT`, `PERCENTAGE` | PENDING REVIEWER: proposed values; PERCENTAGE requires an additional 0–100 rule after confirmation. |
| `commerce_funding_type` | `PLATFORM`, `SELLER`, `SHARED` | PENDING REVIEWER: proposed values; business owners must confirm the mapping to seller_funding_percent. |
| `commerce_product_scope` | `ALL`, `SELECTED_PRODUCTS` | PENDING REVIEWER: proposed values; business owners must confirm how promo_voucher_products is combined with this scope. |

**Money and references:** monetary amounts retain `numeric(19,2)` and the nonnegative CHECK constraints from SQL; funding percentages retain `numeric(5,2)` and are not monetary amounts. `orders`/`order_items`/carts retain their existing lack of a separate currency column. The new order group supplies the shared payment currency; the service validates every child instruction amount against it. No existing order field is silently retyped or removed to introduce the group. Identity, catalog, auction, inventory, payment, and shipment are referenced through UUIDs/events/APIs; there are no cross-database FKs or joins. Source JSON objects only have `jsonb` type/nullability constraints; the service must validate their nested contracts.

### commerce_db.carts

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Cart associated with a user or guest token. **Source:** V1 starting at line 1, and V2 for ACTIVE-cart uniqueness. **Target changes:** Add the two audit columns from #249; converting status to an enum is a proposed physical schema change.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `user_id` | `uuid` | Yes | — | Buyer/cart-owner UUID from identity; a logical reference, without an FK to user_db/Keycloak. | Existing V1 |
| `guest_token` | `varchar(255)` | Yes | — | Guest cart token; V1 permits a cart without user_id when a token exists, although the current R1 API only serves authenticated users. | Existing V1 |
| `status` | `cart_status` (Proposed; source `varchar(20)`) | No | `'ACTIVE'` | Business status; values and sources are listed in the enum catalogue above. | `CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'EXPIRED', 'MERGED'))`; Existing V1; proposed enum conversion #249; see the full value set above |
| `promo_code` | `varchar(50)` | Yes | — | Promotion code selected/snapshotted at the time of the operation; currently no FK to a voucher code. | Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: column `status` has `CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'EXPIRED', 'MERGED'))`. The constraint name is not explicitly declared. The proposed enum will replace this membership CHECK after validating existing data; source literals remain unchanged.
- Existing V1: `CHECK (user_id IS NOT NULL OR guest_token IS NOT NULL)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CREATE INDEX idx_carts_user ON carts (user_id);`
- Existing V1: `CREATE INDEX idx_carts_guest ON carts (guest_token);`
- Existing V1: `CREATE INDEX idx_carts_expiry ON carts (status, updated_at);`
- Existing V2: `CREATE UNIQUE INDEX idx_carts_one_active ON carts (user_id) WHERE status = 'ACTIVE' AND user_id IS NOT NULL;` — does not apply to guests with null user_id and is not UNIQUE(user_id) across all statuses.

**Relationships and FK actions:**

- This table declares no physical FK. UUID references to other services are explained in the field definitions; do not create cross-database FKs.

**Lifecycle:** Retain ACTIVE carts; transition to CHECKED_OUT when an order is placed, EXPIRED upon expiry, and MERGED after merging. The service performs these transitions; the DB does not run jobs automatically.

**Enforcement:** V2 already guarantees at most one ACTIVE cart for a non-null user. V1 only requires a user or guest token; it does not prohibit both. Do not create cross-service guest/user FKs. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.cart_items

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** A SKU line in a cart and its price snapshot. **Source:** V1 starting at line 16. **Target changes:** Add audit columns from #249; retain uniqueness per cart/SKU.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `cart_id` | `uuid` | No | — | Cart owning the line. | `REFERENCES carts (id) ON DELETE CASCADE`; Existing V1 |
| `product_id` | `uuid` | No | — | Catalog product UUID; a logical reference without an FK to catalog_db. | Existing V1 |
| `sku_id` | `uuid` | No | — | Catalog SKU UUID; a logical reference without an FK to catalog_db. | Existing V1 |
| `quantity` | `integer` | No | — | Number of units. | `CHECK (quantity > 0)`; Existing V1 |
| `price` | `numeric(19,2)` | No | — | Snapshot price of one SKU unit in the cart; do not use float/double. | `CHECK (price >= 0)`; Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: column `quantity` has `CHECK (quantity > 0)`. The constraint name is not explicitly declared.
- Existing V1: column `price` has `CHECK (price >= 0)`. The constraint name is not explicitly declared.
- Existing V1: `UNIQUE (cart_id, sku_id)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);`

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `cart_items.cart_id → carts.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Add/increment quantities, use PUT to replace a quantity, or delete a line; deleting a cart removes its lines through CASCADE. Do not automatically replace deletion with soft deletion.

**Enforcement:** The service checks cart ownership and SKU status/prices through CatalogClient. The DB validates quantity/price/uniqueness; it does not guarantee stock availability or automatically refresh prices from catalog. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.order_groups

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Persist the buyer's complete purchase group, containing one seller order per shop and exactly one payment session. **Terminology:** checkout is the operation that validates the cart, prices items and submits the purchase; `order_groups` is the stored grouping entity produced by that operation. There is no `checkouts` table. **Source/status:** selected R1 multi-shop/shared-QR design. It extends the original #113 no-group model and the per-order payment request in #121/BR-109; update those implementation contracts rather than claiming that the baseline already contains the group.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Canonical order-group ID returned on every replay | PK; new target table. Preserve the existing writer-specific UUID conventions when the application supplies an ID. |
| `buyer_id` | `uuid` | No | — | Buyer owning every seller order in the group | Logical user/Keycloak reference, no cross-database FK. The service checks every child order has this buyer. |
| `idempotency_key` | `varchar(100)` | No | — | Stable initial request key for creating this order group | UNIQUE across this table. Retries return the same group and child IDs; the stored request/group snapshots determine whether the body agrees. |
| `status` | `varchar(20)` | No | `'PENDING'` | Group-level payment/cancellation outcome | CHECK `status IN ('PENDING','PAID','CANCELED','EXPIRED')`; not a shared enum for all payment-domain entities and not a replacement for child-order lifecycle. |
| `total_amount` | `numeric(19,2)` | No | — | Sum of finalized seller-order payable amounts | CHECK `total_amount >= 0`; the service enforces the cross-row sum. Immutable once the complete payment manifest is issued. |
| `currency` | `char(3)` | No | `'VND'` | Shared currency of the group and its single payment session | R1 writers use VND. All instruction/child monetary amounts must agree with this currency. |
| `due_at` | `timestamptz` | Yes | — | Common payment deadline of the group | PENDING requires a verified non-NULL deadline; legacy closed groups may have NULL when no deadline evidence exists. Use the earliest applicable child deadline. |
| `paid_at` | `timestamptz` | Yes | — | Verified payment time from the accepted complete session confirmation | NULL before funding; preserve after individual child cancellation/refund. Never fabricate a historical timestamp from migration time. |
| `created_at` | `timestamptz` | No | `now()` | Order-group creation timestamp | New target field. |
| `updated_at` | `timestamptz` | No | `now()` | Most recent local group update | DEFAULT is INSERT-only; the writer refreshes this timestamp on changes. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor, immutable after INSERT | Code #249/user audit policy; authenticated writers supply the actual actor; no actor FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the most recent write | Code #249/user audit policy; native UPDATE supplies the actor explicitly. |
| `version` | `bigint` | No | `0` | Local optimistic-lock token for group concurrency | CHECK `version >= 0`; use row lock/CAS for group funding/cancellation. It is not a payment producer sequence or envelope eventVersion. |

**Keys, constraints, indexes and relationships:**

- PK `(id)`; UNIQUE `(idempotency_key)`; CHECK `total_amount >= 0`; CHECK `version >= 0`; CHECK `status IN ('PENDING','PAID','CANCELED','EXPIRED')`; CHECK `status <> 'PENDING' OR due_at IS NOT NULL`.
- B-tree indexes selected for the new table: `idx_order_groups_buyer ON order_groups (buyer_id, created_at DESC)` and `idx_order_groups_expiring ON order_groups (due_at, id) WHERE status = 'PENDING'`. The request-key UNIQUE creates its own index.
- One group contains 1..N seller orders after creation. Each order has exactly one NOT NULL `order_group_id` local FK. UNIQUE `(order_group_id, seller_id)` permits one seller/shop order per group in the current seller-as-shop model. Service validation additionally enforces the same buyer/currency and correct ownership of every line.
- Payment owns exactly one session for this group under UNIQUE `payment_sessions.order_group_id` in payment_db; this is a logical cross-service relationship, not a commerce FK or a duplicate `payment_session_id`/`payment_state` projection on order_groups. There are no revision, replacement-session, fingerprint or producer-session-version fields in this R1 group.
- Keep the stored order group and its orders as purchase evidence. The order-group FK uses NO ACTION, not a cascade deleting an entire paid group. `status = 'PAID'` preserves successful group payment even when a child is later canceled/refunded; shipment/refund status stays on child orders and the payment ledger.

**Implementation in #113:** commerce migration `V3__order_groups.sql` introduces
the thirteen group columns above, the order-group FK and seller uniqueness. Each
legacy order receives a singleton group with the same UUID in the separate group
table and the deterministic group key `legacy-order:{orderId}`; the historical
order's own key, number, amounts and timestamps remain unchanged. Pending legacy
orders without a recorded `payment_due_at` stop the migration with a diagnostic
for reconciliation. No historical deadline or paid timestamp is fabricated.
`order_number_seq` supplies new unique order suffixes across application instances
and begins above any existing `NX-YYYYMMDD-N` suffix. New child keys use the
canonical SHA-256 tuple described below. The normalized flat API shipping address
and SEPAY method are retained in each child `shipping_info` snapshot for replay
comparison, without a fingerprint or payment-method column on the group. The
future carrier-code/warehouse shipping contract remains separate from this API.

**Checkout operation, atomic seller split and request replay:**

1. Authenticate the buyer, normalize the checkout request, and claim/lock its globally unique request key and source cart. On an existing key, compare the caller and normalized request against the stored group, complete child list, item/quantity/price snapshots, shipping selection and voucher/pricing snapshots. Same key and same logical request return the same group, seller orders and IDs, even if current catalog prices, cart state or session state have since changed. A different body/buyer returns conflict. Resolve replay before recomputing mutable prices or requiring the already-checked-out cart to remain ACTIVE; no fingerprint column is required.
2. Group selected cart lines by `seller_id`, the current shop boundary. Create exactly one STANDARD child order per seller. Validate every line's seller, common buyer/currency, price, discount allocation, tax and shipping fee. Do not distribute a group-level discount twice or charge each seller the full group shipping/discount amount.
3. Derive each child `orders.idempotency_key` as the lowercase 64-character hex SHA-256 of a canonical, length-delimited tuple `(buyer_id, order_groups.idempotency_key, seller_id)`. This fits the existing varchar(100) and gives deterministic globally unique child-operation identity. Do not copy the raw request key into every order, because that key is UNIQUE across orders. Preserve historical order keys during migration.
4. In one commerce_db transaction create the order group, every seller order, all order items/price/address snapshots, voucher-use allocations and initial history; mark the cart CHECKED_OUT; write one complete `CheckoutPaymentRequested` manifest to outbox. Any local failure rolls back the group, children and manifest together. Existing inventory/carrier network work retains its separately documented timeout/compensation boundary; it is not part of a cross-service SQL transaction.
5. Keep `CheckoutPaymentRequested`/`CheckoutPaymentConfirmed` as checkout-flow event names. The request payload identifies the persisted entity with `orderGroupId` and includes `buyerId`, `currency`, `totalAmount`, `dueAt` and the exact complete `orders[{orderId,sellerId,amount,paymentDueAt}]` list. Every child amount and the complete sum must match the stored group. Payment creates one session and one instruction per listed order; only the complete plan authorizes the group's one QR. Per-order OrderCreated may still serve existing nonpayment consumers, but the first such event cannot issue an individual order QR. There are no checkoutRevision, manifestVersion or duplicated payment_state fields in this contract.

**Complete payment confirmation:**

`CheckoutPaymentConfirmed` carries `sessionId`, `orderGroupId`, `transactionId`, `buyerId`, `currency`, `totalAmount`, `paidAt`, informational `sessionVersion` and the full `instructions[{instructionId,orderId,amount}]` set. Deduplicate through processed_events, then lock the group and its child orders in deterministic order. Check buyer/currency, complete instruction membership, exact child amounts, total, and the authoritative unique group/session relationship. A valid complete confirmation commits group PAID/paid_at, all still-payable child PENDING→PAID transitions, one history row per accepted child transition, separate per-order OrderPaid outbox events and the processed marker together. Inventory/shipment continue consuming individual OrderPaid events; an order group is not one seller order or one shipment. sessionVersion describes the payment producer's local transition, not a new group column or a substitute for eventId/business-key deduplication.

The group lock/local version and terminal-state rules serialize the local application of confirmation with cancellation. A repeated confirmation must agree with the stored funded outcome and creates no further history/events. No producer-session tuple is invented on order_groups. A conflicting session for the same group is a contract error because payment permits only one session. CANCELED/EXPIRED groups and CANCELED children never reopen from a late event; payment retains actual money evidence and uses reconciliation/compensation where needed. A group status does not pretend that an asynchronous cancellation was already applied at the bank/payment service.

**R1 before payment — cancel the whole group or expire its single session:**

- Do not cancel one unpaid child, amend the payable child list, increment a revision, or reissue a QR for the same group. Buyer changes use a separately submitted new purchase group, after the old group's local cancellation is accepted; the old group/session/memo remains traceable and never changes its amount. Any old-session money that wins before its asynchronous cancellation arrives follows the compensation rule below; this is not a payment-fence/ACK prerequisite for creating the new purchase.
- For a PENDING group, commerce locks the group and all children, checks the expected local version and cancellation permission, then commits group CANCELED, every still-PENDING child CANCELED, corresponding history, one OrderGroupCancelled event and per-child OrderCancelled events in a single local transaction. The group event includes orderGroupId and groupVersion; stable event/business identities make retries harmless. There is no payment cancellation fence, cancellation-ACK wait, or added pending-group field in this R1 design. Existing child cancellation fields remain for paid shipment coordination only.
- Payment applies OrderGroupCancelled/OrderCancelled under its single session and instruction locks. An unpaid session/instruction is closed without reopening it later. If the group manifest/session is not materialized yet, retain the cancellation in durable retry/DLQ and apply it after prerequisite creation rather than marking it processed and losing it. A session deadline is enforced locally using the verified receipt time and serialized status checks; expiry/repeated events do not fabricate payment or restore a canceled commerce order.
- Cancellation and payment are asynchronous across databases. A valid transfer can win in payment before the cancellation event arrives even though commerce already committed CANCELED. Preserve the NORMAL/SUCCESS receipt and PAID financial evidence; late CheckoutPaymentConfirmed does not resurrect the group/children or emit OrderPaid for them. When the cancellation reaches payment, automatically create the one refund operation for each canceled funded child, limited to its own instruction amount. Do not declare a cross-database atomic cancellation or rewrite successful money as FAILED. If commerce already applied PAID first, later child cancellation instead uses the existing shipment coordination path. A new purchase, if needed, is a new order group; the canceled group's QR/total is never revised or reused.

**Legacy/single-order compatibility and contract changes:**

Backfill one verified singleton order_group for each preexisting order, including AUCTION orders, retaining the existing buyer, IDs, order number, amount and actual deadline/payment evidence. Use a deterministic migration request key based on the historical order ID; do not merge unrelated orders by buyer/date. Existing order/session money facts require actual transaction evidence; do not invent SUCCESS transfers, group paid_at or deadlines. Validate order_group_id and its NOT NULL FK only after every historical order is assigned. A one-order group uses the same one-session contract; auction deadlines remain unchanged.

BR-109 originally associates a payment request with one specific order. The selected R1 extension uses one bank request/session for an order group, with one explicit instruction per seller order; amend #113/#121/#186/#240 and related API/output definitions accordingly. It does not mean each seller owns the group's whole receipt. A zero-total group does not create a positive-value bank QR; any free-order completion must be defined by the checkout API contract rather than a fabricated bank transfer.

**After payment and selective returns:** independent child fulfillment/cancellation is allowed after group funding. A canceled paid seller order automatically requests a refund only for its own funded instruction amount, never its sibling's share. R1 has exactly one refund operation per order under refunds.order_id UNIQUE; there is no split or repeated refund-request workflow. An explicit agreed refund amount is stored exactly and may be less than the funded amount, while the read contract exposes actual refunded and remaining paid amounts. Selective item returns are a different flow requiring reviewed item/quantity eligibility, pricing/tax/shipping/voucher reversal and API/event contracts; no return tables or implemented return workflow are claimed here.

### commerce_db.orders

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** One seller's STANDARD or AUCTION order, preserving its own status, shipment and refund lifecycle within a stored order group. **Source:** V1 starting at line 27, V2 for payment_due_at/reference indexing, #116/#240/#249 and the selected R1 order-group extension. Retain every existing field, its SQL type/nullability/default and audit/cancellation metadata. Add the NOT NULL local order_group_id relationship after verified backfill; no revision/producer-session projection is added.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `order_group_id` | `uuid` | No | — | Canonical purchase group containing this seller order | New target FK `REFERENCES order_groups(id)` with ON UPDATE NO ACTION ON DELETE NO ACTION; validate backfill before NOT NULL. UNIQUE(order_group_id,seller_id) enforces one order per seller/shop in the current model. |
| `user_id` | `uuid` | No | — | Buyer/cart-owner UUID from identity; a logical reference, without an FK to user_db/Keycloak. | Existing V1 |
| `seller_id` | `uuid` | No | — | Seller UUID; a logical identity reference without a cross-service FK. | Existing V1 |
| `order_number` | `varchar(40)` | No | — | Order number displayed to users. | `UNIQUE`; Existing V1 |
| `order_type` | `order_type` (Proposed; source `varchar(20)`) | No | — | Order origin: STANDARD or AUCTION. | `CHECK (order_type IN ('STANDARD', 'AUCTION'))`; Existing V1; proposed enum conversion #249; see the full value set above |
| `reference_id` | `uuid` | Yes | — | Source reference UUID; the event contract requires auctionId for AUCTION and null for STANDARD; the DB currently has no CHECK enforcing this relationship. | Existing V1 |
| `status` | `order_status` (Proposed; source `varchar(30)`) | No | `'PENDING'` | Business status; values and sources are listed in the enum catalogue above. | `CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELED', 'REFUND_REQUESTED', 'REFUNDED'))`; Existing V1; proposed enum conversion #249; see the full value set above |
| `subtotal` | `numeric(19,2)` | No | — | Item subtotal before discounts, tax, and shipping. | `CHECK (subtotal >= 0)`; Existing V1 |
| `discount_amount` | `numeric(19,2)` | No | `0` | Discount amount, interpreted as an allocation where the table's purpose specifies one. | `CHECK (discount_amount >= 0)`; Existing V1 |
| `promo_code` | `varchar(50)` | Yes | — | Promotion code selected/snapshotted at the time of the operation; currently no FK to a voucher code. | Existing V1 |
| `tax_amount` | `numeric(19,2)` | No | `0` | Tax amount. | `CHECK (tax_amount >= 0)`; Existing V1 |
| `shipping_fee` | `numeric(19,2)` | No | `0` | Shipping fee snapshotted from the quote; do not read shipment tables directly. | `CHECK (shipping_fee >= 0)`; Existing V1 |
| `total_amount` | `numeric(19,2)` | No | — | Total payable amount; V1 has no CHECK for the formula relating its components. | `CHECK (total_amount >= 0)`; Existing V1 |
| `shipping_info` | `jsonb` | Yes | — | JSON snapshot of the shipping address/information; the API contract defines its nested structure, and V1 has no JSON CHECK. | Existing V1 |
| `idempotency_key` | `varchar(100)` | No | — | Stable child-order key derived from buyer, initial checkout request key and seller; globally unique, not the raw HTTP key copied to all children | `UNIQUE`; retain the existing field/type. New child keys use the canonical 64-character SHA-256 scheme specified under order_groups; historical keys remain unchanged. |
| `paid_at` | `timestamptz` | Yes | — | Payment timestamp from this order's instruction in CheckoutPaymentConfirmed; do not infer it from HTTP receipt time or another seller's instruction. | Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `version` | `bigint` | No | `0` | Optimistic-lock version; the service must increment/check it for concurrent updates. | Existing V1 |
| `payment_due_at` | `timestamptz` | Yes | — | Order payment deadline. Retain it in commerce because the expiry job reads it; for AUCTION orders, preserve the deadline from AuctionWon. | Existing V2; retained under ADR-009/#240 |
| `payment_note` | `text` | Yes | — | PROPOSED column name for an unreconciled transfer reason under #157/PaymentUnreconciled; it is not a transfer memo and does not change order status. | Proposed #157: proposed column name/type/nullability; no migration yet |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `cancel_request_id` | `uuid` | Yes | No | Stable ID of a paid-order cancellation request coordinated with shipment | UNIQUE when non-NULL; replaying the same request does not create another cancellation request. |
| `cancel_requested_at` | `timestamptz` | Yes | No | Timestamp of an authorized cancellation request | Timestamp before a paid shipment fence can authorize CANCELED. |
| `cancel_pending` | `boolean` | No | `false` | Awaiting paid shipment non-handover/carrier cancellation | Do not add a ninth state to order_status. |
| `cancel_rejection_reason` | `text` | Yes | No | Reason cancellation cannot proceed or manual handling is required | Written only by the service; do not move an already SHIPPED/DELIVERED order back to PAID. |
| `last_refund_version` | `bigint` | No | `0` | Highest payment-service refund snapshot version applied to this order | CHECK `last_refund_version >= 0`; durable projection ordering, distinct from local orders.version and envelope eventVersion. Retained durable projection field for the one refund operation and its REQUESTED/COMPLETED events. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: UNIQUE `(order_number)`, creating an implicit unique index; scope is the entire table unless a separate partial index is explicitly specified.
- Existing V1: column `order_type` has `CHECK (order_type IN ('STANDARD', 'AUCTION'))`. The constraint name is not explicitly declared. The proposed enum will replace this membership CHECK after validating existing data; source literals remain unchanged.
- Existing V1: column `status` has `CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELED', 'REFUND_REQUESTED', 'REFUNDED'))`. The constraint name is not explicitly declared. The proposed enum will replace this membership CHECK after validating existing data; source literals remain unchanged.
- Existing V1: column `subtotal` has `CHECK (subtotal >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `discount_amount` has `CHECK (discount_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `tax_amount` has `CHECK (tax_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `shipping_fee` has `CHECK (shipping_fee >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `total_amount` has `CHECK (total_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: UNIQUE `(idempotency_key)`, creating an implicit unique index; scope is the entire table unless a separate partial index is explicitly specified.
- Existing V1: `CREATE INDEX idx_orders_user ON orders (user_id, created_at DESC);`
- Existing V1: `CREATE INDEX idx_orders_seller ON orders (seller_id, status);`
- Existing V1: `CREATE INDEX idx_orders_status ON orders (status, created_at);`
- Existing V2: `CREATE INDEX idx_orders_reference ON orders (reference_id) WHERE reference_id IS NOT NULL;` — a non-unique index; it does not yet guarantee one order per auction.
- Change #240: `memo_code varchar(32) UNIQUE` exists in commerce V2 but is **removed from target orders** and moved to `payment_db.payment_sessions.memo_code` under the selected shared-QR extension; do not delete/edit an applied V2 migration. `payment_due_at` remains nullable as in the source. The `payment_note text NULL` column is proposed so commerce can store a reason received through PaymentUnreconciled; it is not instruction/memo data owned by payment.

**Relationships, additional constraints and FK actions:**

- Local FK `orders.order_group_id → order_groups.id`, NOT NULL after verified migration, ON UPDATE NO ACTION ON DELETE NO ACTION, NOT DEFERRABLE. Each order belongs to exactly one stored group; one group contains 1..N seller orders. There is no reference to a checkouts table.
- UNIQUE `(order_group_id, seller_id)` guarantees one child per seller/shop in the current seller-as-shop model. The service additionally checks common group buyer/currency and every line's actual seller; separate keys cannot express these derived invariants by themselves.
- Retain all source PK/UNIQUE/CHECK/index definitions listed above. Retain UNIQUE `(cancel_request_id)`, CHECK `NOT cancel_pending OR (cancel_request_id IS NOT NULL AND cancel_requested_at IS NOT NULL)`, and CHECK `last_refund_version >= 0` from the selected paid-cancellation/refund design. The cancellation fields are for paid shipment coordination, not an unpaid child payment fence.
- Preserve logical UUID references to identity/catalog/auction and the logical payment instruction relationship. There is no FK or SQL join across service databases.

**Lifecycle and complete group confirmation:** STANDARD/AUCTION seller orders start PENDING. CheckoutPaymentConfirmed carries `orderGroupId`, `sessionId`, `transactionId`, buyer/currency/total/paidAt and the complete `instructions[{instructionId,orderId,amount}]` list. Commerce validates the full stored seller set and amounts, locks group/orders deterministically and commits the group paid outcome, eligible PENDING→PAID child transitions, child history, per-order OrderPaid outbox events and processed marker together. Shipping/delivery remain SHIPPED/DELIVERED/COMPLETED per child. Canceled-order refunds use CANCELED→REFUND_REQUESTED→REFUNDED. Keep these eight states exactly; no extra group/checkout state becomes an order state. A canceled order never returns to PAID from a late confirmation, and such a confirmation emits no OrderPaid for canceled children.

**Money, snapshots and idempotency:** child orders retain product/SKU/name/image/unit-price snapshots at order placement; shipping_info records recipient{name,phone,address{line1,wardCode,districtCode,provinceCode}} and the selected warehouseId, unchanged by later profile edits. The service validates the nested JSON shape and chosen warehouse. R1 uses the order group's VND currency; no duplicate orders currency column is introduced. Calculate total_amount = subtotal - discount_amount + tax_amount + shipping_fee through one pricing policy, allocating voucher/tax/shipping amounts once across seller orders and reconciling the complete group total. The complete CheckoutPaymentRequested manifest is published with all children in the initial commerce transaction, not assembled from the first OrderCreated received by payment. Canonical child idempotency keys are defined under order_groups; they must be distinct despite one shared checkout request key.

Retain/add the selected partial unique `uq_orders_auction_reference(order_type,reference_id) WHERE order_type='AUCTION' AND reference_id IS NOT NULL` after data validation to prevent two AuctionWon envelopes creating two orders for one auction under BR-165/BR-171. STANDARD references are not made globally unique. Database constraints validate field values and local keys; the service validates cross-row amounts, ownership, legal state changes and current configuration. No fake cross-table CHECK or transaction with payment_db is claimed.

**Before payment:** an individual PENDING seller order cannot be canceled while retaining its group's QR or repricing/reissuing that QR. The selected R1 action cancels the entire PENDING order group and every still-PENDING child atomically in commerce, records history and emits OrderGroupCancelled plus per-order OrderCancelled. Payment applies these events under its local session/instruction locks; if a valid receipt won before cancellation arrived, keep the successful money and automatically compensate the canceled funded instructions. Commerce does not resurrect CANCELED children or promise instantaneous cross-database cancellation. There is no unpaid child fence, cancellation-ACK wait, plan revision or replacement session in this design. A different purchase requires a new group after handling the old outcome. When #189 cancels the group from one seller order, the same transaction upserts the items of the other cancelled orders into the buyer's ACTIVE cart (creating it if needed, adding to the quantity of an existing `(cart_id, sku_id)` row), so the buyer can submit them as that new group; the requested order's items are not returned.

**Paid seller-order cancellation with shipment:** for PAID before SHIPPED, retain PAID and set the existing cancel_request_id/cancel_requested_at/cancel_pending while shipment coordinates no-handover/carrier cancellation. OrderCancellationRequested and ShipmentCancellationConfirmed/ShipmentCancellationRejected remain per-order contracts under #186/#189/#203. Only a matching requestId and expected local order version authorize CANCELED/history/OrderCancelled and clearing cancel_pending in one commerce transaction. Unknown carrier outcomes remain pending for retries/Support; rejection clears pending, records reason and preserves the current lifecycle. Serialize this local effect with CheckoutPaymentConfirmed and ShipmentPickedUp. OrderCancelled triggers at most the one automatic refund operation for this seller order's full funded instruction amount; it does not refund the group's entire bank receipt or its siblings. Keep successful payment evidence unchanged.

**One refund operation per order in R1:** payment_db.refunds.order_id is UNIQUE. Once an order's one agreed refund request exists, another API key or repeated cancellation resolves that operation or conflicts according to its idempotency/body rules; it cannot open a second request, top up the amount, or split the operation. A full paid-order cancellation automatically requests the full funded instruction amount using the system actor. For the authorized manual API, a supplied amount is persisted exactly, satisfies `0 < amount <= totalPaidAmount`, and may be less than the funded amount; omission resolves the full funded instruction amount once. An existing different request is not overwritten by a later auto-cancellation. Eligibility and reconciliation of that conflict follow the payment owner/Support workflow rather than silently creating another refund.

The accepted request moves CANCELED→REFUND_REQUESTED. Completing that same operation moves the order to REFUNDED under #116/#247: here REFUNDED means the **agreed refund operation has completed**, not necessarily that every paid unit was returned when a smaller manual amount was agreed. Read contracts expose totalPaidAmount, agreedRefundAmount, completedRefundAmount and remainingPaidAmount = totalPaidAmount - completedRefundAmount, all scoped to this order's funded instruction, so status never conceals the actual balance. No repeated partial-refund workflow or aggregate-request completion gate remains.

Refund events carry refundId/orderId/instructionId, the agreed amount/currency, REQUESTED or COMPLETED status, actual balance fields and `refundVersion` from the same payment transaction. Commerce compares refundVersion with orders.last_refund_version under the order lock, applies only newer valid transitions, and commits history/projection/processed marker together. If completion arrives first, validate its authoritative request identity and apply CANCELED→REFUND_REQUESTED→REFUNDED in one local transaction; the late older request does not regress it. Retain last_refund_version to order the single operation's events; it is not a license for multiple operations and is distinct from orders.version and envelope eventVersion.

**Selective item returns are separate:** immutable order_items and one amount-based refund request do not implement return eligibility or quantity/item allocation. Those API/event rules, shipping/tax/voucher reversal and any return persistence require their own reviewed design. No return table is added, and canceled-order auto-refund is not described as an implemented partial-return workflow.

### commerce_db.order_items

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Product/SKU snapshot, quantity, and monetary amounts for each order line. **Source:** V1 starting at line 55. **Target changes:** Add audit columns from #249; retain the local order FK and logical catalog references.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `order_id` | `uuid` | No | — | Associated commerce order. | `REFERENCES orders (id) ON DELETE CASCADE`; Existing V1 |
| `product_id` | `uuid` | No | — | Catalog product UUID; a logical reference without an FK to catalog_db. | Existing V1 |
| `sku_id` | `uuid` | No | — | Catalog SKU UUID; a logical reference without an FK to catalog_db. | Existing V1 |
| `product_name` | `varchar(255)` | No | — | Product-name snapshot on the order; unchanged when the catalog name changes. | Existing V1 |
| `sku_code` | `varchar(60)` | Yes | — | SKU-code snapshot; currently nullable. | Existing V1 |
| `image_url` | `varchar(500)` | Yes | — | Image URL snapshot; currently nullable although some events require an image, so a separate publishing policy is needed. | Existing V1 |
| `quantity` | `integer` | No | — | Number of units. | `CHECK (quantity > 0)`; Existing V1 |
| `unit_price` | `numeric(19,2)` | No | — | Unit-price snapshot at order creation. | `CHECK (unit_price >= 0)`; Existing V1 |
| `total_price` | `numeric(19,2)` | No | — | Order-line amount; V1 has no CHECK requiring quantity × unit_price. | `CHECK (total_price >= 0)`; Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: column `quantity` has `CHECK (quantity > 0)`. The constraint name is not explicitly declared.
- Existing V1: column `unit_price` has `CHECK (unit_price >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `total_price` has `CHECK (total_price >= 0)`. The constraint name is not explicitly declared.
- Existing V1: `CREATE INDEX idx_order_items_order ON order_items (order_id);`
- Existing V1: `CREATE INDEX idx_order_items_product ON order_items (product_id);`

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `order_items.order_id → orders.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Create rows in the same transaction as orders; preserve snapshots as evidence after catalog changes. V1 cascades on order deletion; actual retention must be defined by the business requirements.

**Enforcement:** The DB checks nonnegative quantities/amounts; the service calculates total_price and the order total. Do not add catalog FKs or assume a nullable image_url satisfies events requiring an image. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.order_status_history

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Order status transition history in commerce. **Source:** V1 starting at line 70. **Target changes:** Propose the order enum for previous_status/new_status and the two audit columns from #249; retain changed_by separately.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `order_id` | `uuid` | No | — | Associated commerce order. | `REFERENCES orders (id) ON DELETE CASCADE`; Existing V1 |
| `previous_status` | `order_status` (Proposed; source `varchar(30)`) | Yes | — | Order status before the change; null for the initial transition. | Existing V1; proposed enum conversion #249, with the full value set above; V1 has no membership CHECK, so the enum adds a proposed new restriction |
| `new_status` | `order_status` (Proposed; source `varchar(30)`) | No | — | Order status after the change. | Existing V1; proposed enum conversion #249, with the full value set above; V1 has no membership CHECK, so the enum adds a proposed new restriction |
| `reason` | `text` | Yes | — | Reason for the status transition. | Existing V1 |
| `changed_by` | `uuid` | Yes | — | UUID of the actor performing the status transition; retain this business field separately from audit columns #249; currently nullable. | Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: `CREATE INDEX idx_order_history_order ON order_status_history (order_id, created_at DESC);`

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `order_status_history.order_id → orders.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Append one row in the transaction updating orders status and outbox. Do not overwrite earlier history during the next transition; the current FK cascades if the order is deleted.

**Enforcement:** V1 only makes new_status NOT NULL and does not CHECK history status values; the proposed enum will constrain them after data validation. The DB does not yet prohibit history UPDATE/DELETE; the service/repository must enforce append-only behavior. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_mega_sale_events

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** A mega-sale promotion event and its registration/active windows. **Source:** V1 starting at line 99. **Target changes:** created_by NOT NULL already exists; add updated_by NOT NULL/system default following code #249; the status enum and literals are PENDING REVIEWER.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `name` | `varchar(200)` | No | — | Display name. | Existing V1 |
| `description` | `text` | Yes | — | Description. | Existing V1 |
| `banner_url` | `varchar(500)` | Yes | — | Event banner URL. | Existing V1 |
| `registration_start_at` | `timestamptz` | No | — | Start of seller registration. | Existing V1 |
| `registration_end_at` | `timestamptz` | No | — | End of seller registration. | Existing V1 |
| `start_at` | `timestamptz` | No | — | Start of the event's active period. | Existing V1 |
| `end_at` | `timestamptz` | No | — | End of the event's active period. | Existing V1 |
| `status` | `promo_mega_sale_event_status` (Proposed; source `varchar(20)`) | No | — | Business status; values and sources are listed in the enum catalogue above. | Existing V1; proposed enum conversion #249; see the full value set above |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: `CHECK (end_at > start_at)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (registration_end_at > registration_start_at)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CREATE INDEX idx_promo_events_status ON promo_mega_sale_events (status, start_at);`

**Relationships and FK actions:**

- This table declares no physical FK. UUID references to other services are explained in the field definitions; do not create cross-database FKs.

**Lifecycle:** Proposed DRAFT → REGISTRATION_OPEN → REGISTRATION_CLOSED → ACTIVE → ENDED; CANCELED handles cancellation where allowed by business rules. This transition graph has not yet been approved by the issue.

**Enforcement:** The DB only checks each end > start pair; it does not yet require registration to end before the event, prevent overlaps, or enforce ADMIN privileges. The service validates these rules and performs scheduled transitions. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_vouchers

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Voucher definition, conditions, budget, and validity period. **Source:** V1 starting at line 117. **Target changes:** created_by NOT NULL already exists; add updated_by; literals must be confirmed for every proposed enum.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `code` | `varchar(50)` | No | — | Voucher code unique across the table. | `UNIQUE`; Existing V1 |
| `name` | `varchar(200)` | No | — | Display name. | Existing V1 |
| `description` | `text` | Yes | — | Description. | Existing V1 |
| `source_type` | `commerce_voucher_source_type` (Proposed; source `varchar(20)`) | No | — | Voucher source; the enum value set is proposed and awaiting confirmation. | Existing V1; proposed enum conversion #249; see the full value set above |
| `owner_seller_id` | `uuid` | Yes | — | UUID of the seller owning the voucher, if any; a logical identity reference without a cross-database FK. | Existing V1 |
| `event_id` | `uuid` | Yes | — | Associated mega-sale event within commerce, if any. | `REFERENCES promo_mega_sale_events (id)`; Existing V1 |
| `discount_type` | `commerce_discount_type` (Proposed; source `varchar(20)`) | No | — | Discount calculation method: fixed amount or percentage; literal names are proposed. | Existing V1; proposed enum conversion #249; see the full value set above |
| `discount_value` | `numeric(19,2)` | No | — | Discount value interpreted by discount_type; retain numeric(19,2), even for a percentage, without changing the DB type automatically. | `CHECK (discount_value >= 0)`; Existing V1 |
| `max_discount_amount` | `numeric(19,2)` | Yes | — | Maximum discount amount; null means no cap is configured in the source data. | `CHECK (max_discount_amount IS NULL OR max_discount_amount >= 0)`; Existing V1 |
| `min_order_amount` | `numeric(19,2)` | No | `0` | Minimum order amount. | `CHECK (min_order_amount >= 0)`; Existing V1 |
| `currency_code` | `char(3)` | No | `'VND'` | Three-character currency code, default VND; V1 has no CHECK limiting it to VND. | Existing V1 |
| `funding_type` | `commerce_funding_type` (Proposed; source `varchar(20)`) | No | — | Voucher funding arrangement; the literal set is proposed and awaiting confirmation. | Existing V1; proposed enum conversion #249; see the full value set above |
| `seller_funding_percent` | `numeric(5,2)` | No | `0` | Seller funding percentage, 0–100, precision 5 and scale 2; not a monetary amount. | `CHECK (seller_funding_percent BETWEEN 0 AND 100)`; Existing V1 |
| `product_scope` | `commerce_product_scope` (Proposed; source `varchar(20)`) | No | — | Eligible product scope; the literal set is proposed and awaiting confirmation. | Existing V1; proposed enum conversion #249; see the full value set above |
| `claim_limit` | `integer` | Yes | — | Claim-count limit; null means unlimited in the source model. | `CHECK (claim_limit IS NULL OR claim_limit > 0)`; Existing V1 |
| `usage_limit` | `integer` | Yes | — | Usage-count limit; null means unlimited in the source model. | `CHECK (usage_limit IS NULL OR usage_limit > 0)`; Existing V1 |
| `discount_budget` | `numeric(19,2)` | Yes | — | Discount budget; null means no budget limit is set. | `CHECK (discount_budget IS NULL OR discount_budget >= 0)`; Existing V1 |
| `claim_start_at` | `timestamptz` | No | — | Start of the customer voucher-claim window. | Existing V1 |
| `claim_end_at` | `timestamptz` | No | — | End of the customer voucher-claim window. | Existing V1 |
| `valid_from` | `timestamptz` | No | — | Start of voucher redemption validity. | Existing V1 |
| `valid_until` | `timestamptz` | No | — | End of voucher redemption validity. | Existing V1 |
| `terms_text` | `text` | Yes | — | Terms text. | Existing V1 |
| `terms_version` | `integer` | No | `1` | Terms version; V1 has no CHECK > 0. | Existing V1 |
| `status` | `promo_voucher_status` (Proposed; source `varchar(20)`) | No | — | Business status; values and sources are listed in the enum catalogue above. | Existing V1; proposed enum conversion #249; see the full value set above |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: UNIQUE `(code)`, creating an implicit unique index; scope is the entire table unless a separate partial index is explicitly specified.
- Existing V1: column `discount_value` has `CHECK (discount_value >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `max_discount_amount` has `CHECK (max_discount_amount IS NULL OR max_discount_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `min_order_amount` has `CHECK (min_order_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `seller_funding_percent` has `CHECK (seller_funding_percent BETWEEN 0 AND 100)`. The constraint name is not explicitly declared.
- Existing V1: column `claim_limit` has `CHECK (claim_limit IS NULL OR claim_limit > 0)`. The constraint name is not explicitly declared.
- Existing V1: column `usage_limit` has `CHECK (usage_limit IS NULL OR usage_limit > 0)`. The constraint name is not explicitly declared.
- Existing V1: column `discount_budget` has `CHECK (discount_budget IS NULL OR discount_budget >= 0)`. The constraint name is not explicitly declared.
- Existing V1: `CHECK (valid_until > valid_from)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (claim_end_at > claim_start_at)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CREATE INDEX idx_vouchers_active ON promo_vouchers (status, valid_from, valid_until);`
- Existing V1: `CREATE INDEX idx_vouchers_event ON promo_vouchers (event_id);`

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_vouchers.event_id → promo_mega_sale_events.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; Nullable in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Proposed DRAFT → ACTIVE, possibly INACTIVE/CANCELED through administration, and EXPIRED over time. Do not change existing reservation snapshots when the original voucher changes.

**Enforcement:** The DB validates nonnegative numbers, percentages, positive non-null limits, and each time window. The service must define source/funding/scope mappings and check budgets/counts under concurrency; do not infer a 0–100 restriction on discount_value from the current nonnegative CHECK. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_event_sellers

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Seller registrations for mega sales and their review outcomes. **Source:** V1 starting at line 153. **Target changes:** Add audit columns from #249; the status enum/literals are proposed.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `event_id` | `uuid` | No | — | Associated mega-sale event within commerce, if any. | `REFERENCES promo_mega_sale_events (id)`; Existing V1 |
| `seller_id` | `uuid` | No | — | Seller UUID; a logical identity reference without a cross-service FK. | Existing V1 |
| `status` | `promo_event_seller_status` (Proposed; source `varchar(20)`) | No | — | Business status; values and sources are listed in the enum catalogue above. | Existing V1; proposed enum conversion #249; see the full value set above |
| `registered_at` | `timestamptz` | No | `now()` | Timestamp of the seller's event registration. | Existing V1 |
| `reviewed_by` | `uuid` | Yes | — | Reviewer's UUID, a logical identity reference; nullable before review. | Existing V1 |
| `reviewed_at` | `timestamptz` | Yes | — | Review timestamp. | Existing V1 |
| `review_note` | `text` | Yes | — | Review note. | Existing V1 |
| `withdrawn_at` | `timestamptz` | Yes | — | Timestamp of registration/participation withdrawal. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: `UNIQUE (event_id, seller_id)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- V1 declares no separate explicit secondary index for this table; PK/UNIQUE create implicit indexes. PostgreSQL does not automatically index every FK, so do not document such indexes as existing.

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_event_sellers.event_id → promo_mega_sale_events.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Proposed PENDING → APPROVED/REJECTED, with WITHDRAWN when a seller withdraws; retain reviewed_by/reviewed_at/review_note/withdrawn_at.

**Enforcement:** The DB only enforces the event FK and event/seller uniqueness. The service checks seller identity, the registration window, and the reviewer's decision; the DB does not yet constrain reviewed_* against status. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_customer_vouchers

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Voucher instances claimed by individual customers. **Source:** V1 starting at line 167. **Target changes:** Add audit columns from #249; the status enum/literals are proposed.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `customer_id` | `uuid` | No | — | UUID of the customer claiming the voucher; a logical identity reference without a cross-database FK. | Existing V1 |
| `voucher_id` | `uuid` | No | — | Voucher within commerce. | `REFERENCES promo_vouchers (id)`; Existing V1 |
| `status` | `promo_customer_voucher_status` (Proposed; source `varchar(20)`) | No | — | Business status; values and sources are listed in the enum catalogue above. | Existing V1; proposed enum conversion #249; see the full value set above |
| `claimed_at` | `timestamptz` | No | `now()` | Timestamp when the customer claimed the voucher. | Existing V1 |
| `expires_at` | `timestamptz` | No | — | Expiry/validity deadline of the claimed voucher. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: `UNIQUE (customer_id, voucher_id)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CREATE INDEX idx_customer_vouchers_status ON promo_customer_vouchers (customer_id, status);`

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_customer_vouchers.voucher_id → promo_vouchers.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Proposed CLAIMED → RESERVED → USED, or EXPIRED/REVOKED; the service/job determines expiry from expires_at rather than the DB automatically changing status.

**Enforcement:** UNIQUE(customer_id,voucher_id) currently permits only one instance per customer/voucher. Allowing multiple claims requires a decision to change the constraint; do not remove uniqueness automatically. The service checks claim authorization, limits, time windows, and concurrency. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_voucher_products

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Join table defining products eligible for a voucher. **Source:** V1 starting at line 179. **Target changes:** Retain the composite primary key; add audit columns from #249 without introducing an id column.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `voucher_id` | `uuid` | No | — | Voucher within commerce. | `REFERENCES promo_vouchers (id) ON DELETE CASCADE`; Existing V1; composite-PK component |
| `product_id` | `uuid` | No | — | Catalog product UUID; a logical reference without an FK to catalog_db. | Existing V1; composite-PK component |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: `PRIMARY KEY (voucher_id, product_id)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- V1 declares no separate explicit secondary index for this table; PK/UNIQUE create implicit indexes. PostgreSQL does not automatically index every FK, so do not document such indexes as existing.

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_voucher_products.voucher_id → promo_vouchers.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Create/delete links when configuring a voucher; deleting a voucher removes its links through CASCADE. Usage snapshots do not depend on the continued existence of these links.

**Enforcement:** The DB checks the voucher FK and composite PK; product_id is a logical catalog reference. The service validates product_scope and permission to configure the products, without an FK across catalog_db. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_voucher_sellers

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** A seller's acceptance of voucher participation, including a terms/percentage snapshot. **Source:** V1 starting at line 186. **Target changes:** Add audit columns from #249; the status enum/literals are proposed; retain accepted_by separately.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `voucher_id` | `uuid` | No | — | Voucher within commerce. | `REFERENCES promo_vouchers (id)`; Existing V1 |
| `event_seller_id` | `uuid` | No | — | Seller registration for a mega-sale event within commerce. | `REFERENCES promo_event_sellers (id)`; Existing V1 |
| `status` | `promo_voucher_seller_status` (Proposed; source `varchar(20)`) | No | — | Business status; values and sources are listed in the enum catalogue above. | Existing V1; proposed enum conversion #249; see the full value set above |
| `accepted_terms_version` | `integer` | No | — | Terms version accepted by the seller; V1 has no CHECK > 0. | Existing V1 |
| `accepted_terms_snapshot` | `text` | Yes | — | Terms text snapshotted at acceptance. | Existing V1 |
| `seller_funding_percent_snapshot` | `numeric(5,2)` | No | — | Seller funding percentage snapshot, 0–100, numeric(5,2). | `CHECK (seller_funding_percent_snapshot BETWEEN 0 AND 100)`; Existing V1 |
| `accepted_by` | `uuid` | No | — | UUID of the party accepting the terms; a logical identity reference without a cross-database FK. | Existing V1 |
| `accepted_at` | `timestamptz` | No | `now()` | Terms acceptance timestamp. | Existing V1 |
| `withdrawn_at` | `timestamptz` | Yes | — | Timestamp of registration/participation withdrawal. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: column `seller_funding_percent_snapshot` has `CHECK (seller_funding_percent_snapshot BETWEEN 0 AND 100)`. The constraint name is not explicitly declared.
- Existing V1: `UNIQUE (voucher_id, event_seller_id)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- V1 declares no separate explicit secondary index for this table; PK/UNIQUE create implicit indexes. PostgreSQL does not automatically index every FK, so do not document such indexes as existing.

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_voucher_sellers.voucher_id → promo_vouchers.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.
- Local physical FK within `commerce_db`: `promo_voucher_sellers.event_seller_id → promo_event_sellers.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Proposed PENDING → ACCEPTED/REJECTED, with WITHDRAWN on withdrawal; retain the terms snapshot and accepted_at for audit.

**Enforcement:** The DB checks the two commerce FKs, voucher/event_seller uniqueness, and the 0–100 percentage. The service checks that the registration event matches the voucher event, the terms version, and the accepting party's identity; V1 has no CHECK for these rules. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_voucher_usages

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Reserve, redeem, or release a voucher entitlement for an order. **Source:** V1 starting at line 202. **Target changes:** Add audit columns from #249; the status enum/literals are proposed.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `customer_voucher_id` | `uuid` | No | — | Customer's claimed voucher instance. | `REFERENCES promo_customer_vouchers (id)`; Existing V1 |
| `order_id` | `uuid` | No | — | Associated commerce order. | `REFERENCES orders (id)`; Existing V1 |
| `idempotency_key` | `varchar(100)` | No | — | Business idempotency key; currently UNIQUE across the table, not merely per user. | `UNIQUE`; Existing V1 |
| `status` | `promo_voucher_usage_status` (Proposed; source `varchar(25)`) | No | — | Business status; values and sources are listed in the enum catalogue above. | Existing V1; proposed enum conversion #249; see the full value set above |
| `eligible_amount` | `numeric(19,2)` | No | — | Amount eligible for discount calculation. | `CHECK (eligible_amount >= 0)`; Existing V1 |
| `discount_amount` | `numeric(19,2)` | No | — | Discount amount, interpreted as an allocation where the table's purpose specifies one. | `CHECK (discount_amount >= 0 AND discount_amount <= eligible_amount)`; Existing V1 |
| `seller_funded_amount` | `numeric(19,2)` | No | `0` | Discount amount funded by the seller. | `CHECK (seller_funded_amount >= 0)`; Existing V1 |
| `platform_funded_amount` | `numeric(19,2)` | No | `0` | Discount amount funded by the platform. | `CHECK (platform_funded_amount >= 0)`; Existing V1 |
| `currency_code` | `char(3)` | No | `'VND'` | Three-character currency code, default VND; V1 has no CHECK limiting it to VND. | Existing V1 |
| `voucher_snapshot` | `jsonb` | No | — | Non-null JSON snapshot of the voucher/terms when redemption entitlement was reserved. | Existing V1 |
| `reserved_at` | `timestamptz` | No | `now()` | Timestamp when a voucher use was reserved. | Existing V1 |
| `reserved_until` | `timestamptz` | No | — | Reservation deadline for the voucher use. | Existing V1 |
| `redeemed_at` | `timestamptz` | Yes | — | Successful redemption timestamp. | Existing V1 |
| `released_at` | `timestamptz` | Yes | — | Timestamp when the reserved use was released. | Existing V1 |
| `release_reason` | `varchar(100)` | Yes | — | Reason for releasing the voucher use. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: UNIQUE `(idempotency_key)`, creating an implicit unique index; scope is the entire table unless a separate partial index is explicitly specified.
- Existing V1: column `eligible_amount` has `CHECK (eligible_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `discount_amount` has `CHECK (discount_amount >= 0 AND discount_amount <= eligible_amount)`. The constraint name is not explicitly declared.
- Existing V1: column `seller_funded_amount` has `CHECK (seller_funded_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `platform_funded_amount` has `CHECK (platform_funded_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: `CHECK (discount_amount = seller_funded_amount + platform_funded_amount)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CREATE INDEX idx_voucher_usages_order ON promo_voucher_usages (order_id);`
- Existing V1: `CREATE INDEX idx_voucher_usages_expiring ON promo_voucher_usages (status, reserved_until);`

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_voucher_usages.customer_voucher_id → promo_customer_vouchers.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.
- Local physical FK within `commerce_db`: `promo_voucher_usages.order_id → orders.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Proposed RESERVED → REDEEMED or RELEASED/EXPIRED; record reserved_until, redeemed_at/released_at, and release_reason. Handle idempotency through idempotency_key without applying quota increments/decrements again.

**Enforcement:** The DB guarantees that the discount does not exceed the eligible amount, that funding sums match, and that numbers are nonnegative. The service checks limits/budgets under concurrency, order status, and voucher status; the local orders FK is retained. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### commerce_db.promo_voucher_usage_allocations

**Owner:** `commerce-service` / `commerce_db`. **Purpose:** Allocate discounts and refund/reversal amounts to individual order lines and sellers. **Source:** V1 starting at line 226. **Target changes:** Add audit columns from #249; do not introduce a status column absent from the source.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Record identifier. | `PRIMARY KEY`; Existing V1 |
| `voucher_usage_id` | `uuid` | No | — | Voucher reservation/redemption being allocated. | `REFERENCES promo_voucher_usages (id) ON DELETE CASCADE`; Existing V1 |
| `order_item_id` | `uuid` | No | — | Order line within commerce receiving the discount allocation. | `REFERENCES order_items (id)`; Existing V1 |
| `seller_id` | `uuid` | No | — | Seller UUID; a logical identity reference without a cross-service FK. | Existing V1 |
| `voucher_seller_id` | `uuid` | Yes | — | Seller's voucher participation record; nullable. | `REFERENCES promo_voucher_sellers (id)`; Existing V1 |
| `eligible_amount` | `numeric(19,2)` | No | — | Amount eligible for discount calculation. | `CHECK (eligible_amount >= 0)`; Existing V1 |
| `discount_amount` | `numeric(19,2)` | No | — | Discount amount, interpreted as an allocation where the table's purpose specifies one. | `CHECK (discount_amount >= 0)`; Existing V1 |
| `seller_funded_amount` | `numeric(19,2)` | No | `0` | Discount amount funded by the seller. | `CHECK (seller_funded_amount >= 0)`; Existing V1 |
| `platform_funded_amount` | `numeric(19,2)` | No | `0` | Discount amount funded by the platform. | `CHECK (platform_funded_amount >= 0)`; Existing V1 |
| `reversed_discount_amount` | `numeric(19,2)` | No | `0` | Total discount amount reversed/refunded. | `CHECK (reversed_discount_amount >= 0)`; Existing V1 |
| `reversed_seller_amount` | `numeric(19,2)` | No | `0` | Seller-funded amount reversed/refunded. | `CHECK (reversed_seller_amount >= 0)`; Existing V1 |
| `reversed_platform_amount` | `numeric(19,2)` | No | `0` | Platform-funded amount reversed/refunded. | `CHECK (reversed_platform_amount >= 0)`; Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp, with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Last update timestamp; DEFAULT now() applies only to INSERT, so the service must refresh it on changes. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor of the latest write/update; append-only history is not rewritten | Per code #249/0bc9da1 and the user's decision: NOT NULL/system default; authenticated writers assign the actor, without a cross-database FK. |

**Exact source constraints/indexes and target effects:**

- Existing V1: PK `(id)`; `DEFAULT uuidv7()` for the identifier. The constraint name is not explicitly declared.
- Existing V1: column `eligible_amount` has `CHECK (eligible_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `discount_amount` has `CHECK (discount_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `seller_funded_amount` has `CHECK (seller_funded_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `platform_funded_amount` has `CHECK (platform_funded_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `reversed_discount_amount` has `CHECK (reversed_discount_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `reversed_seller_amount` has `CHECK (reversed_seller_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: column `reversed_platform_amount` has `CHECK (reversed_platform_amount >= 0)`. The constraint name is not explicitly declared.
- Existing V1: `UNIQUE (voucher_usage_id, order_item_id)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (discount_amount = seller_funded_amount + platform_funded_amount)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (reversed_discount_amount = reversed_seller_amount + reversed_platform_amount)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (reversed_discount_amount <= discount_amount)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (reversed_seller_amount <= seller_funded_amount)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- Existing V1: `CHECK (reversed_platform_amount <= platform_funded_amount)`. The constraint name is not explicitly declared in SQL; PostgreSQL generates it.
- V1 declares no separate explicit secondary index for this table; PK/UNIQUE create implicit indexes. PostgreSQL does not automatically index every FK, so do not document such indexes as existing.

**Relationships and FK actions:**

- Local physical FK within `commerce_db`: `promo_voucher_usage_allocations.voucher_usage_id → promo_voucher_usages.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.
- Local physical FK within `commerce_db`: `promo_voucher_usage_allocations.order_item_id → order_items.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; NOT NULL in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.
- Local physical FK within `commerce_db`: `promo_voucher_usage_allocations.voucher_seller_id → promo_voucher_sellers.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; Nullable in the source. SQL does not declare DEFERRABLE, so NOT DEFERRABLE applies by default.

**Lifecycle:** Create allocations alongside usage; increment reversed_* amounts in a controlled way during refunds/reversals while preserving original values. Deleting usage currently cascades to allocations.

**Enforcement:** The DB validates funding/reversal sums and each reversal cap. The service checks that order_item belongs to the usage's order, seller consistency, and concurrency; individual FKs do not guarantee these derived relationships automatically. The service/JPA auditing or data-writing layer supplies the audit actors; PostgreSQL does not know the JWT identity automatically, and V1/V2 have no audit trigger.

### Summary of mandatory changes requiring review for `commerce_db`

- #240: payment_transactions and its indexes leave commerce through new migration/backfill, not by editing applied V1. orders.memo_code leaves the target; the shared QR memo belongs to payment_sessions.memo_code. Keep orders.payment_due_at and the existing payment_note design for #157 reconciliation reasons.
- Checkout is the cart-submit/pricing operation; order_groups is its persisted result. Add the 13-field group and retain orders.order_group_id as a NOT NULL local FK with UNIQUE(order_group_id,seller_id). No checkout table, group revision/fingerprint, payment_state, last-payment-session tuple or replacement-session fields are introduced.
- Atomically create the group, all seller orders/items/pricing/voucher allocations and one complete CheckoutPaymentRequested event. Same request key/body returns the same group/children; compare stored snapshots and use deterministic per-seller SHA-256 child keys. Different bodies do not overwrite the purchase. Amend the older #113/BR-109/#121 group/payment contracts explicitly.
- CheckoutPaymentConfirmed identifies orderGroupId and the full instructionId/orderId/amount list; commerce commits eligible child PAID/history and separate OrderPaid events together. Before payment, cancel the whole group or expire the single session; do not cancel one child and revise/reissue its QR. Asynchronous cancellation races preserve successful money and compensate canceled children without resurrecting them.
- After payment, retain existing per-child shipment handover coordination and last_refund_version. R1 refunds.order_id UNIQUE permits one agreed operation per order; completion moves REFUND_REQUESTED→REFUNDED. A smaller explicit manual refund remains exactly that amount and exposes the actual remaining paid balance; it does not open repeated partial refunds. Automatic full paid-child cancellation is scoped to that instruction's funded amount.
- Preserve the eight #116 order states and the spelling CANCELED. Keep all eight promotion tables, funding/reversal constraints, conditional indexes, NULL/default definitions and #249 NOT NULL system-actor audit policy. Enum/promotion semantics remain PENDING REVIEWER, not silently approved by this rewrite.
- Legacy/AUCTION orders receive verified singleton groups and the corresponding single-session instruction mapping, preserving source IDs, deadlines, money evidence and original keys. Verify the FK/backfill before enforcing NOT NULL; do not fabricate historical payment facts.
- Selective item-return eligibility/allocation/API/event contracts remain separate; no return tables or implemented item-return behavior are claimed.

### commerce_db.outbox

**Owner/source:** commerce_db; commerce V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Outbox row ID; proposed to remain identical to eventId across publication/retries | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Aggregate ID as text, potentially a UUID or business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published according to the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraint` |
| `occurred_at` | `timestamptz` | No | `now()` | Event occurrence timestamp | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication timestamp; NULL means not yet marked as published | `No additional constraint` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is specified in the field definition; there is no FK to a business table or another database. Do not automatically add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business behavior:** commit the business change and outbox INSERT in the same local transaction. The row ID is proposed to equal eventId in the envelope; relay retries retain the same eventId, aggregate key, and payload without creating a new ID. A relay may resend after crashing between Kafka acknowledgment and published_at: consumers must deduplicate; outbox does not guarantee exactly-once delivery. The selected design stores the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends the original envelope, preserving eventId across every retry without double-wrapping. `aggregate_id` is varchar(100); do not automatically change it to UUID or an FK, because some aggregates use text keys. Retain published outbox rows for at least 90 days; do not purge unpublished rows. Business ledgers/unique keys and the processed_events retention policy protect the corresponding replays.

### commerce_db.processed_events

**Owner/source:** commerce_db; commerce V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers of the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Local processing completion timestamp | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not replace it with event_id alone. No FK to another service's outbox/event_logs.

**Business behavior:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. Marker uniqueness prevents the same event from being handled twice by one handler, but does not prevent different eventIds for the same business operation, so the domain still needs the corresponding order/provider-reference uniqueness. If an envelope lacks data required to apply it, do not mark it successfully processed and skip it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or the ledger lifetime. Replay beyond the window requires restore/backfill workflows and business uniqueness controls; do not imply indefinite deduplication after purge. Consumer names are stable per handler, not per instance.

### commerce_db.shedlock

**Owner/source:** commerce_db; commerce V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `name` | `varchar(64)` | No | — | Job lock name, unique within the database | `PRIMARY KEY` |
| `lock_until` | `timestamptz` | No | — | Lease expiry timestamp | `NOT NULL` |
| `locked_at` | `timestamptz` | No | — | Lock acquisition timestamp | `NOT NULL` |
| `locked_by` | `varchar(255)` | No | — | Identifier of the instance holding the lease | `NOT NULL` |

**Keys/constraints/indexes:**
- PK `(name)`; no secondary index or FK; no defaults for timestamps/owner.

**Business behavior:** the scheduler writes and renews leases through a local lock provider. Do not use this table as a replacement for business idempotency or foreign-key enforcement. lockAtMostFor/lockAtLeastFor are job settings, not literal CHECK constraints. Retain the existing definition; new services add a scheduler-lock table only when their implementation issue confirms the requirement.

<a id="auction-db"></a>

## auction-service / auction_db

**Scope, sources and implementation choices**

- Retain the three business tables `auctions`, `bids`, `auction_watchers` in `auction_db`; technical tables are documented separately in the shared schema section.
- Physical sources: `auction-service/src/main/resources/db/migration/V1__init.sql` and `V2__schema_corrections.sql`, baseline commit `8f5276388d0274bb9ec0d9f3bdbae9dc2421c960`. V2 already converted all five auction/bid money columns below to `numeric(19,2)`. Do not revert to `bigint` based on the old V1 ERD.
- #249 requires PostgreSQL enums for auction and bid statuses. The names `auction_status`, `bid_status` exist in implementation branch #249; retain the V1 value sets unless an approved business issue changes them. `visibility` uses auction_visibility from implementation branch #249; the PUBLIC/RESTRICTED set is unchanged.
**Audit policy:** according to code #249 and the user's choices: created_by/updated_by are UUID NOT NULL, with the all-zero system UUID as the default; JPA/native writers, backfill and the immutable creator follow the shared conventions.

### auction_db.auctions

**Owner / purpose:** `auction-service` / `auction_db`; the auction session and product/SKU/seller snapshots, bidding progress, result and payment deadline. **Sources:** auction V1+V2, #67/#140/#146/#249. **Changes:** retain the table; convert `status` to the required enum and add audit actors. The table does not read the catalog/user/commerce DBs directly.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Auction PK. | V1 — Existing |
| `product_id` | `uuid` | No | None | Logical reference to the catalog product. | V1 — Existing |
| `sku_id` | `uuid` | No | None | Logical reference to the catalog SKU. | V1 — Existing |
| `seller_id` | `uuid` | No | None | Logical reference to the profile/seller. | V1 — Existing |
| `title` | `varchar(255)` | No | None | Title snapshot; SQL has no CHECK against an empty string. | V1 — Existing |
| `image_url` | `varchar(500)` | Yes | None | Image snapshot; the DB permits NULL, so any non-NULL payload requirement must be handled by the service/contract. | V1 — Existing |
| `starting_price` | `numeric(19,2)` | No | None | Starting price; CHECK `>= 0`. | V1 + V2 / #67 — Existing |
| `bid_increment` | `numeric(19,2)` | No | None | Bid increment; CHECK `> 0`. | V1 + V2 / #67 — Existing |
| `current_price` | `numeric(19,2)` | No | None | Current price; CHECK `>= 0`. | V1 + V2 / #67 — Existing |
| `leader_bidder_id` | `uuid` | Yes | None | Logical user ID of the current leading bidder. | V1 — Existing |
| `bid_count` | `integer` | No | `0` | Bid count; CHECK `>= 0`. | V1 — Existing |
| `currency` | `char(3)` | No | `'VND'` | Currency code; the baseline has no CHECK restricting it to VND. | V1 — Existing |
| `start_at` | `timestamptz` | No | None | Start time. | V1 — Existing |
| `end_at` | `timestamptz` | No | None | Current end time; CHECK `end_at > start_at`. | V1 — Existing |
| `original_end_at` | `timestamptz` | No | None; the service assigns the initial `end_at` | End time before extensions; DEFAULT does not automatically copy another column. | V1 / #67 — Existing NOT NULL; service initialization under #140 requires consistent wording |
| `extension_count` | `smallint` | No | `0` | Extension count; CHECK `>= 0`. | V1 — Existing |
| `visibility` | `auction_visibility` | No | `'PUBLIC'::auction_visibility` | CHECK `PUBLIC`, `RESTRICTED`. | Per implementation #249; the two values PUBLIC and RESTRICTED are unchanged. |
| `status` | `auction_status` — target enum name | No | `'CREATED'` | `CREATED`, `SCHEDULED`, `ACTIVE`, `ENDED`, `WON`, `SETTLED`, `FAILED`, `PAYMENT_TIMEOUT`, `CANCELLED`. | V1 — Existing values; #249 — enum and name per implementation #249 |
| `winner_id` | `uuid` | Yes | None | Logical user ID of the winner. | V1 — Existing |
| `final_price` | `numeric(19,2)` | Yes | None | Final price; CHECK `final_price IS NULL OR final_price >= 0`. | V1 + V2 / #67 — Existing |
| `payment_due_at` | `timestamptz` | Yes | None | Payment deadline after the result is determined; it is not read from the commerce DB. | V1 — Existing |
| `order_id` | `uuid` | Yes | None | Logical reference to the created commerce order. | V1 — Existing |
| `cancelled_by` | `varchar(20)` | Yes | None | Marker for the cancelling party's source/type under the business contract; the baseline has no CHECK or agreed value set. This is not an actor UUID column. | CHECK restricts non-NULL values to SELLER, ADMIN, SYSTEM; the actor UUID comes from audit/event data. Selected for the cancellation audit in SRS §3.5.1. |
| `cancelled_reason` | `varchar(255)` | Yes | None | Cancellation reason. | V1 — Existing |
| `created_at` | `timestamptz` | No | `now()` | Creation time. | V1 — Existing |
| `updated_at` | `timestamptz` | No | `now()` | Modification time; the service must update it. | V1 — Existing |
| `deleted_at` | `timestamptz` | Yes | None | Soft-delete marker; it does not automatically delete bids/watchers. | V1 — Existing |
| `version` | `bigint` | No | `0` | Optimistic locking; the baseline has no nonnegative CHECK. | V1 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `cancelled_at` | `timestamptz` | Yes | None | Actual auction cancellation time | SRS §3.5.1 / BR-148 Cancellation Audit; a field added by the design choice, without a now() default before cancellation. |
| `settled_at` | `timestamptz` | Yes | None | Time when settlement finalization was committed | SRS §3.5.3 / BR-169; updated_at must not replace this business timestamp. |

**Keys, constraints, indexes:** PK `(id)`; this table has no physical FK. Baseline CHECKs: `starting_price >= 0`, `bid_increment > 0`, `current_price >= 0`, `bid_count >= 0`, `extension_count >= 0`, `final_price IS NULL OR final_price >= 0`, `end_at > start_at`; the visibility CHECK has exactly the two values listed above; the nine-value status CHECK is replaced by an enum with the same domain when #249 is implemented. Four nonunique, nonpartial B-tree indexes: `idx_auctions_closing (status, end_at)`, `idx_auctions_upcoming (status, start_at)`, `idx_auctions_seller (seller_id, status)`, `idx_auctions_product (product_id)`. The baseline has no UNIQUE on product/SKU/order or CHECK comparing `original_end_at` with the other two times.

**Relationships:** one auction has 0..N bids and 0..N watchers through FKs from the child tables. Product, SKU, seller, leader, winner and order are logical references; their UUIDs must match the owning service contracts, with no cross-database FKs. Nullable leader/winner/order columns represent results not yet available; they do not guarantee that every auction has a winner or an order.

**Lifecycle / enforcement:** the DB checks basic domains/numeric values; the service enforces the state machine, permitted bidding/cancellation/extension times, ownership, eligibility, leader/bid_count updates and settlement. Initialize `original_end_at = end_at` in the service within the auction creation transaction; SQL DEFAULT cannot reference another column. Each bid must update the auction together with the bid and corresponding outbox write; concurrency control uses version/locking under #146, rather than merely declaring a version column. `numeric(19,2)` alone does not enforce bid increments or VND validity. Soft deletion does not cascade through FKs; cascading occurs only on physical auction DELETE.

**Selected source reconciliation:** original_end_at remains NOT NULL and is assigned the initial end_at under V1/#67; the old nullable wording in #140 must be updated during implementation. cancelled_by uses the SELLER/ADMIN/SYSTEM categories, cancelled_at records cancellation time, and the image remains nullable in the DB. These are target choices based on the SRS audit requirements, not claims that the new fields already exist in migrations.


**Cancellation/image fields:** retain cancelled_by varchar(20) as the actor category `SELLER`, `ADMIN`, `SYSTEM` (target CHECK); the actor UUID comes from audit/event data, rather than being placed in the actor-category varchar. Add cancelled_at timestamptz NULL to record cancellation time under SRS §3.5.1/the audit BR; the cancellation reason is required by the service when cancelling. image_url remains nullable in the DB because the source image may not yet exist; the API does not fabricate images. original_end_at remains NOT NULL and is initialized to the initial end_at according to the code/#67.




**Added auction constraints from SRS/BR:** `CREATE UNIQUE INDEX uq_auctions_active_product ON auctions(product_id) WHERE status='ACTIVE' AND deleted_at IS NULL` protects BR-134 even when two workers activate concurrently. Creation/configuration and activation check existing active auctions under a logical product/seller lock; the MAX_ACTIVE_AUCTIONS_PER_SELLER limit (default 5) is checked in the service using a transaction/lock, not a fictitious cross-table CHECK. `settled_at` is assigned once when the winner/final_price/settlement outbox is finalized; replay does not change this timestamp under BR-169/171. CREATED/SCHEDULED statuses are not yet ACTIVE, so the index does not prohibit all future sessions for the same product; the scheduler must still check the conditions at activation.

### auction_db.bids

**Owner / purpose:** `auction-service` / `auction_db`; individual bids, their result statuses and idempotency keys. **Sources:** auction V1+V2, #67/#146/#147/#249. **Changes:** retain V2's `amount numeric(19,2)`; convert status to the required enum; add audit actors.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Bid PK. | V1 — Existing |
| `auction_id` | `uuid` | No | None | Local FK to `auctions.id`, DELETE CASCADE. | V1 — Existing |
| `bidder_id` | `uuid` | No | None | Logical user ID of the bidder. | V1 — Existing |
| `amount` | `numeric(19,2)` | No | None | Bid amount; CHECK `amount > 0`. | V1 + V2 / #67 — Existing |
| `status` | `bid_status` — target enum name | No | `'PLACED'` | `PLACED`, `OUTBID`, `WON`, `CANCELLED`. | V1 — Existing values; #249 — enum and name per implementation #249 |
| `idempotency_key` | `varchar(100)` | No | None | Table-wide UNIQUE; the baseline does not scope uniqueness by bidder/auction. | V1 / #147 — Existing / Required to retain |
| `placed_at` | `timestamptz` | No | `now()` | Bid placement time; the baseline has no `created_at`/`updated_at`. | V1 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys, constraints, indexes:** PK `(id)`; UNIQUE `(idempotency_key)`; CHECK `amount > 0`; a four-value status domain. FK `(auction_id) REFERENCES auctions(id) ON DELETE CASCADE ON UPDATE NO ACTION`, not deferred. Three nonunique, nonpartial B-tree indexes: `idx_bids_auction_amount (auction_id, amount DESC)`, `idx_bids_auction_time (auction_id, placed_at DESC)`, `idx_bids_bidder (bidder_id, placed_at DESC)`. Idempotency uniqueness is table-wide; changing its scope requires a separate decision and migration.

**Relationships:** auction 1 ↔ 0..N bids through the FK; each bid belongs to exactly one auction. The bidder is a logical user reference; no FK to user/auth.

**Lifecycle / enforcement:** the DB rejects nonpositive amounts and duplicate idempotency keys, but the service must also verify that the same key has the same request content, bidder authorization/eligibility, an open session and a valid price/increment under #146. CHECK `amount > 0` alone does not replace bidding business rules. Bid status and the auction leader/current price must be updated consistently in a transaction. Tie-breaking/winner selection ordering belongs to settlement; the existing indexes do not themselves ensure a single WON bid or a total order when `placed_at` values tie. The baseline bids table has no soft delete or version; do not add a unique-winner constraint or extra timestamps without a requirement.

### auction_db.auction_watchers

**Owner / purpose:** `auction-service` / `auction_db`; auction watchers. **Sources:** auction V1, #249. **Changes:** retain the composite key and add audit actors; do not add a surrogate ID.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `auction_id` | `uuid` | No | None | PK component; FK to `auctions.id`, DELETE CASCADE. | V1 — Existing |
| `user_id` | `uuid` | No | None | PK component; logical user ID of the watcher. | V1 — Existing |
| `created_at` | `timestamptz` | No | `now()` | Time when watching began. | V1 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys, constraints, indexes:** composite PK `(auction_id, user_id)` ensures one watcher record per user per auction; there is no `id` PK. FK `(auction_id) REFERENCES auctions(id) ON DELETE CASCADE ON UPDATE NO ACTION`, not deferred. Separate index `idx_watchers_user (user_id)` — B-tree, nonunique, nonpartial. No additional CHECK.

**Relationships:** auction 1 ↔ 0..N watchers through the FK; user 1 ↔ 0..N watcher records is a logical reference. Together these form a many-to-many auction/user relationship through the junction table.

**Lifecycle / enforcement:** the service verifies the authenticated user and watching eligibility. The PK protects against duplicate watch requests; the service determines the contract response for retries. Unwatching deletes the link row; the baseline has no `deleted_at`, `updated_at` or version. Physically deleting an auction cascades to watchers; soft-deleting an auction does not trigger that cascade.

### auction_db.outbox

**Owner / source:** auction_db; auction V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Outbox record ID; proposed to remain stable and identical to eventId during publishing/retries | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type name | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Text aggregate ID, which may be a UUID or business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraints` |
| `occurred_at` | `timestamptz` | No | `now()` | Time when the event occurred | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not yet marked as published | `No additional constraints` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is already specified on the field; there is no FK to a business table or another database. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business behavior:** the business change and outbox INSERT share one local transaction. The row ID is proposed to equal eventId in the envelope; relay retries retain the same eventId, aggregate key and payload rather than generating a new ID. A relay may resend if it crashes after Kafka acknowledgement but before published_at: consumers must deduplicate; outbox does not itself provide exactly-once delivery. The selected approach stores the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends the envelope unchanged and preserves eventId on every retry without double-wrapping. `aggregate_id` is varchar(100); do not change it to UUID or an FK because some aggregates use text keys. Published outbox records are retained for at least 90 days; unpublished records are not purged. The business ledger/uniqueness rules and processed_events policy in the retention section protect their respective replay scenarios.

### auction_db.processed_events

**Owner / source:** auction_db; auction V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers of the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Time when local processing completed | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not change it to event_id alone. No FK to another service's outbox/event_logs.

**Business behavior:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. Marker uniqueness prevents the same event from being processed twice by one handler, but does not block different eventIds for the same business operation, so the domain still needs the corresponding unique order/provider reference. If the envelope lacks the data needed to apply it, do not mark it successfully processed and discard it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or for the ledger's lifetime. Replay outside that window must use a restore/backfill workflow and business uniqueness; do not imply indefinite deduplication after purging. Consumer names are stable per handler, not per instance.

### auction_db.shedlock

**Owner / source:** auction_db; auction V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `name` | `varchar(64)` | No | — | Job lock name, unique within the database | `PRIMARY KEY` |
| `lock_until` | `timestamptz` | No | — | Lease expiration time | `NOT NULL` |
| `locked_at` | `timestamptz` | No | — | Lock acquisition time | `NOT NULL` |
| `locked_by` | `varchar(255)` | No | — | Identifier of the instance holding the lease | `NOT NULL` |

**Keys/constraints/indexes:**
- PK `(name)`; no secondary index or FK; no defaults for the timestamps/owner.

**Business behavior:** the scheduler writes and renews the lease through a local lock provider. This table does not replace business idempotency or foreign-key enforcement. The lockAtMostFor/lockAtLeastFor durations are job configuration, not CHECK literals. Retain the existing definition; a new service adds a scheduler lock table only when its implementation issue confirms that it is needed.

<a id="inventory-db"></a>

## inventory-service / inventory_db

**Scope, sources and implementation choices**

- Target owner: `inventory-service`, renamed from `fulfillment-service` under #234/#243. The four retained business tables are `warehouses`, `inventory_balances`, `inventory_reservations`, `inventory_movements`. `shipments` and `shipment_history` move to `shipment_db`; inventory must not retain another writable copy.
- Reviewed baseline: commit `8f5276388d0274bb9ec0d9f3bdbae9dc2421c960`; `fulfillment-service/src/main/resources/db/migration/V1__init.sql` and `V2__schema_corrections.sql`. V2 only adds the default warehouse, without structural changes. #130/#131/#134/#136/#199/#200 provide the business rules; #243 implements the schema split; #249 adds audit actors and coordinates enums; #250 is the issue for this schema document.
- **Existing** means present in the SQL baseline; **Required** means a change required by the cited issue; **Proposed** means a draft design choice, not yet considered an approved decision or an executed migration.
- Inventory named enums from code #249: warehouse_status, inventory_reservation_status, inventory_movement_type. The inventory DB owns its own types; movement_type is extended to a superset for the INTAKE/COMMIT/ADJUST flows rather than discarding historical data.
**Audit policy:** according to code #249 and the user's choices: created_by/updated_by are UUID NOT NULL, with the all-zero system UUID as the default; JPA/native writers, backfill and the immutable creator follow the shared conventions.
- Do not add `created_at`, `updated_at`, `deleted_at` or `version` to a table that lacks those columns in the baseline. The timestamp and optimistic-locking rules below describe each table specifically. `DEFAULT now()` supplies a value only on INSERT; it does not automatically update on UPDATE.

### inventory_db.warehouses

**Owner / purpose:** `inventory-service` / `inventory_db`; warehouse catalogue used by stock balances, reservations and movements. **Sources:** fulfillment V1; fulfillment V2 seeds `code = 'WH_001'`, `name = 'Default warehouse'`; #243/#249. **Changes:** retain the table and IDs when changing DBs; add two audit actors; propose converting `status` from `varchar(20)` to an equivalent enum.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | PK; warehouse identifier. | V1 — Existing |
| `code` | `varchar(40)` | No | None | Unique warehouse code; UNIQUE. | V1 — Existing |
| `name` | `varchar(150)` | No | None | Warehouse name; SQL has no CHECK against an empty string. | V1 — Existing |
| `address` | `text` | Yes | None | Warehouse address. | V1 — Existing |
| `status` | `warehouse_status` — target enum name | No | `'ACTIVE'` | `ACTIVE`, `INACTIVE`, `MAINTENANCE`; the baseline is `varchar(20)` + CHECK with exactly these three values. | V1 — Existing values; #243/#249 — type per implementation #249 |
| `created_at` | `timestamptz` | No | `now()` | Creation time. | V1 — Existing |
| `updated_at` | `timestamptz` | No | `now()` | Update time; the service must update it on modification. | V1 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys, constraints, indexes:** PK `(id)`; UNIQUE `(code)` — both create corresponding unique B-tree indexes in PostgreSQL. Apart from those two key indexes, V1 declares no separate indexes. The baseline CHECK `status IN ('ACTIVE','INACTIVE','MAINTENANCE')` is replaced by an enum domain with the same values when applying the named enum from code #249. This table has no outgoing FK.

**Relationships:** one warehouse has 0..N balances, 0..N reservations and 0..N movements; every child record must belong to exactly one warehouse through the `warehouse_id` FK. After the split, a shipment reference to a warehouse is a logical UUID reference only.

**Lifecycle / enforcement:** the service manages status; the DB restricts its value domain but does not itself validate transitions. #199 requires a warehouse in `MAINTENANCE` to block intake: the service must check warehouse status in the intake flow, rather than describe this as a local CHECK on the movement/balance table. There is no soft-delete column. Child-table FKs use `ON DELETE NO ACTION`, so a referenced warehouse cannot be deleted. During provisioning or data migration, `WH_001` is a **code**, not a UUID: look up `warehouses.code` to obtain `warehouses.id`; do not write the code string into `warehouse_id`. Preserve UUIDs for data moved to the new DB to avoid breaking logical references. The V2 seed does not imply a fixed UUID across environments.

### inventory_db.inventory_balances

**Owner / purpose:** `inventory-service` / `inventory_db`; stock quantities for one SKU at one warehouse. **Sources:** fulfillment V1, #130/#243/#249. **Changes:** retain the existing table/columns and add audit actors. SKUs belong to catalog; do not move SKU records into inventory.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Balance PK. | V1 — Existing |
| `sku_id` | `uuid` | No | None | Logical reference to the catalog SKU; UNIQUE together with `warehouse_id`. | V1 — Existing |
| `warehouse_id` | `uuid` | No | None | Local FK to `warehouses.id`. | V1 — Existing |
| `total_quantity` | `integer` | No | `0` | Total stock; CHECK `>= 0`. | V1 / #130 — Existing |
| `available_quantity` | `integer` | No | `0` | Available to reserve; CHECK `>= 0`. | V1 / #130 — Existing |
| `reserved_quantity` | `integer` | No | `0` | Currently reserved; CHECK `>= 0`. | V1 / #130 — Existing |
| `unavailable_quantity` | `integer` | No | `0` | Unavailable for sale; CHECK `>= 0`. | V1 / #130 — Existing |
| `updated_at` | `timestamptz` | No | `now()` | Stock change time; the baseline has no `created_at`. | V1 — Existing |
| `version` | `bigint` | No | `0` | Value used for optimistic locking; the baseline has no CHECK `version >= 0`. | V1 / #130 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys, constraints, indexes:** PK `(id)`; UNIQUE `(sku_id, warehouse_id)`; a separate nonnegative CHECK for each quantity; the required total CHECK `total_quantity = available_quantity + reserved_quantity + unavailable_quantity`. FK `(warehouse_id) REFERENCES warehouses(id) ON DELETE NO ACTION ON UPDATE NO ACTION`, not deferred. Separate index `idx_inventory_sku (sku_id)` — B-tree, nonunique, nonpartial. Retain this baseline index; do not remove it without review merely because it shares a prefix with the UNIQUE index.

**Relationships:** warehouse 1 ↔ 0..N balances; catalog SKU 1 ↔ 0..N balances is a logical relationship, and the DB does not ensure that the SKU exists. UNIQUE permits at most one balance for each SKU/warehouse pair.

**Lifecycle / enforcement:** the DB enforces nonnegative quantities and the total equation together. The service ensures reserve/confirm/release changes the correct quantity categories, checks sufficient stock and updates atomically in a transaction with the related reservation/movement/outbox. `version` prevents lost updates only when the entity/UPDATE actually checks and increments it; DEFAULT itself does not implement optimistic locking. There is no `deleted_at`; do not arbitrarily delete related history when a balance reaches 0.

### inventory_db.inventory_reservations

**Owner / purpose:** `inventory-service` / `inventory_db`; reserve a SKU quantity at a warehouse for an order. **Sources:** fulfillment V1, #134/#136/#243/#249. **Changes:** retain nullable `cart_id` for schema compatibility; R1 uses `order_id`, not `cart_id`; add audit actors; the status enum name follows implementation #249.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Reservation PK. | V1 — Existing |
| `sku_id` | `uuid` | No | None | Logical reference to the catalog SKU. | V1 — Existing |
| `warehouse_id` | `uuid` | No | None | Local FK to `warehouses.id`. | V1 — Existing |
| `order_id` | `uuid` | Yes in the schema | None | Logical reference to the commerce order; the service requires a value for the R1 order-based reservation flow. | V1 — Existing NULL allowance; #134 — R1 usage |
| `cart_id` | `uuid` | Yes | None | Reserved logical reference to the commerce cart; retain the column, unused in R1. | V1 / #134 — Existing |
| `quantity` | `integer` | No | None | Reserved quantity; CHECK `quantity > 0`. | V1 — Existing |
| `status` | `inventory_reservation_status` — target enum name | No | `'HELD'` | `HELD`, `CONFIRMED`, `RELEASED`, `EXPIRED`; the baseline is `varchar(20)` + CHECK with the same value set. | V1 — Existing values; #243/#249 — type per implementation #249 |
| `expires_at` | `timestamptz` | No | None | Reservation expiration time. | V1 — Existing |
| `released_at` | `timestamptz` | Yes | None | Release time when applicable; not automatically generated by the DB. | V1 — Existing |
| `created_at` | `timestamptz` | No | `now()` | Reservation creation time. | V1 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `auction_id` | `uuid` | Yes | None | Source auction for the stock hold before an order exists | Logical auction reference; order_id may be attached after settlement without changing the reservation UUID. |

**Keys, constraints, indexes:** PK `(id)`; CHECK `quantity > 0`; status is restricted to the four values above. FK `(warehouse_id) REFERENCES warehouses(id) ON DELETE NO ACTION ON UPDATE NO ACTION`, not deferred. Three nonunique, nonpartial B-tree indexes: `idx_reservations_expiring (status, expires_at)`, `idx_reservations_order (order_id)`, `idx_reservations_sku (sku_id, warehouse_id)`. The baseline has no UNIQUE for order/SKU/warehouse, no FK to orders/carts/SKUs and no constraint requiring exactly one of `order_id`/`cart_id`.

**Relationships:** warehouse 1 ↔ 0..N reservations is enforced by an FK; order, cart and SKU are logical references. One order may have multiple reservations for different SKUs/warehouses, so do not make `order_id` alone UNIQUE.

**Lifecycle / enforcement:** the R1 flow starts with `HELD` and transitions as appropriate to `CONFIRMED`, `RELEASED` or `EXPIRED`; the enum validates values, not the state machine. The service/job must conditionally update status and change the balance/movement in the same transaction; retries or redelivered events must not release stock twice. Reservation deadlines and expiry durations from configuration/issues are checked by the service; do not turn configurable durations into fixed CHECKs. The baseline has no CHECK `expires_at > created_at` or rule for `released_at`; do not describe such CHECKs as existing. This table has no `version`, `updated_at` or soft delete.

**Selected idempotency under SRS §3.6.1 / BR-192:** order requests normalize each SKU/warehouse pair to one row; partial UNIQUE uq_reservation_order_item(order_id,sku_id,warehouse_id) WHERE order_id IS NOT NULL. Auction holds use partial UNIQUE uq_reservation_auction_item(auction_id,sku_id,warehouse_id) WHERE auction_id IS NOT NULL. One reservation may retain both auction_id and order_id after settlement; do not duplicate the hold. The same source/pair and quantity return the existing reservation; a body with a different quantity returns 409 without increasing reserved stock again. Confirm/release update HELD only through CAS; repeating the same operation is a no-op under #136. Existing duplicates must be reconciled against stock/the ledger before adding uniqueness; do not mechanically delete duplicate rows.


### inventory_db.inventory_movements

**Owner / purpose:** `inventory-service` / `inventory_db`; stock movement history by SKU/warehouse. **Sources:** fulfillment V1, #131/#136/#199/#200/#243/#249. **Changes:** add audit actors and storage for the adjustment reason; extend the `movement_type` domain because intake/reservation flows require `INTAKE`, `RESERVE`, `RELEASE`, `COMMIT` and #200 requires `ADJUST`, while the old CHECK lacks `INTAKE`/`COMMIT`/`ADJUST`.

| Column | Target PostgreSQL type | NULL | DEFAULT / generation | Meaning, keys and constraints | Source / status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Movement PK. | V1 — Existing |
| `sku_id` | `uuid` | No | None | Logical reference to the catalog SKU. | V1 — Existing |
| `warehouse_id` | `uuid` | No | None | Local FK to `warehouses.id`. | V1 — Existing |
| `movement_type` | `inventory_movement_type` — retain a historically compatible superset | No | None | The intake/reservation flow writes `INTAKE`, `RESERVE`, `RELEASE`, `COMMIT`; adjustments write `ADJUST`. The legacy-compatible superset is `IMPORT`, `EXPORT`, `RESERVE`, `RELEASE`, `SALE`, `RETURN`, `ADJUSTMENT`, `TRANSFER`, `INTAKE`, `COMMIT`, `ADJUST`. | V1 — 8 existing legacy values; #131/#136/#200 — Required values for new writers; superset/type per implementation #249 |
| `quantity` | `integer` | No | None | Movement quantity; the baseline has no sign CHECK or nonzero CHECK. For `ADJUST`, it is proposed to store the signed `quantityDelta` from #200 unchanged; do not apply a table-wide positive CHECK. | V1 — Existing column; #200 — Proposed delta mapping |
| `reference_id` | `uuid` | Yes | None | Business reference ID that caused the movement; its meaning depends on the movement/flow; the baseline has no FK or discriminator. | V1 — Existing |
| `reason` | `text` — proposed name/type | Yes at column level; required for `ADJUST` — proposed | None | Adjustment reason under #200; other movement types and historical records without a reason may be NULL. | #200 — Reason persistence required; field/type/conditional NULL allowance proposed |
| `created_at` | `timestamptz` | No | `now()` | Movement recording time. | V1 — Existing |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `reference_type` | `varchar(30)` | Yes | None | Classification of reference_id: ORDER, AUCTION, RESERVATION, INTAKE, ADJUSTMENT | Unknown legacy values remain NULL; new writers supply the correct type; CHECK enforces the listed domain when non-NULL. |
| `operation_id` | `uuid` | Yes | None | Stable identity of an intake/reserve/release/commit/adjust operation | Legacy records remain NULL; new writers must supply it; the partial unique index below protects HTTP/event replay. |

**Keys, constraints, indexes:** PK `(id)`; FK `(warehouse_id) REFERENCES warehouses(id) ON DELETE NO ACTION ON UPDATE NO ACTION`, not deferred. Two nonunique, nonpartial B-tree indexes: `idx_movements_sku (sku_id, warehouse_id, created_at DESC)`, `idx_movements_reference (reference_id)`. The baseline CHECK restricts `movement_type` to eight legacy values. Replace it with the target superset enum; do not retain the old CHECK and thereby block `INTAKE`/`COMMIT`/`ADJUST`. **Selected target CHECK** for adjustment reasons: `movement_type <> 'ADJUST' OR (reason IS NOT NULL AND btrim(reason) <> '')`; it does not apply retroactively to unconverted historical `ADJUSTMENT` values. There is no UNIQUE on `reference_id`; one order/transaction may generate multiple movements.

**Relationships:** a warehouse has 0..N movements; SKU and `reference_id` are logical references. Do not assume every `reference_id` is `orders.id`; auction flows may require a different source. Each writer's reference mapping must be documented in its implementation issue.

**Lifecycle / enforcement:** movements are **proposed** to be append-only history; balance updates and movement/outbox writes are atomic. #200 requires an `ADJUST` movement with a reason for an adjustment; the proposal retains the signed delta in `quantity`, checks inputs and resulting quantities in the service, while the DB continues to protect the balance total equation and nonnegative stock quantities. The baseline has no `updated_at`, `version` or `deleted_at`. The service checks operation, quantity and reservation-status validity; do not add a table-wide CHECK `quantity > 0` when adjustments require negative deltas.

**Selected data migration strategy:** initially use the 11-value superset above to preserve existing data, while new writers use the values for their respective flows. Do not automatically `UPDATE IMPORT -> INTAKE`, `SALE -> COMMIT`, `ADJUSTMENT -> ADJUST` or delete the `EXPORT`, `RETURN`, `ADJUSTMENT`, `TRANSFER` categories: these are only candidate mappings requiring business confirmation. Converting old `ADJUSTMENT` to `ADJUST` also requires addressing reasons absent from historical data; do not fabricate reasons to pass the CHECK. If the team later wants to remove legacy values, inventory the data, approve mapping/backfill for each category and create a separate migration. The superset is an implementation choice made within the user-authorized turn; it is not claimed to be a literal set already specified in the old issues.


**Ledger under BR-186/BR-192:** INSERT only, with no business UPDATE/DELETE. ADJUST stores signed quantityDelta and a nonempty reason; intake/reserve/release/commit store a positive quantity whose direction is determined by movement_type. CHECK the reason for ADJUST; do not CHECK quantity>0 across the entire table because negative adjustments are valid. Partial UNIQUE uq_movements_operation(operation_id,sku_id,warehouse_id,movement_type) WHERE operation_id IS NOT NULL prevents a duplicate effect. Multiple movements for the same order are allowed; reference_id is not unique. Retain the 11-value enum superset to preserve history; new writers use INTAKE/RESERVE/RELEASE/COMMIT/ADJUST. Do not automatically map IMPORT/SALE/ADJUSTMENT data to the new names because semantics may differ. This is the selected upgrade strategy, separate from the promo section awaiting reviewer approval.

### inventory_db.outbox

**Owner / source:** inventory_db; fulfillment V1. Retain the V1 definition; change owner/DB under #243.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Outbox record ID; proposed to remain stable and identical to eventId during publishing/retries | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type name | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Text aggregate ID, which may be a UUID or business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraints` |
| `occurred_at` | `timestamptz` | No | `now()` | Time when the event occurred | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not yet marked as published | `No additional constraints` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is already specified on the field; there is no FK to a business table or another database. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business behavior:** the business change and outbox INSERT share one local transaction. The row ID is proposed to equal eventId in the envelope; relay retries retain the same eventId, aggregate key and payload rather than generating a new ID. A relay may resend if it crashes after Kafka acknowledgement but before published_at: consumers must deduplicate; outbox does not itself provide exactly-once delivery. The selected approach stores the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends the envelope unchanged and preserves eventId on every retry without double-wrapping. `aggregate_id` is varchar(100); do not change it to UUID or an FK because some aggregates use text keys. Published outbox records are retained for at least 90 days; unpublished records are not purged. The business ledger/uniqueness rules and processed_events policy in the retention section protect their respective replay scenarios.

### inventory_db.processed_events

**Owner / source:** inventory_db; fulfillment V1. Retain the V1 definition; change owner/DB under #243.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers of the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Time when local processing completed | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not change it to event_id alone. No FK to another service's outbox/event_logs.

**Business behavior:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. Marker uniqueness prevents the same event from being processed twice by one handler, but does not block different eventIds for the same business operation, so the domain still needs the corresponding unique order/provider reference. If the envelope lacks the data needed to apply it, do not mark it successfully processed and discard it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or for the ledger's lifetime. Replay outside that window must use a restore/backfill workflow and business uniqueness; do not imply indefinite deduplication after purging. Consumer names are stable per handler, not per instance.

### inventory_db.shedlock

**Owner / source:** inventory_db; fulfillment V1. Retain the V1 definition; change owner/DB under #243.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `name` | `varchar(64)` | No | — | Job lock name, unique within the database | `PRIMARY KEY` |
| `lock_until` | `timestamptz` | No | — | Lease expiration time | `NOT NULL` |
| `locked_at` | `timestamptz` | No | — | Lock acquisition time | `NOT NULL` |
| `locked_by` | `varchar(255)` | No | — | Identifier of the instance holding the lease | `NOT NULL` |

**Keys/constraints/indexes:**
- PK `(name)`; no secondary index or FK; no defaults for the timestamps/owner.

**Business behavior:** the scheduler writes and renews the lease through a local lock provider. This table does not replace business idempotency or foreign-key enforcement. The lockAtMostFor/lockAtLeastFor durations are job configuration, not CHECK literals. Retain the existing definition; a new service adds a scheduler lock table only when its implementation issue confirms that it is needed.

<a id="payment-db"></a>

## payment-service / payment_db

**Owner and R1 scope:** payment-service, port 8087. Sources: #120, #121, #155–#157, #207, #240, #247, the commerce V1/V2 baseline, ADR-007, and the accepted leader/user R1 design. The buyer's checkout operation creates a stored `commerce_db.order_groups` aggregate and one commerce order per seller. Exactly one payment session belongs to that order group and supplies its single QR. Per-order `payment_instructions` contain the order shares of the payment plan and funding references; there is no separate allocation table. Record each actual provider movement once, without cloning the full bank transfer for every seller. These **four baseline business tables** describe the original target design; #257 adds seller_bank, payouts and payout_history below, while already-applied V5 completion/eligibility tables remain in place. The technical outbox/processed_events definitions follow separately.

**Terminology and contract boundary:** checkout names the buyer operation; order group names the stored aggregate. Use `order_group_id` in database relationships and `orderGroupId` in payloads. Retain `CheckoutPaymentRequested` and `CheckoutPaymentConfirmed` as functional event names for the checkout flow; their names do not introduce another stored aggregate or payload identifier. BR-109 and the older #240/#121/#155–#157 contracts assumed payment by one order; this R1 extension settles one complete order group through one session and its per-order instructions. Update #118/#186/#246 event snapshots, #121 QR initiation, #207 reads and #247 refund-origin handling. Do not add replacement sessions, plan revisions, supersession or an unpaid-child cancellation handshake. Before payment, cancel the entire group/session or let its single QR expire; afterward, child cancellation retains the existing physical shipment handover coordination.

**Types and audit:** `payment_instruction_status = ('PENDING','PAID','CANCELED','EXPIRED')`; `payment_session_status = ('PENDING','PAID','CANCELED','EXPIRED')`; `payment_transaction_status = ('PENDING','SUCCESS','FAILED','UNRECONCILED')`; `payment_transaction_type = ('NORMAL','REFUND','PAYOUT','PAYOUT_RETURN')`; `refund_status = ('REQUESTED','COMPLETED')`. Each session status appears once. The transaction enum names follow #249; instruction/session/refund type names and added values are target design choices. `provider` remains `varchar(40)` for #120 adapters rather than a fixed SEPAY/STRIPE enum. All four business tables use `created_by`, `updated_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid` under #249. JPA/native writers supply authenticated actors or the fixed system actor, preserve created_by after INSERT, and update updated_by on changes. There are no physical FKs to commerce/user/Keycloak databases.

### payment_db.payment_instructions

**Purpose / changes:** one local ownership, payment-plan and refund-eligibility projection per seller order. Retain UNIQUE(order_id). The nonunique session_id joins all seller instructions to the one order-group session. `amount` is this order's share; `funded_transaction_id` points to the one accepted incoming movement that may also fund sibling instructions. The QR/memo belongs only to payment_sessions. The separate allocation table is merged into this instruction rather than retained under another name.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Local instruction/projection ID | PK; retained target technical key under #240. |
| `order_id` | `uuid` | No | From the trusted order snapshot | Commerce child order represented by this projection | UNIQUE; logical commerce reference, no cross-database FK. |
| `session_id` | `uuid` | No | From the order-group payment session | Shared session for this order instruction | Local FK to payment_sessions.id; many instructions share one session. |
| `funded_transaction_id` | `uuid` | Yes | NULL until paid | NORMAL/SUCCESS transaction funding this order share | Local FK to payment_transactions.id; one transaction may fund many instructions. |
| `buyer_id` | `uuid` | No | From the trusted order snapshot | Buyer authorized to view the order's payment/refund information | Required for local ownership checks; must match the session buyer. |
| `amount` | `numeric(19,2)` | No | From the finalized order amount | Exact payment-plan share assigned to this order | CHECK `amount >= 0`; preserve #66/#67 money precision; do not accept a caller override. |
| `currency` | `char(3)` | No | `'VND'` | Order payment currency | Must match its session, funding transaction and refunds at the service. |
| `due_at` | `timestamptz` | No | From the order's payment deadline | Latest allowed payment time for this order | Retains #240/#121/#157 deadline semantics; commerce retains orders.payment_due_at. |
| `status` | `payment_instruction_status` | No | `'PENDING'` | PENDING, PAID, CANCELED or EXPIRED | PAID requires this instruction's accepted funding reference, not ownership of the entire session amount. |
| `paid_at` | `timestamptz` | Yes | None | Time this order's instruction share was funded | Preserve after later order cancellation/refund; do not erase funding evidence. |
| `canceled_at` | `timestamptz` | Yes | From the trusted cancellation event | Order cancellation marker | Blocks unpaid funding; a funded instruction retains PAID and records cancellation separately. |
| `last_order_event_at` | `timestamptz` | Yes | From the last applied order event | Order event occurredAt for audit | Not a replacement for order version or processed_events. |
| `created_at` | `timestamptz` | No | `now()` | Local projection creation time | Retained target field. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Local projection update time | The writer updates it on UPDATE. |
| `version` | `bigint` | No | `0` | Local optimistic-lock version; also sequences refund snapshots for this order | CHECK `version >= 0`; increment/check in the actual writer. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating authenticated/system actor | NOT NULL/system default under code #249/0bc9da1; no identity FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent authenticated/system writing actor | NOT NULL/system default under #249; preserve created_by on updates. |
| `last_order_version` | `bigint` | No | `0` | Highest commerce order version applied to the projection | CHECK `last_order_version >= 0`; distinct from local version and envelope schema eventVersion. |

**Keys / constraints / indexes:** PK `(id)`; UNIQUE `(order_id)`; FK `(session_id) REFERENCES payment_sessions(id) ON DELETE NO ACTION ON UPDATE NO ACTION`; FK `(funded_transaction_id) REFERENCES payment_transactions(id) ON DELETE NO ACTION ON UPDATE NO ACTION`. Both FKs are local and NOT DEFERRABLE. CHECK `amount >= 0`, `version >= 0` and `last_order_version >= 0`; use the stated instruction status enum. Add regular indexes `idx_payment_instructions_session (session_id, order_id)` and `idx_payment_instructions_funded_transaction (funded_transaction_id)`; retain `idx_payment_instructions_buyer (buyer_id, created_at DESC)` and `idx_payment_instructions_due (status, due_at)`. Neither session_id nor funded_transaction_id is UNIQUE: one session/accepted movement funds several instructions. UNIQUE(order_id) already supplies its own B-tree index.

**Relationships and enforcement:** one logical commerce order has at most one instruction; a session has 1..N instructions once its complete plan is created. The instruction's session/order/buyer/currency/amount/deadline are immutable plan facts. Set funded_transaction_id only once, after verifying a NORMAL/SUCCESS movement from the same session and currency; the FK alone does not prove its type/status or the sum of shares. Never clear or retarget this reference to hide a cancellation/refund. The session's whole receipt amount must not be counted as each order's paid amount. A zero-valued instruction may share a positive-total paid session, but has no positive refund budget.

**Creation and lifecycle:** create the session and all its instructions atomically from the complete CheckoutPaymentRequested manifest, not from the first OrderCreated. Validate the exact child set and each order snapshot; missing prerequisites use durable retry rather than fabricated data or SELECT from commerce_db. Write consumer markers with the local effects. PENDING instructions become PAID together with their funding reference and paid_at when the session's exact payment is accepted; prepayment whole-group cancellation/expiry prevents further funding. A paid child's later OrderCancelled records canceled_at but preserves PAID, paid_at and the successful incoming movement. Apply order events using last_order_version; a stale creation/cancellation event cannot erase funding evidence or reopen a terminal unpaid instruction. Legacy memo ownership moves to the session; do not retain a second authoritative memo on instructions.

### payment_db.payment_sessions

**Purpose:** one immutable payment session for the complete stored order group. It owns the group's single QR, unique memo, expected total, buyer/currency and deadline. UNIQUE(order_group_id) permits one session over the group's entire lifetime, including canceled/expired states; it is not a partial uniqueness rule for PENDING rows. A later purchase requires a new group rather than a replacement session for the same group.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Payment session ID returned with the QR | PK; selected R1 group-payment design. |
| `order_group_id` | `uuid` | No | From the complete commerce checkout manifest | Shared stored order-group identity | UNIQUE; exactly one session exists for one group. |
| `buyer_id` | `uuid` | No | From the complete manifest | Buyer authorized to access this session/QR | Must match every child instruction; no user database FK. |
| `total_amount` | `numeric(19,2)` | No | Sum of the finalized instruction plan | Exact combined amount expected from one provider transfer | CHECK `total_amount > 0`; service validates the complete sum and money scale. |
| `currency` | `char(3)` | No | From the complete manifest | One currency shared by the session and every instruction | Do not combine different currencies in one QR. |
| `memo_code` | `varchar(32)` | No | Generated once when creating this session | Canonical transfer memo owned by this session | UNIQUE across all session states; CHECK `memo_code ~ '^[A-Z0-9]+$'`; moved from per-order instructions. |
| `due_at` | `timestamptz` | No | From the manifest, bounded by every included order's deadline | Common acceptance deadline for this QR | No SQL clock default; must be no later than the earliest child deadline. |
| `status` | `payment_session_status` | No | `'PENDING'` | PENDING, PAID, CANCELED or EXPIRED | Only PENDING is fundable; CANCELED/EXPIRED cannot be reopened. |
| `version` | `bigint` | No | `0` | Local session concurrency/transition version | CHECK `version >= 0`; serialize webhook, cancellation and expiry updates. |
| `source_event_id` | `uuid` | No | Complete-manifest envelope eventId | Original evidence establishing the complete session plan | Preserve on replay; business uniqueness still uses order_group_id/idempotency_key. |
| `idempotency_key` | `varchar(100)` | No | Order-group session request key | Identity of session creation/retrieval across retries | UNIQUE; retries return the same session/memo/QR. |
| `paid_at` | `timestamptz` | Yes | From first accepted funding | Time the complete session transfer was accepted | Retain after child refunds; a refund does not erase incoming payment history. |
| `created_at` | `timestamptz` | No | `now()` | Local session creation time | New target field. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Last local session update time | The writer updates it on UPDATE. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating authenticated/system actor | Audit #249. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent authenticated/system actor | Audit #249. |

**Keys / constraints / indexes:** PK `(id)`; UNIQUE `(order_group_id)`; UNIQUE `(memo_code)`; UNIQUE `(idempotency_key)`; CHECK `total_amount > 0`, `version >= 0` and `memo_code ~ '^[A-Z0-9]+$'`; four-value session status enum. The group UUID is a logical commerce reference, without a cross-database FK. Retain regular `idx_payment_sessions_buyer (buyer_id, created_at DESC)` and `idx_payment_sessions_due (status, due_at)` for ownership/listing and expiry work. Unique keys create their own B-tree indexes; do not add a second pending-only group uniqueness rule. Memo uniqueness applies to historical CANCELED/EXPIRED/PAID sessions, so old memos are never reused.

**Complete manifest and API:** CheckoutPaymentRequested carries orderGroupId, buyerId, currency, totalAmount, dueAt, occurredAt and the complete expected seller-order list/count, including each orderId/sellerId/amount/paymentDueAt. Per-order version information needed for an instruction's last_order_version follows the trusted order-event contract. Commerce commits the order_groups row, seller orders/items/pricing and this manifest/outbox in one local transaction. Payment validates that every child is present exactly once, shares the buyer/currency, has a valid immutable amount/deadline, and that instruction amounts sum exactly to total_amount. Create the session, every instruction and processed marker in one payment transaction. The first child event alone must not expose a QR. Initiation accepts orderGroupId and returns sessionId, orderGroupId, one memo/QR, totalAmount, currency and dueAt after ownership validation. An order-based compatibility lookup can resolve that order's shared session but must not create or charge a second QR.

**Idempotency and lifecycle:** resolve the stable request key and UNIQUE(order_group_id) before creating a session. The same normalized full plan returns its existing identity/memo/QR; conflicting membership/buyer/currency/amount/deadline is rejected rather than mutating the issued plan. Keep the plan and QR fixed for the session's life. Before payment, whole-group cancellation closes the session as CANCELED, or its deadline makes it EXPIRED; neither results in a new session for the same group. Once PAID, preserve the financial fact while seller orders progress independently through shipment/cancellation/refund. An order group with a zero total does not create this positive-value bank-payment session or a fabricated SUCCESS transfer; its zero-payment completion belongs to the explicit commerce pricing/free-order flow.

### payment_db.payment_transactions

**Purpose / source:** one real-money ledger record per actual provider movement/attempt. The table moves from commerce V1 to payment_db, retaining original IDs and provider/reference/amount/currency/status/type/idempotency/raw-response/timestamp fields plus audit/receipt metadata. Replace the direct order_id association with session_id: one incoming movement can fund several order instructions. Do not duplicate the group receipt per seller or leave a direct order FK as a competing financial source of truth.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Local identity of one physical provider movement/attempt | PK; preserve legacy transaction IDs. |
| `session_id` | `uuid` | No | From the matched memo/session or the refund's funded instruction | Session associated with this incoming/outgoing movement | Local FK to payment_sessions.id; replaces authoritative order_id association. |
| `provider` | `varchar(40)` | No | Selected #120 implementation | Canonical provider name such as SEPAY | Retain V1; no fixed provider enum. |
| `txn_reference` | `varchar(120)` | Yes | Provider transaction ID as text when available | Physical provider movement ID, not a memo or order ID | Retain V1; required by the service for verified NORMAL webhook movements. |
| `amount` | `numeric(19,2)` | No | From verified provider data or the approved refund amount | Actual value of this single incoming/outgoing movement | Retain CHECK `amount >= 0`; never repeat a session total per child order. |
| `currency` | `char(3)` | No | `'VND'` | Movement currency | Retain V1; must match the session/instruction/refund at the service. |
| `status` | `payment_transaction_status` | No | None | PENDING, SUCCESS, FAILED or UNRECONCILED | Keep the original outcomes and add UNRECONCILED under #156/#157. |
| `type` | `payment_transaction_type` | No | None | NORMAL incoming payment, REFUND outgoing refund, PAYOUT outgoing seller transfer or PAYOUT_RETURN returned seller funds | Original V1 domain plus #257; not a seller-share type. |
| `idempotency_key` | `varchar(100)` | No | Stable provider/operation identity; refund operation derived from refundId | Business identity of this movement/attempt | Retain table-wide UNIQUE from V1/#120. |
| `raw_response` | `jsonb` | Yes | None | Non-sensitive provider payload/response retained for reconciliation | Retain V1; not a complete HTTP log or byte-preserving signature store. |
| `created_at` | `timestamptz` | No | `now()` | Local ledger recording time | Retain V1. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Last local result update time | Retain V1; writer updates on UPDATE. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating authenticated/system actor | Audit #249. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent authenticated/system actor | Audit #249; financial facts are not rewritten to hide cancellation. |
| `received_at` | `timestamptz` | No | Assigned once on first verified receipt/recording | Webhook receipt time; for manual refund/PENDING, local operation recording time | Preserve across retries; only a verified NORMAL webhook uses receipt for its payment deadline. |
| `provider_occurred_at` | `timestamptz` | Yes | From verified provider evidence | Provider-reported movement time for reconciliation | Do not substitute HMAC-header time for bank movement time. |

**Keys / constraints / indexes:** PK `(id)`; FK `(session_id) REFERENCES payment_sessions(id) ON DELETE NO ACTION ON UPDATE NO ACTION`, NOT DEFERRABLE; UNIQUE `(idempotency_key)`; CHECK `amount >= 0`; transaction status/type enums. Replace obsolete idx_payment_order with regular `idx_payment_session (session_id, created_at)`; retain `idx_payment_txn_ref (txn_reference)` and `idx_payment_status (status, created_at)`. Add `CREATE UNIQUE INDEX uq_payment_provider_txn ON payment_transactions (provider, txn_reference) WHERE txn_reference IS NOT NULL;`. R1 uses one configured SePay account per environment and separate environment databases; an adapter whose IDs are account-scoped needs account identity before multiple accounts are supported. A NORMAL webhook requires txn_reference at the service; NULL remains possible for manual refund evidence, PENDING attempts and verified legacy cases. Do not use the memo as the provider transaction deduplication key.

**Webhook acceptance transaction:** verify HMAC over the original bytes before effects, resolve the session by its unique memo, and check the provider transaction identity/content. An unmatched memo follows #155's 200/no-business-write behavior; do not create a fake session. For a matched transfer, lock the session then its instructions in deterministic order_id order. Require session PENDING, a complete unchanged instruction set, matching buyer/currency/total, every instruction unfunded/payable, no known canceled/expired child, and first verified received_at < due_at. In one transaction insert **one** NORMAL SUCCESS movement, set that same funded_transaction_id on all child instructions, mark the session/instructions PAID with paid_at, increment their local versions, and write **one** CheckoutPaymentConfirmed outbox event with the complete instruction list. Validate each share against its finalized order amount and the sum against both ledger amount and session total. UNIQUE(order_id), session locking and one-way funding-reference assignment prevent charging an order twice; there is no removed allocation table or allocation-specific partial unique index to enforce this.

**Duplicate and exception handling:** replay of the same provider ID and business content returns 200 without another ledger entry, instruction funding, version increment or confirmation event. A reused provider ID with different content is a conflict to audit, never an overwrite. A distinct movement for an already PAID session, wrong amount/currency, partial/extra payment, a CANCELED/EXPIRED session, known canceled child or received_at >= due_at becomes UNRECONCILED without funding instructions or emitting CheckoutPaymentConfirmed. Write the matched exception ledger and PaymentUnreconciled outbox atomically with sessionId, orderGroupId, transactionId, actual amount/currency, reasonCode/reason, receivedAt/providerOccurredAt and affected planned order IDs. The order shares provide context; they do not assign the whole receipt to each seller. R1 neither accumulates partial transfers nor pays an arbitrary subset of the group. Preserve provider evidence for Support; do not reopen canceled orders/released stock.

**Confirmation contract and commerce effect:** CheckoutPaymentConfirmed carries sessionId, orderGroupId, transactionId, buyerId, currency, totalAmount, paidAt, sessionVersion and `instructions[{instructionId,orderId,amount}]`. The group identifier occurs once; the contract includes no replacement-plan revision. Commerce validates the immutable group/session identity, full child membership and amounts, deduplicates the event, and locks its stored order_groups row and affected child orders. Commit group payment evidence, valid child payment transitions/history and individual OrderPaid/outbox events in one commerce transaction. The existing order_groups.version is a local concurrency token, not a payment producer version; this flow adds no separate payment-projection fields to the group. With one immutable session and one accepted funding result, repeat confirmations with the same evidence are no-ops and inconsistent evidence is a reconciliation conflict. A canceled child never returns to PAID; retain financial evidence and compensate its funded instruction if payment had already been accepted before asynchronous cancellation became known.

**Deadlines and migration:** PAYMENT_CALLBACK_TIMEOUT_MINUTES=10 is callback monitoring, not a replacement for session/order due_at; preserve the default auction deadline of 24h. PAYMENT_IDEMPOTENCY_WINDOW_MINUTES=60 is a minimum deduplication window, not automatic expiry of ledger uniqueness; PAYMENT_RETRY_LIMIT defaults to 3, preserving operation identities. Backfill verified historical single-order payments into singleton order groups/sessions/instructions, keeping provider references, transaction IDs, amounts and memo mappings. Only real NORMAL SUCCESS evidence funds an instruction. Remove legacy order_id/FK/index through a new migration after cutover; do not edit applied V1 or fabricate missing sessions/deadlines/SUCCESS records to satisfy target constraints.

### payment_db.refunds

**Purpose / R1 limit:** one refund record per canceled paid seller order, enforced by UNIQUE(order_id). Its budget comes from that order's funded instruction, not the whole group receipt or a sibling shop. The request's optional amount remains authoritative when supplied; the database stores the resolved amount. A reviewer-required automatic refund follows full child-order cancellation. Selective item returns and several refund requests for one order are outside this R1 schema.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | refundId returned to callers/Support | PK; retained target field for #247. |
| `order_id` | `uuid` | No | From the trusted request/cancellation | Seller order owning this refund | UNIQUE local FK to payment_instructions.order_id; one refund per child order in R1. |
| `amount` | `numeric(19,2)` | No | Exact supplied amount, or this order instruction amount when omitted | Amount for this child order | CHECK amount > 0; automatic cancellation uses this order amount, never the group total. |
| `currency` | `char(3)` | No | `'VND'` | Currency of this instruction-scoped refund | Must match the origin session, funded instruction and transaction at the service. |
| `reason` | `text` | No | API reason or nonempty system cancellation reason | Why this order's funded share is being refunded | CHECK `length(btrim(reason)) > 0`; retained #247 field. |
| `status` | `refund_status` | No | `'REQUESTED'` | REQUESTED reservation or COMPLETED outgoing refund | Retained two-state target domain. |
| `idempotency_key` | `varchar(100)` | No | API header or stable system:cancel-refund:{orderId} key | Identity of this one refund operation | Table-wide UNIQUE; reserve the system namespace from public API input. |
| `requested_by` | `uuid` | No | CurrentUser for API; system UUID for automatic cancellation | Actor creating the request | Automatic requests use the fixed all-zero #249 system actor. |
| `requested_at` | `timestamptz` | No | `now()` | Request creation time | Retained #247 requestedAt. |
| `completed_by` | `uuid` | Yes | None | Authorized Support/Admin confirming the outgoing transfer | Logical user ID, no identity FK. |
| `completed_at` | `timestamptz` | Yes | None | Time completion was confirmed | Retained #247 completedAt. |
| `completed_transaction_id` | `uuid` | Yes | From the one corresponding REFUND SUCCESS ledger row | Outgoing money record for this completed request | Local FK payment_transactions.id; UNIQUE when non-NULL. |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Last request update time | Writer updates on completion. |
| `version` | `bigint` | No | `0` | Per-refund optimistic-lock version | CHECK `version >= 0`; prevents completing one request twice. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating authenticated/system actor | Audit #249. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent authenticated/system actor | Audit #249. |

**Keys / constraints / indexes:** PK `(id)`; UNIQUE `(order_id)`; UNIQUE `(idempotency_key)`; UNIQUE `(completed_transaction_id)` with default NULL DISTINCT before completion. FK `(order_id) REFERENCES payment_instructions(order_id) ON DELETE NO ACTION ON UPDATE NO ACTION` and FK `(completed_transaction_id) REFERENCES payment_transactions(id) ON DELETE NO ACTION ON UPDATE NO ACTION` are local and NOT DEFERRABLE. CHECK `amount > 0`, `length(btrim(reason)) > 0` and `version >= 0`; refund status enum. REQUESTED requires completed_by/completed_at/completed_transaction_id all NULL; COMPLETED requires all three non-NULL and `completed_at >= requested_at`. Retain regular `idx_refunds_status (status, requested_at)`. UNIQUE(order_id) supplies the order lookup index; no redundant regular order index or separate allocation-origin FK is needed.

**Origin validation and budget:** authorize first, resolve the order instruction, and verify its funded_transaction_id points to a NORMAL SUCCESS movement in the same session/currency. The available order budget is the funded instruction amount, minus any existing REQUESTED/COMPLETED refund reservation/payment; do not use the full session ledger amount and do not subtract a completed refund's REFUND ledger amount again. Lock session, instruction and refund consistently with the funding/cancellation paths before creating or completing a record. Positive supplied amounts must be no greater than that order budget and are stored exactly; omission resolves that order's current refundable amount once. Reject zero/negative/excess-scale/excess-budget input rather than rounding or replacing a valid smaller amount with the full group receipt. Empty/zero-priced child shares do not create a positive refund record.

**Idempotency and one-order uniqueness:** look up an authorized request's existing Idempotency-Key before recalculating omitted amount or rechecking mutable eligibility. Same logical order/amount/reason returns the stored refundId/amount/result, including after completion; conflicting content returns 409. A different key for an order already holding a refund conflicts with UNIQUE(order_id); it is not a second refund operation in R1. Resolve a conflict atomically under the same locks, not by creating a duplicate record. This storage limit is separate from the optional amount rule: a permitted partial request does not enable another later request for the remainder. Do not add item-return or automatic-request claim/split/resize workflows.

**Automatic canceled-paid-order record:** apply trusted child OrderCancelled using last_order_version while preserving incoming funding facts. If the instruction is funded, retain PAID/paid_at, record canceled_at and first resolve the stable system:cancel-refund:{orderId} key and the unique order record. If no refund exists and the funded share is positive, create one REQUESTED refund for that child's amount with requested_by/created_by/updated_by equal to the fixed system UUID and a nonempty cancellation reason. Write the refund, incremented instruction version, OrderRefundRequested outbox and processed marker in one local transaction. Duplicate cancellation envelopes do not create another refund or resize an existing one; an existing API-created record is retained under UNIQUE(order_id), rather than silently topped up. If no verified successful funding exists, create no refund and retain unpaid/UNRECONCILED handling. The system record is not an automatic bank transfer.

**Manual completion and events:** Support/Admin with ORDER.REFUND confirms transfer of exactly the request's stored amount, not a freshly computed group total. Under session/instruction/refund locks, verify REQUESTED/version and the same order/currency/amount, then insert one REFUND SUCCESS movement with session_id equal to the funded instruction's session and a key derived from refundId. Set completed_transaction_id/actor/time/status, increment the instruction version and write OrderRefundCompleted in the same local transaction. The linked movement must be REFUND/SUCCESS and exactly match the request; FK/UNIQUE alone do not enforce these values. Replay adds no outgoing movement or event. received_at records local manual confirmation; provider_occurred_at is populated only from verified evidence. Real transfer and DB commit are not atomic, so reconcile external evidence before retrying confirmation.

OrderRefundRequested/OrderRefundCompleted carry refundId, orderId, instructionId, sessionId, amount, currency, REQUESTED/COMPLETED status, totalPaidAmount, agreedRefundAmount, completedRefundAmount, remainingPaidAmount and the per-order refundVersion from payment_instructions.version. The status and balance snapshot is derived from the funded instruction and its single refund in the same local transaction; these event fields do not introduce additional database columns. Commerce retains orders.last_refund_version for ordered application; it is distinct from orders.version and envelope eventVersion. Only this seller order follows REFUND_REQUESTED/REFUNDED under #116/#247; the parent group and successful session retain their payment history, and siblings do not become refunded. Completion confirms the stored refund request amount. Where an authorized smaller amount is accepted, completion is not proof that the full original paid share was returned; R1's one-request constraint does not add a second refund or a new order status. Item-return eligibility, item/quantity accounting and any later residual-refund workflow require their own contract.

### payment_db.seller_bank

#257 / payment-service V7. A seller owns zero or more encrypted bank accounts. Only an active VERIFIED default can receive a payout.

| Field | PostgreSQL type | NULL? | Rule / purpose |
| --- | --- | --- | --- |
| `id` | `uuid` | No | PK DEFAULT uuidv7(). |
| `seller_id` | `uuid` | No | Logical user/seller ID; no cross-database FK. |
| `bank_code` | `varchar(20)` | No | Six-digit NAPAS BIN. |
| `account_number_encrypted` | `text` | No | AES-256-GCM versioned ciphertext; preserves leading zeroes. |
| `account_last4` | `char(4)` | No | Four digits, the only account number portion exposed in APIs. |
| `account_holder_name` | `varchar(100)` | No | Nonblank holder. |
| `verification_status` | `seller_bank_verification_status` | No | UNVERIFIED (default), VERIFIED, REJECTED. |
| `verified_by`, `verified_at` | `uuid`, `timestamptz` | Yes | Both required for reviewed VERIFIED/REJECTED; NULL when UNVERIFIED. |
| `rejection_reason` | `text` | Yes | Required/nonblank when REJECTED, otherwise NULL. |
| `is_default`, `active` | `boolean` | No | Defaults false/true. |
| `version` | `bigint` | No | DEFAULT 0, CHECK >= 0; writer increments on change. |
| `created_at`, `updated_at` | `timestamptz` | No | DEFAULT now(); updates explicitly write updated_at. |
| `created_by`, `updated_by` | `uuid` | No | DEFAULT all-zero system actor; no identity FK. |

Partial UNIQUE `(seller_id) WHERE is_default AND active` enforces one active default, including unverified accounts. Index seller/time for own lists and active verification/time for #261. Changing bank code, number or holder clears verification and rejection metadata; changing a bank does not alter an already-sent payout snapshot. The encryption key is configuration, never a database value or API field.

### payment_db.payouts

#257 / payment-service V7. Current payout state, exactly one row per seller order regardless of retries. It does not replace buyer payment status.

| Field | PostgreSQL type | NULL? | Rule / purpose |
| --- | --- | --- | --- |
| `id` | `uuid` | No | PK DEFAULT uuidv7(). |
| `order_id` | `uuid` | No | UNIQUE, logical commerce seller order. |
| `session_id`, `instruction_id` | `uuid` | No | Local FKs to payment_sessions/payment_instructions. |
| `seller_id` | `uuid` | No | Logical user/seller ID; validated against trusted local instruction ownership. |
| `seller_bank_id` | `uuid` | Yes | Local FK seller_bank(id), NULL while held without an account. |
| `recipient_bank_code` | `varchar(20)` | Yes | Frozen recipient bank BIN for the current attempt. |
| `recipient_account_encrypted` | `text` | Yes | Frozen encrypted recipient number; private persistence only. |
| `recipient_account_last4` | `char(4)` | Yes | Recipient last four digits. |
| `recipient_holder_name` | `varchar(100)` | Yes | Frozen holder name. |
| `order_amount`, `shipping_fee`, `platform_fee`, `net_amount` | `numeric(19,2)` | No | Per-order money snapshot; shipping/fee nonnegative and net positive. |
| `platform_fee_rate` | `numeric(5,4)` | No | CHECK 0..1, frozen when payout is created. |
| `currency` | `char(3)` | No | Same as funded instruction/session. |
| `status` | `payout_status` | No | ON_HOLD, PENDING, PROCESSING, UNKNOWN, SUCCEEDED, FAILED, RETURNED. |
| `attempt_count` | `integer` | No | DEFAULT 0, CHECK >=0; no config-dependent upper bound. |
| `next_attempt_at`, `attempt_started_at` | `timestamptz` | Yes | Due dispatch/reconcile timing and crash recovery. |
| `provider`, `provider_reference` | `varchar(40)`, `varchar(120)` | Yes | Current attempt provider identity/reference. |
| `transaction_id`, `return_transaction_id` | `uuid` | Yes | Local FKs to current attempt PAYOUT/PAYOUT_RETURN movements. |
| `source_event_id` | `uuid` | No | Original OrderCompleted envelope identity. |
| `succeeded_at`, `returned_at` | `timestamptz` | Yes | Confirmed current-attempt outcomes. |
| `version` | `bigint` | No | DEFAULT 0, CHECK >= 0; writer increments on change. |
| `created_at`, `updated_at` | `timestamptz` | No | DEFAULT now(); updates explicitly write updated_at. |
| `created_by`, `updated_by` | `uuid` | No | DEFAULT all-zero system actor; no identity FK. |

CHECK `platform_fee = round((order_amount - shipping_fee) * platform_fee_rate, 2)` and `net_amount = order_amount - shipping_fee - platform_fee AND net_amount > 0`. Recipient snapshot columns are all NULL or all non-NULL with a bank reference. Partial UNIQUE `(provider,provider_reference) WHERE provider_reference IS NOT NULL`; separate local FK indexes; seller/status listing indexes; PENDING due index and PROCESSING/UNKNOWN attempt-start index. Confirmation requires a provider success, not acceptance. Retry can replace the current snapshot; prior attempt history remains immutable.

### payment_db.payout_history

#257 / payment-service V7. Append-only events; never update/delete an event to revise an earlier result.

| Field | PostgreSQL type | NULL? | Rule / purpose |
| --- | --- | --- | --- |
| `id` | `uuid` | No | PK DEFAULT uuidv7(). |
| `payout_id` | `uuid` | No | Local FK payouts(id); no cascade deletion. |
| `event_seq` | `bigint` | No | Positive sequence, UNIQUE(payout_id,event_seq). |
| `event` | `payout_history_event` | No | CREATED, HELD, RELEASED, SENT, SUCCEEDED, FAILED, TIMED_OUT, RECONCILED, RETRIED, RETURNED. |
| `from_status`, `to_status` | `payout_status` | Yes / No | Previous status NULL for creation; resulting status always present. |
| `attempt_no` | `integer` | No | CHECK >=0; zero before the first dispatch. |
| `amount` | `numeric(19,2)` | No | Positive net amount for this step. |
| `seller_bank_id` | `uuid` | Yes | Local bank FK. |
| `recipient_bank_code`, `recipient_holder_name` | `varchar(20)`, `varchar(100)` | Yes | Immutable recipient metadata for the attempt. |
| `recipient_account_encrypted`, `recipient_account_last4` | `text`, `char(4)` | Yes | Private immutable ciphertext and public last4 snapshot. |
| `provider`, `provider_reference` | `varchar(40)`, `varchar(120)` | Yes | Attempt provider identity, can repeat on several lifecycle events. |
| `provider_idempotency_key` | `varchar(100)` | Yes | Stable payout:{id}:{attempt}; same key when reconciling. |
| `provider_status`, `error_code`, `error_message` | `varchar(80)`, `varchar(100)`, `text` | Yes | Sanitized metadata, no secrets or raw provider messages. |
| `transaction_id` | `uuid` | Yes | Local payment_transactions FK, retaining each historical money movement. |
| `request_idempotency_key` | `varchar(100)` | Yes | UNIQUE admin retry request identity; other event rows use NULL. |
| `simulated` | `boolean` | No | Explicit true for sandbox, with no misleading false default. |
| `actor_id` | `uuid` | No | Admin UUID or system all-zero UUID. |
| `occurred_at` | `timestamptz` | No | DEFAULT now(); sequence controls lifecycle order. |

Database triggers reject UPDATE, DELETE and TRUNCATE. This table has no mutable version/updated_at/updated_by columns; actor_id/occurred_at describe the immutable step. `(payout_id,event_seq)` serves history reads; indexes cover bank/transaction FKs and provider key/reference lookup. Do not make provider_reference globally unique in history: SENT, success and return evidence can reference the same attempt. APIs whitelist public fields and never serialize recipient ciphertext.

### #257 payout integration and upgrade

The four new business tables are seller in user_db and seller_bank/payouts/payout_history in payment_db. Already-applied V5 payment_order_completions and payout_eligibility remain local durable projections, not a fifth new payout business table. Payment V6 adds transaction enum values before V7 uses them; V8 adds nullable order_amount/shipping_fee to the completion projection. Old rows retain NULL rather than invented shipping. Only upgraded, funded and consistent completion evidence creates automatic payouts.

Actual money movements use per-attempt keys `payout:{id}:{attempt}` and `payout-return:{id}:{attempt}`. This refines the single-key proposal so a successful payout can return and be successfully retried while retaining every movement. Payout transaction pointers describe the current attempt; payout_history.transaction_id preserves prior links. Existing buyer confirmation/refund/read queries continue filtering NORMAL/REFUND. See [automatic payout operations/configuration](../payment-payout-eligibility.md) and [event contracts](../api/events.md#ordercompleted).

### Combined payment, cancellation and reconciliation contract

**One complete group, one QR, one receipt:** commerce creates order_groups, seller orders/items/pricing and CheckoutPaymentRequested atomically. Payment creates its one immutable session and all per-order instructions atomically. A verified exact receipt writes one NORMAL/SUCCESS ledger entry, funds those instructions and writes CheckoutPaymentConfirmed in one payment transaction. Commerce applies the group snapshot in one local transaction and emits individual OrderPaid events for downstream inventory/shipment. No group amount is duplicated into several physical receipts, and no cross-database atomic transaction is claimed.

**Before payment:** cancel the entire order group/session or let its single QR expire. Do not independently cancel one unpaid seller child, alter the issued total, reopen the old session or create a replacement session for the same group. Commerce locks the PENDING order_groups row and all its children, commits their CANCELED states with OrderGroupCancelled and the individual OrderCancelled outbox events in one commerce transaction, and does not wait for a payment acknowledgement. Payment applies OrderGroupCancelled idempotently under the same session/instruction locks used for webhook funding: if still unpaid, set the session and its unpaid instructions CANCELED. Whole-group expiry closes the same single plan rather than generating another QR. If the complete manifest/session has not arrived, defer processing durably and do not mark cancellation handled merely because no row exists; apply it after the session prerequisites can be resolved. OrderGroupCancelled is an explicit R1 contract addition, not an existing publisher claim. R1 introduces no unpaid-child payment fence, waiting acknowledgement or reissue workflow.

**Payment versus asynchronous group cancellation:** cancellation is immediately serialized only within its owning database; do not state that commerce cancellation instantly makes a remote QR unpayable. If payment applies whole-group cancellation first, the session/instructions are closed and later money is UNRECONCILED, without funding orders. If a valid payment is accepted before the cancellation consumer catches up, keep the physical NORMAL SUCCESS movement, session PAID and instruction funding evidence. Commerce must not resurrect already canceled orders; retain receipt evidence and route canceled funded children through their existing automatic refund/compensation path. Matching duplicate confirmations/cancellations are no-ops; conflicting evidence is reconciled, not discarded. No distributed instant-cancellation guarantee is implied.

**After payment:** child orders may progress or be canceled independently. Preserve the existing paid shipment handover coordination: retain PAID/cancel_pending until the matching ShipmentCancellationConfirmed proves non-handover/successful carrier cancellation; unknown carrier results stay pending and a rejection preserves the lifecycle. The physical shipment fence remains distinct from the removed unpaid-child payment workflow. After final child OrderCancelled, its automatic refund is bounded by its own funded instruction and UNIQUE(refunds.order_id). Neither cancellation nor refund clears the accepted incoming ledger, reopens the group QR or spends a sibling's share.

**Example and migration:** orders for shop A=300000 and shop B=200000 share one session requesting 500000. One actual incoming movement of 500000 funds instructions of 300000 and 200000. Later full cancellation of A creates at most one system REQUESTED refund of A's funded share; B retains its funding/shipment lifecycle and the group/session retain payment evidence. Backfill older single-order records into verified singleton order groups/sessions/instructions, preserve IDs, memos and actual movements, and remove obsolete memo/direct ledger order fields only through new migrations after verified cutover. Do not fabricate missing historical evidence. This section defines contracts to implement; it does not execute DDL, modify the public API or certify runtime behavior.

### payment_db.outbox

**Owner / source:** payment_db; #240 and the V1 pattern. A new table under #240, using the same family of structures as existing technical tables; no target migration exists in the baseline.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Outbox record ID; proposed to remain stable and identical to eventId on publication/retry | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type name | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Aggregate ID as text, either a UUID or a business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraint` |
| `occurred_at` | `timestamptz` | No | `now()` | Event occurrence time | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not marked as published | `No additional constraint` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is already specified in the field definition; no FK to business tables or other databases. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business rules:** the business change and INSERT into outbox occur in the same local transaction. The proposed row id equals eventId in the envelope; relay retries preserve eventId, aggregate key and payload without generating a new ID. A relay can resend if it crashes after Kafka acknowledgement but before published_at is recorded: consumers must deduplicate; an outbox does not itself provide exactly-once delivery. Store the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends that envelope unchanged with the same eventId on every retry, without double wrapping. `aggregate_id` is varchar(100); do not silently change it to UUID or an FK, because some aggregates use text keys. Retain published outbox entries for at least 90 days; do not purge unpublished entries. Business ledger/uniqueness and the processed_events policy in the retention section protect the corresponding replay behavior.

### payment_db.processed_events

**Owner / source:** payment_db; #240 and the V1 pattern. A new table under #240, using the same family of structures as existing technical tables; no target migration exists in the baseline.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers for the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Local processing completion time | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not reduce it to event_id alone. No FK to another service's outbox/event_logs.

**Business rules:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. The UNIQUE marker prevents the same event from being processed twice by one handler, but does not prevent different eventId values for the same business operation, so the domain still needs appropriate order/provider-reference uniqueness. If the envelope lacks data required for application, do not mark it successfully processed and discard it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or the ledger lifetime. Replay beyond the window must use a restore/backfill workflow and business uniqueness; do not imply permanent deduplication after purging. Consumer names are stable per handler, not per instance.

<a id="shipment-db"></a>

## shipment-service / shipment_db

Target scope: `shipments` and `shipment_history` move from fulfillment to `shipment_db` under #241. Warehouse data remains in `inventory_db`; do not copy `warehouses` or create cross-database FKs. `outbox` and `processed_events` are shipment's own technical tables, specified in the technical-table section. #201 requires carrier adapters and a carrier-read API; it does not imply a `carriers` table.

Sources read: `fulfillment-service/src/main/resources/db/migration/V1__init.sql` (V2 only seeds warehouses); #241, #201, #202, #203; #249 for audit actors; `docs/api/events.md` to check gaps in the current OrderPaid contract. Fields/constraints marked **Proposed** below are concrete designs for review, not column names predefined by an issue or approved migrations.

### shipment_db.shipments

Purpose: one shipment per order, storing the selected carrier, tracking information, the snapshot required to create a shipping order, and retry state. Preserve every source field; label additions for snapshots/ownership/idempotency/concurrency.

Propose enum type `shipment_status` for both shipment and history. The target set preserves every V1 value and adds the status required by #202: `CREATED`, `PENDING_TRACKING`, `PICKING`, `PACKED`, `READY_TO_SHIP`, `SHIPPED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `DELIVERY_FAILED`, `RETURNING`, `RETURNED`, `CANCELLED`. Retain shipment_status from code #249 and use the same type for history to prevent invalid status names; extend it with PENDING_TRACKING under #202. In particular, shipment uses `CANCELLED` with two Ls as in the baseline; commerce uses `CANCELED` with one L under #116. Do not silently rename wire/state values to make them identical.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` if not supplied on insert | Shipment ID | PK, retain V1. |
| `order_id` | `uuid` | No | None | Paid order served by the shipment | Retain table-wide UNIQUE from V1; logical UUID reference to commerce, no FK. |
| `warehouse_id` | `uuid` | No | None; the service selects/receives a warehouse before insert | Dispatch warehouse | Retain the V1 field, **remove the FK to warehouses** under #241/#243; logical reference to `inventory_db.warehouses.id`. |
| `carrier` | `varchar(40)` | No | None; adapter/config resolves it if omitted from the request | Code of the carrier handling the shipment | Retain V1. No fixed enum or carrier table without a carrier-management requirement. |
| `tracking_code` | `varchar(80)` | Yes | None | Tracking code returned by the carrier | Retain V1; may be NULL in `PENDING_TRACKING`; the baseline has no system-wide UNIQUE constraint. |
| `shipment_status` | `shipment_status` | No | `'CREATED'::shipment_status` | Normalized internal state | V1 uses `varchar(30)` + CHECK; add `PENDING_TRACKING` under #202. Type name/cast/shared enum are proposed. |
| `service_type` | `varchar(40)` | Yes | None | Selected carrier service option | Retain V1; when omitted from a request, adapter/service selects it under #201/#202. |
| `shipping_fee` | `numeric(19,2)` | No | `0` | Selected/snapshotted shipping fee | Retain `CHECK (shipping_fee >= 0)` from V1; do not recalculate it in a way that changes the commerce order. |
| `created_at` | `timestamptz` | No | `now()` | Internal shipment creation time | Retain V1. |
| `updated_at` | `timestamptz` | No | `now()` on insert | Record update time | Retain V1; service/JPA updates it; no baseline trigger. |
| `buyer_id` | `uuid` | No after backfill | None; from the OrderPaid snapshot | Owner used to authorize buyer access to the shipment | **Proposed for #202** to authorize from local data; no user/commerce FK. Not present in the baseline. |
| `shipping_info` | `jsonb` | No after backfill | None; receive a snapshot approved in the event contract | Recipient/address/items needed by the carrier | **Proposed for #201/#202**; object CHECK below; do not default to `{}` to hide a missing payload. |
| `idempotency_key` | `varchar(100)` | Yes | None | Key for manual shipment-creation POST | **Proposed for #202**; non-NULL values are UNIQUE across `shipment_db.shipments`. Automatic consumers leave it NULL and deduplicate through processed_events and order uniqueness. |
| `version` | `bigint` | No | `0` | Optimistic locking for concurrent webhook/poller/manual updates | **Proposed for #203**, mapped with JPA `@Version`; do not substitute update time for version. |
| `tracking_retry_count` | `integer` | No | `0` | Number of retries to obtain tracking after initial creation | **Proposed for #202**; `CHECK (tracking_retry_count >= 0)`. The retry limit comes from runtime configuration, not a configuration-dependent fixed CHECK. |
| `next_tracking_retry_at` | `timestamptz` | Yes | None | Next tracking retry time | **Proposed for #202**; NULL when no retry is scheduled. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor, immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `cancel_request_id` | `uuid` | Yes | None | ID of the cancellation request received by shipment | Logical contract from commerce; stable replay ID. |
| `cancel_fenced_at` | `timestamptz` | Yes | None | Local fence preventing shipping-order creation/handover during cancellation | Shipment/cancel workers and pickup use the same row lock/version. |
| `handed_over_at` | `timestamptz` | Yes | None | Time the carrier received/took custody of the goods | Do not ACK successful cancellation when non-NULL; verified carrier source or authorized operation. |

Constraints and indexes:

- Retain `PRIMARY KEY (id)`, `UNIQUE (order_id)`, `CHECK (shipping_fee >= 0)`. Order uniqueness guarantees at most one shipment per order; do not change to multiple shipments per order without issue authorization.
- Remove only the `warehouse_id → warehouses(id)` FK from the fulfillment source. `order_id`, `warehouse_id` and `buyer_id` are logical UUID references; no physical FK to commerce, inventory or user databases. Do not validate existence by querying another service's database.
- Retain regular indexes `idx_shipments_status (shipment_status, created_at DESC)` and `idx_shipments_tracking (tracking_code)`. Do not make the tracking index unique, because the same code may exist in another carrier namespace; it is not a provider-event deduplication key.
- Propose `UNIQUE (idempotency_key)` on the nullable field: multiple automatic shipments can have NULL, while each non-NULL manual key occurs only once. Replay with the same key and content returns the existing shipment; the same key with different content returns a conflict. Use the service-wide scope specified by this unique constraint; the key is always tied to an authenticated request, and replay with a different key/body returns a conflict. Do not switch to a composite actor key in this schema.
- Propose `CHECK (tracking_retry_count >= 0)` and `CHECK (jsonb_typeof(shipping_info) = 'object')`. The service validates JSON fields against the contract; syntactically valid JSONB is insufficient.
- Propose retry index `idx_shipments_tracking_retry ON shipments (next_tracking_retry_at, id) WHERE shipment_status = 'PENDING_TRACKING' AND next_tracking_retry_at IS NOT NULL` for jobs selecting due rows. Propose `idx_shipments_buyer ON shipments (buyer_id, created_at DESC)` for a buyer's shipment list. New indexes require EXPLAIN against real queries in the implementation issue; they do not replace query-plan verification.

Selected target JSON snapshot for updating contract #186/#202; not an existing publisher payload:

| JSON path | Type / requirement | Meaning / validation |
|---|---|---|
| `shipping_info.recipient` | object, required | Recipient. |
| `shipping_info.recipient.name` | nonempty string, required | Recipient name; do not silently substitute the buyer's name for the recipient. |
| `shipping_info.recipient.phone` | nonempty string, required | Delivery contact number. |
| `shipping_info.recipient.address` | object, required | Selected delivery address; locality codes/field structure must follow the contract approved for #201/#186. |
| `shipping_info.items` | nonempty array, required | Snapshot of order lines. |
| `shipping_info.items[].skuId` | UUID string, required | Logical SKU reference, no FK. |
| `shipping_info.items[].productName` | string, required | Name snapshotted when the order was confirmed. |
| `shipping_info.items[].quantity` | integer > 0, required | Quantity to ship. |

The snapshot additionally includes parcel{weightGrams,lengthCm,widthCm,heightCm} and pickup address/locality codes when the GHN adapter requires them. Measurements come from verified data or a validated shipment-preparation step; do not add out-of-scope catalog columns. If required measurements are unavailable, retain PENDING_TRACKING and report validation/Support action to complete them before calling the carrier. Do not fabricate values for carrier calls or change historical addresses when a user profile changes. The current event contains only order/buyer/orderType/reference/paidAt, without a shipping snapshot or warehouse; this is an actual contract gap, not evidence that the proposed JSON fields above already exist in the publisher.

Lifecycle and enforcement:

- #202: OrderPaid is processed through `IdempotentConsumer`. Duplicate delivery of an event or a manual POST racing on the same order still produces one `shipments` row, using `(event_id, consumer)` and order UNIQUE. Write the shipment/required history/outbox/processed marker in a local transaction, not one spanning commerce_db.
- Buyer reads require `buyer_id = CurrentUser.id`; Admin/Support access follows API privileges. The buyer snapshot comes from a trusted event, not the caller's body. If snapshot data is missing, do not bypass ownership checks or call commerce_db directly.
- Carrier success: store tracking_code and emit `ShipmentCreated` under #202. Temporary carrier failure: the shipment remains in `PENDING_TRACKING`, tracking_code may be NULL, and a job retries using backoff/configuration. `tracking_retry_count` increments for actual retries, and `next_tracking_retry_at` records the schedule; clear the retry schedule once the code exists. There is no CHECK such as `status != PENDING_TRACKING → tracking_code NOT NULL`, because CREATED/PICKING/PACKED may exist before the carrier issues tracking; the service checks for a code at milestones where tracking is required.
- Prevent two external carrier shipping orders if a worker crashes between the carrier response and commit: use idempotency/reference supported by CarrierAdapter based on `order_id` or shipment ID, then reconcile the result on retry. Database UNIQUE does not itself guarantee exactly-once external side effects; do not claim these operations are atomic.
- #203: webhook/poller/manual updates use the same state machine and version/conditional update. An accepted change writes exactly one history row and one `Shipment*` outbox event; a duplicate/no-op adds neither history nor an event. Reject backward updates such as `DELIVERED → IN_TRANSIT` under AC #203; override requires separate validation and a nonempty reason at the service.
- Event names differ from state names: proposed mapping `ShipmentPickedUp → SHIPPED`, `ShipmentInTransit → IN_TRANSIT`, `ShipmentDelivered → DELIVERED`, `ShipmentFailed → DELIVERY_FAILED`, `ShipmentReturned → RETURNED`. `ShipmentCreated` describes shipment creation; do not add a `PICKED_UP` state to the enum. This mapping is a target contract choice for #203/#204; commerce transitions PAID→SHIPPED only on the correct pickup event, not merely from a carrier status name.


### shipment_db.shipment_history

Purpose: an append-only history of accepted statuses, with source and actor/reason metadata for traceability under #203. Preserve all five source fields; propose deduplication and audit metadata in the same table, without adding a third carrier/tracking table before it is needed.

| Field | Target PostgreSQL type | NULL? | Default / generation | Meaning | Constraints, source and status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` if not supplied on insert | History ID | PK, retain V1. |
| `shipment_id` | `uuid` | No | None | Shipment being changed | Retain local FK `shipments(id) ON DELETE CASCADE`; do not remove this FK during the split. |
| `shipment_status` | `shipment_status` | No | None | Accepted new status | Baseline `varchar(30)` has no CHECK; **propose** using the same target enum to prevent incorrect status names in history. |
| `description` | `varchar(255)` | Yes | None | Description or manual override reason | Retain V1; the service requires a nonempty reason for an override and must not silently truncate content exceeding 255 characters. |
| `created_at` | `timestamptz` | No | `now()` | Time the system records the change | Retain V1; not necessarily the carrier occurrence time when the payload reports a different timestamp. |
| `carrier_event_id` | `varchar(160)` | Yes | None | Stable identifier of a carrier delivery/event for deduplication | **Proposed for #203**; NULL for non-webhook updates or historical data; source/format must be finalized for GHN. |
| `source` | `varchar(30)` | No | `'SYSTEM'` | Change source | **Proposed for #203**; CHECK against `SYSTEM`, `CARRIER_WEBHOOK`, `POLLER`, `MANUAL`. |
| `changed_by` | `uuid` | Yes for historical data | None | Actor performing the state transition | **Proposed for #203**; manual uses CurrentUser, automatic uses the fixed system actor from #249; no FK. |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor, immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

Constraints/indexes/relationships:

- Retain PK `(id)` and FK `shipment_id REFERENCES shipments(id) ON DELETE CASCADE ON UPDATE NO ACTION`. Each history row belongs to exactly one shipment; a shipment has 0..n history rows. The local FK remains effective after the service move.
- Retain regular index `idx_shipment_history (shipment_id, created_at DESC)`. Queries needing a stable order for equal timestamps can add `id` as a tie-breaker in ORDER BY; do not modify the baseline index unless the query plan requires it.
- Propose `CREATE UNIQUE INDEX uq_shipment_history_carrier_event ON shipment_history (shipment_id, carrier_event_id) WHERE carrier_event_id IS NOT NULL`. Repeated receipt of one external event for the same shipment does not add another row. The shipment's carrier remains fixed after creation; the event key includes the carrier namespace in its canonical identity. Changing carrier requires a pre-handover workflow.
- Propose `CHECK (source IN ('SYSTEM','CARRIER_WEBHOOK','POLLER','MANUAL'))`. Do not require a description for every MANUAL operation through a database CHECK, because #203 requires a reason specifically for overrides; the service distinguishes ordinary PATCH from override and validates reason/actor for the relevant endpoint.
- History is append-only in the service/repository. `ON DELETE CASCADE` preserves the baseline FK behavior; it does not authorize an endpoint to delete history or arbitrarily hard-delete shipments. Retain it with order evidence for at least 5 years; do not hard-delete related shipments/history before that point.

Deduplication and state handling:

- A webhook verifies its signature before any data effect. With a stable carrier event ID, update shipment, insert history and write outbox in one transaction; recognize a duplicate key as replay and return 200 under #203 rather than 500. An invalid signature writes neither shipment nor history.
- If the carrier provides no event ID, use a canonical hash under the finalized shipping policy; the adapter must verify the fields actually present in the payload and their stability across replay. State CAS remains the final no-op guard. Do not use an unverified caller-supplied timestamp as a trusted key.
- A poller without an external delivery ID still uses the state machine and version/conditional update: retrieving the same state twice must not create two history rows/events. History records accepted state changes, not every empty poll as a transition.
- Backward/duplicate/out-of-order updates and manual overrides require specific rules approved under #203. DDL enums/uniqueness enforce value names and uniqueness only; they do not enforce the entire transition graph.

### Shipping choices finalized against SRS/BR

- The OrderPaid snapshot includes orderId/orderVersion, buyerId, warehouseId, selected carrier/serviceType, shippingFee, recipient/address, items and parcel information required by the adapter. Product name/quantity are order snapshots; do not confuse warehouse UUIDs with codes. Missing required data goes to retry/DLQ to complete the contract; do not fabricate snapshots or SELECT from commerce_db.
- The default warehouse uses configured code WH_001, resolving its local inventory UUID through the approved contract; the default carrier is GHN, with GHN/GHTK supported under BR-196. If the caller omits serviceType, use the selected quote/adapter option; do not hardcode an unverified GHN service. R1 uses one warehouse/one shipment per order under #241/#202; multi-warehouse is out of scope here even though SRS allows a maximum of 2 when the feature is enabled.
- Carrier retry timeout is 3000ms, with at most 3 retries after the first call (BR-208), configurable exponential backoff, and a circuit breaker per carrier. PENDING_TRACKING retains a NULL tracking_code until a result exists. Jobs divide claims using local row lock/version/lease; do not automatically add shedlock to a new service when the claim mechanism is sufficient.
- Webhooks use a stable provider event ID when available; otherwise the adapter creates a SHA-256 hash from canonical carrier/tracking/status/providerOccurredAt/identifying payload data, rather than a random UUID per delivery. Duplicate states use CAS/no-op to avoid another history row/event. Keep the carrier fixed once the shipment has an identity; changing carrier is a separate pre-handover workflow.
- Event/state mapping: ShipmentPickedUp→SHIPPED, ShipmentInTransit→IN_TRANSIT, ShipmentDelivered→DELIVERED, ShipmentFailed→DELIVERY_FAILED, ShipmentReturned→RETURNED. Reject ordinary backward transitions; manual override requires Admin/Support, a nonempty reason and immutable history/outbox.
- **Cancellation fence:** cancellation and pickup/dispatch use the same shipment row lock/version. On OrderCancellationRequested, set cancel_request_id/cancel_fenced_at before calling the carrier; all workers stop handover from then on. ACK CONFIRMED only after establishing that the carrier has not taken custody and any required external cancellation of an issued shipping order has succeeded. If handed_over_at exists or the external-call result is uncertain, return REJECTED or remain pending/manual; do not assume a database lock makes a carrier call atomic. If no row exists but OrderPaid is still queued, defer the request using the order sequence until a materialized shipment can be fenced; do not ACK merely because SELECT finds no row. An old OrderPaid must not reopen a CANCELLED/fenced shipment. On a definitive REJECTED result, clear only the cancel_fenced_at for the matching requestId/version and continue the existing lifecycle; retain the request/reason in history. If the carrier outcome remains unknown, retain the fence and pending state; do not clear them merely because of a timeout. A duplicate/stale ACK must not remove a newer request's fence.

### shipment_db.outbox

**Owner / source:** shipment_db; #241 and the V1 pattern. A new table under #241, using the same family of structures as existing technical tables; no target migration exists in the baseline.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Outbox record ID; proposed to remain stable and identical to eventId on publication/retry | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type name | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Aggregate ID as text, either a UUID or a business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraint` |
| `occurred_at` | `timestamptz` | No | `now()` | Event occurrence time | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not marked as published | `No additional constraint` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is already specified in the field definition; no FK to business tables or other databases. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business rules:** the business change and INSERT into outbox occur in the same local transaction. The proposed row id equals eventId in the envelope; relay retries preserve eventId, aggregate key and payload without generating a new ID. A relay can resend if it crashes after Kafka acknowledgement but before published_at is recorded: consumers must deduplicate; an outbox does not itself provide exactly-once delivery. Store the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends that envelope unchanged with the same eventId on every retry, without double wrapping. `aggregate_id` is varchar(100); do not silently change it to UUID or an FK, because some aggregates use text keys. Retain published outbox entries for at least 90 days; do not purge unpublished entries. Business ledger/uniqueness and the processed_events policy in the retention section protect the corresponding replay behavior.

### shipment_db.processed_events

**Owner / source:** shipment_db; #241 and the V1 pattern. A new table under #241, using the same family of structures as existing technical tables; no target migration exists in the baseline.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers for the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Local processing completion time | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not reduce it to event_id alone. No FK to another service's outbox/event_logs.

**Business rules:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. The UNIQUE marker prevents the same event from being processed twice by one handler, but does not prevent different eventId values for the same business operation, so the domain still needs appropriate order/provider-reference uniqueness. If the envelope lacks data required for application, do not mark it successfully processed and discard it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or the ledger lifetime. Replay beyond the window must use a restore/backfill workflow and business uniqueness; do not imply permanent deduplication after purging. Consumer names are stable per handler, not per instance.

<a id="rating-db"></a>

## rating-service / rating_db

**Owner and scope:** `rating-service` owns ratings, eligibility, and reputation aggregates/history. Move `ratings` and `user_reputations` from `user_db`, preserving their UUIDs; add `rating_eligibility` under #242 and `reputation_history` under #213. The service's database and technical tables are not shared with the source owner; the following section contains only **four business tables**, with technical tables specified separately.

**Sources and interpretation:** SRS §3.1.2; BR-033–BR-050 in `Bussiness Rule.xlsx`, sheet `Trang tính1`, rows 38–55; `SQL_ERD_BR_Review.xlsx`, sheet `BR_Review`, rows 38–55; user V1 lines 81–108; #212/#213/#234/#242/#249. **Existing state** labels structures/code that were read; **SRS/BR/issue requirement** labels requirements; **Design choice** labels a concrete implementation option, not an applied migration or evidence of reviewer approval. User V3 only seeds data and does not change the two source schemas.

**Business decisions used in this design:**

- Ratings are **bidirectional, buyer ↔ seller**, with at most one rating per party per transaction and no self-rating (BR-033–BR-037). Retain the 1–5 scale and maximum 1,000-character comment under SQL/#212; SRS/BR do not themselves specify the 1–5 range.
- Use a **completed order as the canonical transaction**, covering both STANDARD and orders created from successful auctions. `rating_eligibility` receives only `OrderCompleted`; include auction context in `auction_id` when applicable. SRS allows a completed order or successfully ended auction; this #242 implementation represents the auction transaction through its corresponding completed order. `AuctionWon`/`AuctionSettled` alone do not grant eligibility while the sale is incomplete, and an auction ID does not allow a second rating for the same transaction.
- An ordinary user creates a rating once and cannot independently edit/delete a submitted score. Admin may resolve disputes and adjust reputation effects through an authorized service with a reason and immutable history under BR-048–BR-050. Support has viewing/assistance rights according to permissions, without implicitly receiving authority to change reputation scores.
- `OrderCompleted` updates transaction counters once for **both buyer and seller**. A rating updates the aggregate of **the rated user**. Do not automatically award a fixed number of points to both parties merely for completing an order: SRS/BR-038 do not define that formula.
- Score calculation uses a **configured, versioned policy** based on ratings, transaction/auction outcomes, penalties and audited adjustments. SRS supplies no specific coefficients; `TRUST_LEVEL_THRESHOLDS` is `CONFIGURED`. #213 owns valid formula/coefficient/threshold configuration; do not leave this as an ownerless question or present arbitrarily chosen numbers as SRS requirements. When enforcement is enabled, fail service startup if the policy version, formula/coefficients or thresholds are missing; thresholds must cover score 0–100 without overlap and remain consistent with the neutral 50/NEW default for a user without activity. A policy may use sample/outcome counts to distinguish NEW, but must configure this explicitly.
- Retain initial **score 50, trust NEW** and zero counters from the baseline. After activity, recompute from verified inputs, clamp score to 0–100, and record the actual post-clamp delta in history. The default bid threshold is 40 and auction-creation threshold 50; the default auction payment-timeout penalty is 10 under SRS §2.6/BR-041/042/045. Read these from configuration rather than encoding environment-dependent CHECK constraints.

**Enum and IDs:** `rating_trust_level = ('NEW','BASIC','TRUSTED','TOP')`; the four literals are present in the source CHECK/#242, while the PostgreSQL type name is a design choice. `Rating.java` and `UserReputation.java` currently use `@UuidGenerator(style = VERSION_7)`; source DDL has `DEFAULT uuidv7()`. Retain both JPA UUIDv7 generation and the database fallback for these two entities; do not replace IDs during the move. The new history table uses `uuid DEFAULT uuidv7()` under the database convention; `rating_eligibility.order_id` comes from commerce, without generating a new order ID.

**Audit aligned with the inspected #249 branch:** all four tables have `created_by`, `updated_by uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid`. This is the fixed system actor from code #249, not a randomly created user account. JPA auditing assigns the authenticated request actor or the consumer/job system actor; assign both columns on creation and preserve created_by on updates. The database default supports direct inserts/backfill; use the actual actor when evidence identifies one. No actor/user ID FK points to user/auth databases. For append-only history, initialize updated_by without implying permission to rewrite history.

### rating_db.user_reputations

**Owner / purpose:** `rating-service` / `rating_db`; one aggregate per user for score/trust calculation and outcome summaries. **Source / changes:** user V1; retain all 10 source columns, remove the users FK, convert trust to an enum, add five input aggregates for BR-038/040 and audit under #249. The additional aggregate columns are design choices, absent from baseline migrations.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source-status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | JPA UUIDv7; DB `uuidv7()` | Aggregate PK; preserve the ID during migration | Existing SQL/JPA |
| `user_id` | `uuid` | No | — | User whose reputation is calculated; logical identity reference | Existing UNIQUE; #242 removes the users FK |
| `score` | `smallint` | No | `50` | Reputation score, distinct from the 1–5 rating | CHECK `score BETWEEN 0 AND 100`; existing/#213 |
| `trust_level` | `trust_level` | No | `'NEW'` | NEW/BASIC/TRUSTED/TOP according to policy | Source literals; enum under #242, type name a design choice |
| `total_transaction` | `integer` | No | `0` | Total transaction outcomes processed for the user under policy | CHECK `>= 0`; existing |
| `successful_transaction` | `integer` | No | `0` | Count of recorded COMPLETED canonical orders | CHECK `>= 0`; existing column, counting semantics a design choice |
| `failed_transaction` | `integer` | No | `0` | Failures defined by the outcome contract; do not count every voluntary cancellation as a failure | CHECK `>= 0`; existing column, mapping under #213 |
| `rating_count` | `integer` | No | `0` | Valid ratings currently included by policy for the user | CHECK `>= 0`; design choice for BR-038 |
| `rating_score_sum` | `bigint` | No | `0` | Sum of effective 1–5 scores after dispute decisions | CHECK `>= 0`; design choice; calculate the average using decimal arithmetic when count > 0 |
| `penalty_points` | `integer` | No | `0` | Total effective penalty points currently applied; never count the same timeout twice | CHECK `>= 0`; design choice for BR-044/045 |
| `successful_auction_count` | `integer` | No | `0` | Confirmed successful auction outcomes for the user according to the role in the event | CHECK `>= 0`; design choice for BR-040 |
| `failed_auction_count` | `integer` | No | `0` | Confirmed failed auction outcomes for the user; do not assume every losing bidder violated a rule | CHECK `>= 0`; design choice for BR-040 |
| `created_at` | `timestamptz` | No | `now()` | Aggregate creation time | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Last change time; the writer updates it on UPDATE | Existing V1 |
| `version` | `bigint` | No | `0` | Optimistic locking; every writer must check/increment it | Existing V1; the source has no nonnegative CHECK |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; JPA uses user/system | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Last updating actor; JPA uses user/system | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys / constraints / indexes:** PK `(id)` and UNIQUE `(user_id)` create two implicit unique B-tree indexes. CHECK score 0–100 and individually CHECK all three source counters and five new input aggregates as nonnegative. The trust enum replaces the varchar CHECK with the same domain. Retain the baseline's absence of separate secondary indexes; remove `user_id REFERENCES users(id) ON DELETE CASCADE` without cross-database cascading. Do not add a CHECK on counter totals or `rating_score_sum <= 5 * rating_count` to conceal historical data differences; the service maintains invariants on verified, backfilled target data and reconciles them against ratings/history. Use bigint/decimal for multiplication/division to avoid overflow or incorrect rounding.

**Relationships / lifecycle / enforcement:** a logical user has 0..1 aggregate; an aggregate has 0..N history rows through a local FK. Create a neutral aggregate if absent, then update under a row lock/optimistic version with input counters, score/trust, history, outbox and processed marker in the **same local transaction**. A stable order/auction source identity prevents counting twice when an envelope ID changes. Auction metrics and order metrics are distinct measures; do not count both again in total_transaction for the same auction transaction. Apply `AuctionPaymentTimeout` once to the winner using the configured default of 10; do not penalize the seller or all bidders. Record history even when counters/trust change with a zero score delta. The source has no soft delete. Dispute corrections recompute from effective inputs rather than adding the entire rating again as a new rating.

### rating_db.ratings

**Owner / purpose:** `rating-service` / `rating_db`; one original rating per party for a completed canonical order. **Source / changes:** retain all nine V1 columns, remove the from/to users FKs, add dispute flags under BR-049, #249 audit, and a local FK to eligibility. Storage `order_id` remains nullable to preserve legacy data lacking evidence; R1 service always requires a canonical order ID and never creates a new rating with a NULL order.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source-status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | JPA UUIDv7; DB `uuidv7()` | Rating PK; preserve the UUID during migration | Existing SQL/JPA |
| `from_user_id` | `uuid` | No | — | Submitter; obtained from JWT rather than a client-declared actor | Existing; #242 removes the users FK |
| `to_user_id` | `uuid` | No | — | The exact other party among the order's buyer/seller | Existing; #242 removes the users FK |
| `order_id` | `uuid` | Yes in storage | — | Canonical order ID; required by R1 | Source nullability; local eligibility FK is a design choice |
| `auction_id` | `uuid` | Yes | — | Auction context when the canonical order is AUCTION | Existing; logical reference, not an additional rating opportunity |
| `score` | `smallint` | No | — | Original 1–5 score submitted by the user; not silently rewritten for a dispute | CHECK `score BETWEEN 1 AND 5`; existing/#212 |
| `comment` | `varchar(1000)` | Yes | — | Optional original comment | Existing; type limits it to 1,000 characters |
| `created_at` | `timestamptz` | No | `now()` | Original rating submission time | Existing |
| `updated_at` | `timestamptz` | No | `now()` on INSERT | Dispute metadata update time; does not authorize a user to edit the score | Existing |
| `is_disputed` | `boolean` | No | `false` | Rating currently flagged as disputed | BR-049 requires flagging capability; name/type are design choices |
| `dispute_reason` | `text` | Yes, required when flagged | — | Reason for opening the dispute; does not overwrite the comment | Design choice for BR-049 |
| `disputed_at` | `timestamptz` | Yes, required when flagged | — | Most recent dispute time; retain after resolution for traceability | Design choice for BR-049 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Submitting actor; must match from_user_id for a new request | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Actor updating metadata through an authorized service | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys / constraints / indexes:** PK `(id)`; CHECK `score BETWEEN 1 AND 5`; CHECK `from_user_id <> to_user_id`; retain `CREATE INDEX idx_ratings_to_user ON ratings (to_user_id);` and `CREATE UNIQUE INDEX uq_ratings_from_order ON ratings (from_user_id, order_id) WHERE order_id IS NOT NULL;`. This UNIQUE allows one rating per party per order, not one shared rating for both parties. **New design choice:** FK `(order_id) REFERENCES rating_eligibility(order_id) ON DELETE NO ACTION ON UPDATE NO ACTION`, NOT DEFERRABLE; CHECK `NOT is_disputed OR (dispute_reason IS NOT NULL AND btrim(dispute_reason) <> '' AND disputed_at IS NOT NULL)`. Do not require reason/timestamp to become NULL after resolution, because their timestamps must be retained; complete resolution/reopening decisions remain in history. Do not add UNIQUE auction_id as a second transaction key.

**Relationships / lifecycle / enforcement:** the service resolves eligibility, requires the JWT caller to be the buyer or seller and to_user_id to be the other party, and compares auction_id with the projection when present, rather than merely checking that two UUIDs exist. A normal order allows at most two valid ratings; the order FK alone does not enforce membership. Original score/comment remain immutable for ordinary users. When Admin resolves a dispute, retain the original and record `DISPUTE_ADJUSTMENT` history with reason, actor, before/after inputs/score and a new source_version; policy determines the rating's validity/effective score from the audited decision. Do not exclude every disputed rating immediately on flagging, which could allow abuse to lower scores; change its effect only through an authorized decision. A resolution with zero score delta still requires audit. Reopening updates current metadata while preserving every prior decision in history. This table has no source delete/version/idempotency-key field; uniqueness, row locking and history operation identity protect updates.

**Data migration:** preserve rating ID/from/to and original score/comment. Backfill eligibility only from commerce evidence of completion with the correct buyer/seller; do not fabricate completed_at, set order_id to NULL to evade an FK, or publish fake events. If legacy data is not fully reconciled, defer FK validation according to the migration plan and preserve the data for handling; NOT VALID still checks new INSERT/UPDATE operations. Missing audit evidence uses the fixed system actor under #249, without presenting it as the verified historical submitter.

### rating_db.rating_eligibility

**Owner / purpose:** `rating-service` / `rating_db`; authoritative projection of `OrderCompleted` used to check both parties' rating eligibility. **Source / changes:** #242 requires order_id/buyer_id/seller_id/completed_at; auction_id, source event/version and the keys below are concrete design choices. Do not call commerce synchronously or read another database to authorize a rating.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source-status |
|---|---|---|---|---|---|
| `order_id` | `uuid` | No | From OrderCompleted | PK, one projection per canonical order | Field required by #242; PK is a design choice |
| `buyer_id` | `uuid` | No | From OrderCompleted | Verified buyer in the transaction | Field required by #242; logical user reference |
| `seller_id` | `uuid` | No | From OrderCompleted | Verified seller in the transaction | Field required by #242; logical user reference |
| `completed_at` | `timestamptz` | No | From OrderCompleted; no SQL default | Business completion time, not consumer receipt time | Field required by #242 |
| `auction_id` | `uuid` | Yes | From the order snapshot if AUCTION | Links the completed auction transaction to its canonical order | Design choice for BR-033/035; no auction database FK |
| `source_event_id` | `uuid` | No | Verified envelope eventId | Projection creation source; do not generate a fake ID on replay | Traceability design choice |
| `source_version` | `bigint` | Yes | From the order-version contract when available | Source version used to distinguish old/new corrections | CHECK `>= 0` when not NULL; design choice |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Consumer/JPA actor | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Consumer actor; do not arbitrarily change the snapshot on replay | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Keys / constraints / indexes:** PK `(order_id)`; CHECK `buyer_id <> seller_id`; CHECK `source_version IS NULL OR source_version >= 0`. No UNIQUE buyer/seller because one person can participate in many transactions. Do not add a surrogate id, commerce/user/auction FK or secondary index for order-ID-only lookup; the PK index serves this access. source_event_id is evidence; do not add a separate UNIQUE that could prevent a valid future batch envelope containing multiple orders if the contract later supports batches; currently the consumer processes one order per OrderCompleted contract.

**Relationships / lifecycle / enforcement:** a logical commerce order has 0..1 projection because events can be delayed; multiple ratings can reference a projection, with the service/unique constraint limiting valid ratings to the two directions. The consumer verifies producer/schema, then writes the projection, processed marker and related counter/history updates in the same local transaction. Replay with the same order and buyer/seller/completedAt/auction context is a no-op; the same order with different content is a conflict requiring a correction contract/version, not an arbitrary-event upsert overwrite. Only `OrderCompleted` creates eligibility; OrderCreated, OrderPaid, AuctionWon or shipment status alone grants no permission. An AUCTION order must use its canonical order, without an independent direct-auction rating that would rate the same transaction twice. Preserve the historical projection; do not soft-delete it to recreate rating eligibility.

### rating_db.reputation_history

**Owner / purpose:** `rating-service` / `rating_db`; immutable history explaining every input, score/trust, penalty and dispute-decision change. **Source:** #213 requires history; BR-044/045/048–050 require audit for penalties/adjustments/disputes. The table name, new fields and history enum are design choices, not existing migrations.

**Selected target enum:** `reputation_change_type = ('USER_RATED','ORDER_COMPLETED','AUCTION_OUTCOME','AUCTION_PAYMENT_TIMEOUT','ADMIN_ADJUSTMENT','DISPUTE_ADJUSTMENT','POLICY_RECALCULATED')`. These seven literals classify history; they do not automatically create Kafka events with the same names. Event publication must follow contract #186/#246. `trust_level` is shared locally with the aggregate within rating_db.

| Field | Target PostgreSQL type | NULL? | Default / value generation | Meaning | Constraints / source-status |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | New history PK | Design choice for #213 |
| `user_id` | `uuid` | No | From the locked aggregate | User affected, distinct from the executing actor | Local FK to user_reputations.user_id; design choice |
| `operation_id` | `uuid` | No | Stable source-operation UUID, no SQL default | Operation identity preserved across retries; one operation can affect buyer and seller | Composite uniqueness below; design choice |
| `policy_version` | `varchar(60)` | No | From policy configuration; no SQL default | Version of the formula/thresholds actually used for this result | Nonblank CHECK; design choice for #213 |
| `source_version` | `bigint` | Yes | Source operation revision; no SQL default | Initial rating uses 0; dispute corrections increment the revision under row lock | CHECK `>= 0` when not NULL; design choice |
| `previous_score` | `smallint` | No | — | Score before the change | CHECK 0–100; design choice |
| `new_score` | `smallint` | No | — | Score after policy calculation/clamping | CHECK 0–100; design choice |
| `delta` | `smallint` | No | — | Actual difference new_score − previous_score | CHECK -100–100, allowing 0; design choice |
| `previous_trust_level` | `trust_level` | No | — | NEW/BASIC/TRUSTED/TOP before the change | Design choice |
| `new_trust_level` | `trust_level` | No | — | Trust after policy evaluation | Design choice |
| `change_type` | `reputation_change_type` | No | — | One of the seven history types above | Design choice |
| `source_id` | `uuid` | Yes, conditionally | — | Rating/order/auction ID according to type; admin/policy operation ID if provided | No polymorphic FK; design choice |
| `event_id` | `uuid` | Yes | eventId if triggered by an event | Source envelope ID, NULL for a direct operation | Design choice; not automatically the outbox row ID |
| `reason` | `text` | Yes, required for admin/dispute/policy | — | Meaningful reason and source decision/inputs | Design choice for BR-048–050 |
| `changed_by` | `uuid` | Yes, required for admin/dispute/policy | — | Person authorized to approve/initiate correction/recomputation | Logical reference, distinct from user_id; design choice |
| `change_details` | `jsonb` | Yes | — | Before/after input snapshots, effective rating/dispute outcome, counters, penalty and policy parameters needed for recomputation | Design choice; service validates schema/version; no raw secrets |
| `created_at` | `timestamptz` | No | `now()` | Local history recording time | Design choice |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | User/system actor creating history | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Initialized to the creating actor; history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Selected keys and constraints:**

- PK `(id)`; FK `(user_id) REFERENCES user_reputations(user_id) ON DELETE NO ACTION ON UPDATE NO ACTION`, NOT DEFERRABLE; target user_id is UNIQUE, not the id PK.
- CHECK `previous_score BETWEEN 0 AND 100`; CHECK `new_score BETWEEN 0 AND 100`; CHECK `delta BETWEEN -100 AND 100`; CHECK `new_score = previous_score + delta`; CHECK `btrim(policy_version) <> ''`; CHECK `source_version IS NULL OR source_version >= 0`.
- CHECK `change_type NOT IN ('ADMIN_ADJUSTMENT','DISPUTE_ADJUSTMENT','POLICY_RECALCULATED') OR (reason IS NOT NULL AND btrim(reason) <> '' AND changed_by IS NOT NULL)`.
- CHECK `change_type IN ('ADMIN_ADJUSTMENT','POLICY_RECALCULATED') OR source_id IS NOT NULL`. Rating/dispute sources require a revision: CHECK `change_type NOT IN ('USER_RATED','DISPUTE_ADJUSTMENT') OR source_version IS NOT NULL`.
- Do not CHECK score-to-trust mapping against a fixed set of SQL thresholds; configured policy determines it. Do not CHECK that score must change or delta must be nonzero: counters, dispute metadata or trust can change while score remains the same.

**Selected indexes/idempotency:**

- `CREATE INDEX idx_reputation_history_user_time ON reputation_history (user_id, created_at DESC);` supports one user's history.
- `CREATE UNIQUE INDEX uq_reputation_history_operation ON reputation_history (operation_id, user_id, change_type, source_version) NULLS NOT DISTINCT;`. operation_id identifies the source operation and is not UNIQUE on its own: one OrderCompleted can affect two users. source_version distinguishes valid corrections; NULLS NOT DISTINCT prevents another row from replaying an operation without a revision.
- `CREATE UNIQUE INDEX uq_reputation_history_event_user_type ON reputation_history (event_id, user_id, change_type, source_version) NULLS NOT DISTINCT WHERE event_id IS NOT NULL;`. One event can affect multiple users/history types; do not make event_id UNIQUE across the table.
- `CREATE UNIQUE INDEX uq_reputation_history_source_user_revision ON reputation_history (source_id, user_id, change_type, source_version) NULLS NOT DISTINCT WHERE source_id IS NOT NULL AND change_type <> 'POLICY_RECALCULATED';`. Prevents two envelopes with different eventId values from applying the same business effect, including after a policy change. Do not include policy_version in the business key, because a policy change does not create another transaction/rating. A valid rating correction uses a new source_version. POLICY_RECALCULATED uses the stable operation_id of its batch/job/request and the composite operation uniqueness above, without using the original operation to add counters again. Do not blindly make source_id alone unique and reject valid dispute corrections.

**Identity and version:** the producer/request workflow must assign operation_id once and preserve it across retries, or resolve it stably from the business operation under the contract. Do not call random UUID generation on every consumer retry. For a source event, operation_id may be that operation's eventId; the source/revision/policy uniqueness above also protects against two envelopes with different IDs for the same source. Admin/dispute/policy operations have a stable request UUID and appropriate permissions; increment a rating correction's source_version under the rating/source lock rather than using wall-clock time as a revision. Event schema version (`eventVersion`) is not a business revision. policy_version is versioned configuration; do not use DEFAULT 'current', which loses evidence of the policy executed. A recomputation job preserves one operation_id/batch identity across every retry and stores it before processing; an intentional rerun is a new audited operation. Recompute score/trust only from existing inputs, without incrementing outcomes/rating_count/penalty_points again. Business-source uniqueness does not depend on policy_version.

**Lifecycle / enforcement:** lock the aggregate, snapshot the previous state, calculate the new state from inputs and policy, then write history/outbox/processed marker in the same transaction. History is INSERT-only; do not update/delete rows to rewrite reasons or original ratings. A duplicate uniqueness conflict must make the entire effect a no-op or roll it back; do not update score in another transaction and ignore a history error. Preserve the actual post-clamp delta; change_details stores enough before/after inputs to explain recomputation/disputes and rebuild aggregates using a versioned schema. The service enforces permissions and JSON-content validation; a local CHECK cannot verify that changed_by is actually an Admin.

**Recomputation and upgrade:** #213 uses original ratings, effective dispute/penalty/admin decisions in history, and outcome projections to rebuild rating_count/rating_score_sum/penalty_points/counters before running the specified policy version. Add `AUCTION_OUTCOME` from an event with defined user/role/result to meet BR-040; do not assume that AuctionFailed without a winner means every bidder failed. Recomputation does not add counters or penalties already processed. New aggregates defaulting to 0 serve only new users/migration initialization; this must not be presented as evidence that historical users had no ratings/penalties. Backfill from evidenced data before enabling enforcement. Preserve source IDs and legacy scores if inputs cannot be established; do not fabricate history/events or rewrite the past under a guessed policy. Versioned policy/configuration and change_details are implementation responsibilities of #213, not part of a numerical formula specified by SRS.

### rating_db.outbox

**Owner / source:** rating_db; #242 and the V1 pattern. A new table under #242, using the same family of structures as existing technical tables; no target migration exists in the baseline.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `id` | `uuid` | No | `uuidv7()` | Outbox record ID; proposed to remain stable and identical to eventId on publication/retry | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type name | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Aggregate ID as text, either a UUID or a business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraint` |
| `occurred_at` | `timestamptz` | No | `now()` | Event occurrence time | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not marked as published | `No additional constraint` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is already specified in the field definition; no FK to business tables or other databases. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business rules:** the business change and INSERT into outbox occur in the same local transaction. The proposed row id equals eventId in the envelope; relay retries preserve eventId, aggregate key and payload without generating a new ID. A relay can resend if it crashes after Kafka acknowledgement but before published_at is recorded: consumers must deduplicate; an outbox does not itself provide exactly-once delivery. Store the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends that envelope unchanged with the same eventId on every retry, without double wrapping. `aggregate_id` is varchar(100); do not silently change it to UUID or an FK, because some aggregates use text keys. Retain published outbox entries for at least 90 days; do not purge unpublished entries. Business ledger/uniqueness and the processed_events policy in the retention section protect the corresponding replay behavior.

### rating_db.processed_events

**Owner / source:** rating_db; #242 and the V1 pattern. A new table under #242, using the same family of structures as existing technical tables; no target migration exists in the baseline.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
|---|---|---|---|---|---|
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers for the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Local processing completion time | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not reduce it to event_id alone. No FK to another service's outbox/event_logs.

**Business rules:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. The UNIQUE marker prevents the same event from being processed twice by one handler, but does not prevent different eventId values for the same business operation, so the domain still needs appropriate order/provider-reference uniqueness. If the envelope lacks data required for application, do not mark it successfully processed and discard it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or the ledger lifetime. Replay beyond the window must use a restore/backfill workflow and business uniqueness; do not imply permanent deduplication after purging. Consumer names are stable per handler, not per instance.

<a id="notification-db"></a>

## notification-service / notification_db

**Owner:** `notification-service`, port 8086 under ADR-009. **Source:** `notification-service/src/main/resources/db/migration/V1__init.sql`. There are exactly **6 business tables** below; technical tables are presented separately. `Existing V1` denotes the local migration structure; `Proposed` denotes a proposal without a migration. Nullability/defaults match SQL except for marked additions. `—` means no DEFAULT is declared. PKs are always NOT NULL. FKs without explicit actions use the default `ON UPDATE NO ACTION ON DELETE NO ACTION`, without an implicit CASCADE.

**Audit policy:** according to code #249 and the user's choices: created_by/updated_by are UUID NOT NULL, with the all-zero system UUID as the default; JPA/native writers, backfill and the immutable creator follow the shared conventions.

| Proposed target enum #249 | Complete value set | Source/status |
| --- | --- | --- |
| `notification_channel` | `EMAIL`, `PUSH`, `SMS` | The literal set has a V1 CHECK; the PostgreSQL enum name exists in implementation #249. In-app notifications are notifications records; do not add IN_APP to the source channel domain. |
| `notification_delivery_status` | `PENDING`, `SENT`, `FAILED`, `SKIPPED` | The literal set/PENDING default has a V1 CHECK; the PostgreSQL enum name exists in implementation #249. |
| `notification_attempt_status` | `STARTED`, `SUCCEEDED`, `FAILED` | The literal set has a V1 CHECK; the source has no default. Retain SUCCEEDED rather than changing it to SENT; the PostgreSQL enum name follows implementation #249. |

Converting varchar+CHECK to a PostgreSQL enum requires a new migration, data checks and default/cast handling; type names follow implementation branch #249, which is not yet merged into the develop baseline. `event_type`, `notifications.type` and `notification_type` are open-ended business names; do not force them into delivery/attempt enums or create an event enum for every future type.

### notification_db.event_logs

**Owner:** `notification-service` / `notification_db`. **Purpose:** store domain events received from Kafka and serve as the read source for the event-log/audit API. **Source:** V1 starting at line 1. **Target changes:** proposed UNIQUE(event_id) #192; add audit #249; do not add an audit_logs table.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Internal notification_db record identifier. | `PRIMARY KEY`; Existing V1 |
| `event_id` | `uuid` | No | — | Original event UUID in the envelope (not the internal log row ID). | Existing V1 |
| `event_type` | `varchar(80)` | No | — | Original event type name; projected as action in the audit API. | Existing V1 |
| `message` | `varchar(500)` | Yes | — | Summary message for lookup; does not replace the original payload. | Existing V1 |
| `operator_id` | `uuid` | Yes | — | UUID of the person/principal performing the action in the event-producing system; projected as actorId in the audit API. It is not the actor of the consumer writing the log. | Existing V1 |
| `operator_name` | `varchar(150)` | Yes | — | Actor display name at the time of event production, if supplied; nullable; do not replace historical content with a current name. | Existing V1 |
| `aggregate_type` | `varchar(50)` | Yes | — | Type of business object affected by the event; may be projected as resourceType for audit. | Existing V1 |
| `aggregate_id` | `varchar(100)` | Yes | — | Object identifier as a string, as in the envelope; do not force UUID because the source column is varchar(100). May be projected as resourceId for audit. | Existing V1 |
| `producer` | `varchar(50)` | Yes | — | Event-producing service name if supplied; not the notification consumer name. | Existing V1 |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the processing flow; retain source data; may be null in historical logs. | Existing V1 |
| `payload` | `jsonb` | Yes | — | Event payload retaining its original JSON values, without modification for template/audit rendering. jsonb does not preserve key order/whitespace or HTTP/Kafka bytes. | Existing V1 |
| `occurred_at` | `timestamptz` | No | — | Time when the business event occurred at the producer; distinct from consumer receipt time. | Existing V1 |
| `received_at` | `timestamptz` | No | `now()` | Time when notification-service received the event envelope. | Existing V1; consumer receipt time, distinct from producer occurred_at. |
| `deleted_at` | `timestamptz` | Yes | — | Soft-deletion time if permitted by the retention rules; the source has no automatic deletion job/trigger. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Source constraints/indexes and target additions:**

- Existing V1: PK `(id)`, `DEFAULT uuidv7()`; no explicit constraint name is declared.
- Existing V1: `CREATE INDEX idx_event_logs_aggregate ON event_logs (aggregate_type, aggregate_id);`
- Existing V1: `CREATE INDEX idx_event_logs_time ON event_logs (occurred_at DESC);`
- Existing V1: `CREATE INDEX idx_event_logs_trace ON event_logs (trace_id);`
- Existing V1: `CREATE INDEX idx_event_logs_type ON event_logs (event_type, occurred_at DESC);`
- Proposed #192: `CONSTRAINT uq_event_logs_event_id UNIQUE (event_id)` (proposed name). Uniqueness covers the entire table, including rows with deleted_at set, to store each eventId once; before migration, inspect duplicate eventIds, preserve payload/evidence and resolve conflicts with verification. Do not use a partial unique constraint restricted to deleted_at IS NULL when the requirement is to store an event exactly once.

**Relationships/FKs:**

- No physical FK is declared. User/operator/actor UUIDs are logical references, without FKs to user_db/Keycloak; eventId is not a cross-database FK either.

**Lifecycle:** receive the event and store eventId/payload/metadata faithfully to the source; Kafka redelivery of the same eventId does not create a second row. The log preserves history: do not change payload because a template changes or audit reads occur. Soft deletion/retention runs only under the approved policy; eventId uniqueness still applies to soft-deleted rows.

**Enforcement:** the consumer handles idempotency and transactions; unique eventId is enforced in the DB. operator_id is the business actor from the producer, whereas created_by/updated_by identify the consumer/service writing the DB and may be entirely different. Do not assign the JWT of the current audit viewer as the historical operator_id. A timestamp DEFAULT is not an update trigger; new audit UUIDs are supplied by the data-writing layer and retain the Proposed label until the migration is implemented.

### notification_db.notification_templates

**Owner:** `notification-service` / `notification_db`. **Purpose:** EMAIL/PUSH/SMS templates by business code and language. **Source:** V1 starting at line 22. **Target changes:** add audit #249; propose a channel enum with the same V1 CHECK value set.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Internal notification_db record identifier. | `PRIMARY KEY`; Existing V1 |
| `code` | `varchar(80)` | No | — | Template identifier for the business operation/event. | Existing V1 |
| `channel` | `notification_channel` (Proposed; source `varchar(20)`) | No | — | EMAIL/PUSH/SMS channel; retain the V1 CHECK value set. | `CHECK (channel IN ('EMAIL', 'PUSH', 'SMS'))`; Existing V1; proposed enum conversion #249, preserving the source literals |
| `locale` | `varchar(10)` | No | `'vi'` | Template language code, default vi; not an enum hardcoded to one language. | Existing V1 |
| `subject` | `varchar(255)` | Yes | — | Delivery-channel subject; may be null for a channel that does not use a subject. | Existing V1 |
| `body` | `text` | No | — | Template body before rendering; does not replace the stored event payload. | Existing V1 |
| `version` | `integer` | No | `1` | Template version; currently has no CHECK > 0 and is not part of the source UNIQUE. | Existing V1 |
| `is_active` | `boolean` | No | `true` | Flag permitting selection of the template for delivery. | Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Modification time; DEFAULT now() applies only on insert, and the service must update it when modifying the row. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Source constraints/indexes and target additions:**

- Existing V1: PK `(id)`, `DEFAULT uuidv7()`; no explicit constraint name is declared.
- Existing V1: column `channel` has `CHECK (channel IN ('EMAIL', 'PUSH', 'SMS'))`; retain the literals when converting to the proposed enum.
- Existing V1: `UNIQUE (code, channel, locale)`; SQL does not declare an explicit constraint name, so PostgreSQL generates it. UNIQUE/PK create implicit indexes.
- V1 declares no separate secondary indexes beyond the implicit PK/UNIQUE indexes; PostgreSQL does not automatically index every FK.

**Relationships/FKs:**

- No physical FK is declared. User/operator/actor UUIDs are logical references, without FKs to user_db/Keycloak; eventId is not a cross-database FK either.

**Lifecycle:** create a template, update body/subject or disable is_active. UNIQUE(code,channel,locale) currently permits one current record; version is merely a version number on that record and does not allow multiple historical rows with the same key tuple.

**Enforcement:** the DB enforces uniqueness/channel/nullability; the service enforces administrative permissions, validates templates, escapes content during rendering and determines the locale fallback. Do not modify event_logs payload during rendering. Template history requires a separate decision; do not add version to the UNIQUE key on your own. A timestamp DEFAULT is not an update trigger; new audit UUIDs are supplied by the data-writing layer and retain the Proposed label until the migration is implemented.

### notification_db.notifications

**Owner:** `notification-service` / `notification_db`. **Purpose:** rendered in-app notification history for each recipient. **Source:** V1 starting at line 36. **Target changes:** add audit #249; retain event_id as an FK to event_logs.id, rather than changing it to the envelope eventId.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Internal notification_db record identifier. | `PRIMARY KEY`; Existing V1 |
| `event_id` | `uuid` | Yes | — | Nullable `event_logs.id` row ID in notification_db; **not** the envelope eventId (`event_logs.event_id`). | `REFERENCES event_logs (id)`; Existing V1 |
| `user_id` | `uuid` | No | — | Recipient/preference-owner UUID from the authenticated identity or event; a logical reference, without an FK to user/Keycloak. | Existing V1 |
| `type` | `varchar(60)` | No | — | Business notification type, varchar(60); not a delivery/attempt status, and the source has no CHECK listing allowed types. | Existing V1 |
| `title` | `varchar(255)` | No | — | Rendered title for in-app history. | Existing V1 |
| `content` | `text` | No | — | Rendered content for in-app history. | Existing V1 |
| `read_at` | `timestamptz` | Yes | — | Time when the recipient first read the notification; null means unread. | Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp with time zone. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Source constraints/indexes and target additions:**

- Existing V1: PK `(id)`, `DEFAULT uuidv7()`; no explicit constraint name is declared.
- Existing V1: `CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);`
- Existing V1: `CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;`

**Relationships/FKs:**

- Existing V1 local FK: `notifications.event_id → event_logs.id`; `ON UPDATE NO ACTION ON DELETE NO ACTION`; nullable. DEFERRABLE is not declared, so it is NOT DEFERRABLE.
- Two distinct UUIDs: `event_logs.id` is the internal PK; `event_logs.event_id` is the envelope ID used for deduplication. The consumer must obtain/resolve the log PK before writing notifications.event_id; do not put the envelope eventId into the FK if those IDs differ.

**Lifecycle:** the consumer creates notifications from the event/template, with read_at null. The first read assigns read_at; repeated reads preserve the first timestamp. read-all updates only unread rows belonging to the caller. V1 has no updated_at/status/deleted_at on this table.

**Enforcement:** the service obtains the owner from JWT sub for queries/mark-read; the DB FK only links the local log and does not replace ownership checks. The DB does not currently have UNIQUE(event_id,user_id,type), so do not claim that this constraint prevents duplicate notifications; use consumer idempotency. A timestamp DEFAULT is not an update trigger; new audit UUIDs are supplied by the data-writing layer and retain the Proposed label until the migration is implemented.

### notification_db.notification_deliveries

**Owner:** `notification-service` / `notification_db`. **Purpose:** one overall delivery status for a notification and channel; attempts have a separate child table. **Source:** V1 starting at line 49. **Target changes:** proposed UNIQUE(notification_id,channel), nullable next_attempt_at timestamptz and retry-scheduling index #191; add audit/enums #249.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Internal notification_db record identifier. | `PRIMARY KEY`; Existing V1 |
| `notification_id` | `uuid` | No | — | Parent in-app/historical notification for the channel delivery. | `REFERENCES notifications (id) ON DELETE CASCADE`; Existing V1 |
| `channel` | `notification_channel` (Proposed; source `varchar(20)`) | No | — | EMAIL/PUSH/SMS channel; retain the V1 CHECK value set. | `CHECK (channel IN ('EMAIL', 'PUSH', 'SMS'))`; Existing V1; proposed enum conversion #249, preserving the source literals |
| `status` | `notification_delivery_status` (Proposed; source `varchar(20)`) | No | `'PENDING'` | Delivery status using the corresponding proposed enum; see the complete literal set at the beginning of this section. | `CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))`; Existing V1; proposed enum conversion #249, preserving the source literals |
| `attempt_count` | `integer` | No | `0` | Number of delivery attempts actually started; not incremented for deduplicated events or SKIPPED deliveries. | `CHECK (attempt_count >= 0)`; Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Modification time; DEFAULT now() applies only on insert, and the service must update it when modifying the row. | Existing V1 |
| `next_attempt_at` | `timestamptz` | Yes | — | Proposed #191: earliest next-attempt time after backoff; null when no further attempt is scheduled or no retry schedule is yet needed. | Proposed #191: durable backoff scheduling; proposed column name/nullability |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Source constraints/indexes and target additions:**

- Existing V1: PK `(id)`, `DEFAULT uuidv7()`; no explicit constraint name is declared.
- Existing V1: column `channel` has `CHECK (channel IN ('EMAIL', 'PUSH', 'SMS'))`; retain the literals when converting to the proposed enum.
- Existing V1: column `status` has `CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))`; retain the literals when converting to the proposed enum.
- Existing V1: column `attempt_count` has `CHECK (attempt_count >= 0)`; retain the literals when converting to the proposed enum.
- Existing V1: `CREATE INDEX idx_deliveries_notification ON notification_deliveries (notification_id);`
- Existing V1: `CREATE INDEX idx_deliveries_pending ON notification_deliveries (status, created_at);`
- Proposed #191: `CONSTRAINT uq_notification_deliveries_channel UNIQUE (notification_id, channel)` (proposed name), one delivery per channel; retries are stored in the attempts table. Inspect/merge duplicates using a planned process before adding the constraint; do not delete attempt history.
- Proposed #191: `CREATE INDEX idx_deliveries_retry_due ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING' AND next_attempt_at IS NOT NULL;` (proposed name/predicate). The source idx_deliveries_pending index remains (status, created_at); do not assume it already supports efficient due-time scanning.

**Relationships/FKs:**

- Existing V1 local FK: `notification_deliveries.notification_id → notifications.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL. DEFERRABLE is not declared, so it is NOT DEFERRABLE.

**Lifecycle:** PENDING awaits delivery or a scheduled retry; success becomes SENT; a permanent error/exhausted attempts becomes FAILED; a preference/filter decision not to send becomes SKIPPED. Each attempt creates a new attempt row, rather than a new delivery row to represent a retry. A PENDING delivery may have a STARTED attempt; do not add PROCESSING to the source enum.

**Enforcement:** the worker checks due time, claims atomically to prevent concurrent delivery, writes attempt_count/status/next_attempt_at consistently and does not hold a long DB transaction across a provider network call. The DB has a nonnegative attempt_count CHECK; notification/channel uniqueness and retry scheduling are new proposals absent from V1. This unique constraint alone cannot guarantee exactly-once provider delivery. A timestamp DEFAULT is not an update trigger; new audit UUIDs are supplied by the data-writing layer and retain the Proposed label until the migration is implemented.

### notification_db.notification_delivery_attempts

**Owner:** `notification-service` / `notification_db`. **Purpose:** a trace of each provider call to send a notification. **Source:** V1 starting at line 62. **Target changes:** add audit #249; propose an enum containing only STARTED/SUCCEEDED/FAILED; retain retryable and do not change literals to delivery statuses.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Internal notification_db record identifier. | `PRIMARY KEY`; Existing V1 |
| `delivery_id` | `uuid` | No | — | Channel delivery containing this attempt. | `REFERENCES notification_deliveries (id) ON DELETE CASCADE`; Existing V1 |
| `attempt_number` | `integer` | No | — | Attempt sequence number, starting at 1 and unique within each delivery. | `CHECK (attempt_number > 0)`; Existing V1 |
| `status` | `notification_attempt_status` (Proposed; source `varchar(20)`) | No | — | Delivery status using the corresponding proposed enum; see the complete literal set at the beginning of this section. | `CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))`; Existing V1; proposed enum conversion #249, preserving the source literals |
| `attempted_at` | `timestamptz` | No | `now()` | Attempt start time. | Existing V1 |
| `completed_at` | `timestamptz` | Yes | — | Attempt completion time; null while STARTED/no result is available. | Existing V1 |
| `provider_message_id` | `varchar(120)` | Yes | — | Provider ID returned for the attempt; nullable if not yet assigned or delivery fails before an ID is received. | Existing V1 |
| `error_code` | `varchar(60)` | Yes | — | Normalized error code for a failed attempt. | Existing V1 |
| `error_message` | `text` | Yes | — | Error details with sensitive data filtered out; do not modify the event payload to include delivery errors. | Existing V1 |
| `retryable` | `boolean` | No | `true` | Classification of whether the attempt's error is retryable; V1's true default does not mean every error is retryable. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Source constraints/indexes and target additions:**

- Existing V1: PK `(id)`, `DEFAULT uuidv7()`; no explicit constraint name is declared.
- Existing V1: column `attempt_number` has `CHECK (attempt_number > 0)`; retain the literals when converting to the proposed enum.
- Existing V1: column `status` has `CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))`; retain the literals when converting to the proposed enum.
- Existing V1: `UNIQUE (delivery_id, attempt_number)`; SQL does not declare an explicit constraint name, so PostgreSQL generates it. UNIQUE/PK create implicit indexes.
- V1 declares no separate secondary indexes beyond the implicit PK/UNIQUE indexes; PostgreSQL does not automatically index every FK.

**Relationships/FKs:**

- Existing V1 local FK: `notification_delivery_attempts.delivery_id → notification_deliveries.id`; `ON UPDATE NO ACTION ON DELETE CASCADE`; NOT NULL. DEFERRABLE is not declared, so it is NOT DEFERRABLE.

**Lifecycle:** create STARTED with a sequence number > 0; when the provider responds, transition to SUCCEEDED or FAILED and record completed_at and provider/error identifiers. A retry creates the next attempt_number and preserves the old attempt. FAILED with retryable=true may be scheduled again; FAILED with retryable=false or an exhausted retry budget ends the delivery as FAILED.

**Enforcement:** the DB enforces delivery/attempt_number uniqueness, the positive-number CHECK and source enum; the service classifies permanent/retryable errors, checks the budget and records backoff in delivery.next_attempt_at. Stale/crashed STARTED attempts require a timeout/recovery policy and must not be treated as automatically SUCCEEDED. SQL currently has no CHECK for completed_at >= attempted_at or error columns by status. A timestamp DEFAULT is not an update trigger; new audit UUIDs are supplied by the data-writing layer and retain the Proposed label until the migration is implemented.

### notification_db.notification_preferences

**Owner:** `notification-service` / `notification_db`. **Purpose:** user preferences for receiving each notification type through each channel. **Source:** V1 starting at line 77. **Target changes:** add audit #249; propose a channel enum matching the V1 CHECK.

| Field | Target type | Nullable | Default | Meaning | Constraints and source/status |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Internal notification_db record identifier. | `PRIMARY KEY`; Existing V1 |
| `user_id` | `uuid` | No | — | Recipient/preference-owner UUID from the authenticated identity or event; a logical reference, without an FK to user/Keycloak. | Existing V1 |
| `notification_type` | `varchar(60)` | No | — | Notification type to which the preference applies; shares the business domain of notifications.type; no dictionary table/FK currently exists. | Existing V1 |
| `channel` | `notification_channel` (Proposed; source `varchar(20)`) | No | — | EMAIL/PUSH/SMS channel; retain the V1 CHECK value set. | `CHECK (channel IN ('EMAIL', 'PUSH', 'SMS'))`; Existing V1; proposed enum conversion #249, preserving the source literals |
| `enabled` | `boolean` | No | `true` | Whether this notification type may be sent through this channel; defaults to true. | Existing V1 |
| `created_at` | `timestamptz` | No | `now()` | Creation timestamp with time zone. | Existing V1 |
| `updated_at` | `timestamptz` | No | `now()` | Modification time; DEFAULT now() applies only on insert, and the service must update it when modifying the row. | Existing V1 |
| `created_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Creating actor; immutable after INSERT | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |
| `updated_by` | `uuid` | No | `'00000000-0000-0000-0000-000000000000'::uuid` | Most recent writing/updating actor; append-only history is not rewritten | Per code #249/0bc9da1 and the user: NOT NULL/system default; authenticated writers assign the actor; no cross-database FK. |

**Source constraints/indexes and target additions:**

- Existing V1: PK `(id)`, `DEFAULT uuidv7()`; no explicit constraint name is declared.
- Existing V1: column `channel` has `CHECK (channel IN ('EMAIL', 'PUSH', 'SMS'))`; retain the literals when converting to the proposed enum.
- Existing V1: `UNIQUE (user_id, notification_type, channel)`; SQL does not declare an explicit constraint name, so PostgreSQL generates it. UNIQUE/PK create implicit indexes.
- V1 declares no separate secondary indexes beyond the implicit PK/UNIQUE indexes; PostgreSQL does not automatically index every FK.

**Relationships/FKs:**

- No physical FK is declared. User/operator/actor UUIDs are logical references, without FKs to user_db/Keycloak; eventId is not a cross-database FK either.

**Lifecycle:** upsert by user/notification_type/channel; enabled false blocks the corresponding delivery without deleting in-app notifications or deliveries/attempts history. The service writes updated_at.

**Enforcement:** the DB enforces UNIQUE(user_id,notification_type,channel)/channel; the service checks JWT ownership and determines the default when no row exists. The enabled=true default does not mean a row already exists for every user/channel. A timestamp DEFAULT is not an update trigger; new audit UUIDs are supplied by the data-writing layer and retain the Proposed label until the migration is implemented.

**Mandatory transactional notifications under BR-064:** enabled=false must not disable all order/payment notifications when TRANSACTIONAL_NOTIFICATION_MANDATORY=true. Default preferences are created for a new user under BR-065; event redelivery does not create duplicate rows because of uniqueness. SMS is off by default, while email/push follow runtime features; do not change the channel domain merely because a channel is disabled.

### Event-log, audit, retry and health flows after the split

**#192 — event-log and audit:** notification subscribes to eight domain topics: `nexus.user.events`, `nexus.catalog.events`, `nexus.commerce.events`, `nexus.auction.events`, `nexus.inventory.events`, `nexus.payment.events`, `nexus.shipment.events`, `nexus.rating.events` under ADR-009/#244. Do not create additional notification/system topics. This is the target scope; the current smoke/demo listener does not prove that all eight topics are subscribed to or that every business event is stored.

The audit API is a projection/VIEW reading `event_logs`; **do not add an `audit_logs` table**. Proposed mappings from existing fields: `operator_id → actorId`, `operator_name → actorName`, `event_type → action`, `aggregate_type → resourceType`, `aggregate_id → resourceId`, `occurred_at → occurredAt`, `trace_id → traceId`; exact API naming must match the #192 contract. A physical SQL VIEW, if needed for optimization/normalization, is a separate implementation detail, not a seventh business table. The service applies filtering/paging/view permissions; do not accept a viewer-supplied actorId to change historical data.

Payload and producer metadata retain their original logical content. jsonb does not guarantee byte-for-byte preservation, key order or whitespace; signature/raw-evidence requirements need a separate byte-storage contract, rather than a claim that jsonb meets them. A duplicate eventId with the same payload is redelivery; the same eventId with different content must raise a conflict/investigation, not overwrite the original payload to conceal the inconsistency. Depending on retention policy, hard-purging logs means table uniqueness no longer prevents replay of purged events; apply the selected 90-day retention and replay/backfill workflow, without claiming indefinite duplicate prevention after purging.

**#191 — retry/backoff:** classify each attempt's errors using `retryable`, the error code and provider policy. Transient error: attempt FAILED/retryable=true, and if budget remains, delivery PENDING with `next_attempt_at`; permanent error or exhausted budget: delivery FAILED and next_attempt_at null. Success: attempt SUCCEEDED, delivery SENT, retry schedule cleared. A preference decision to skip produces delivery SKIPPED, not a fabricated successful attempt. `attempt_count` must be updated consistently with attempt_number/the row in a transaction; UNIQUE(delivery_id,attempt_number) does not itself limit total retries.

Selected notification backoff: NOTIFICATION_RETRY_LIMIT defaults to 3, interpreted as at most 3 total sends (attempt_number 1..3), consistent with AC #191 and BR-066; retry transient errors and stop immediately on permanent errors under BR-067. The implementation default delay is min(30s,1s×2^(n−1)) plus 0..20% jitter, all externally configured; these delay values are implementation choices because the SRS does not specify them. next_attempt_at is durably stored in the DB; the worker claims through an atomic lock/lease before the network call, detects stuck STARTED attempts using a configured timeout and reconciles the provider message ID before retrying. Do not hold a long DB transaction across SMTP/API calls or claim exactly-once provider side effects.

**#193 — selected health design under SRS §5.1 / BR-228:** store shared state in durable Redis (AOF/backup); key `{env}:health:{target}` contains status,lastCheckedAt,lastFailureAt,lastRecoveredAt and version. Memory is only a temporary cache, not the sole source. Do not expire latest-state for a still-configured target; failure/recovery history is written to immutable event_logs retained for at least 90 days. Do not add a PostgreSQL health table; Redis failure produces a degraded response and separate monitoring, without failing orders under BR-070.

**Notification choices:** UNIQUE(event_id) for logs; UNIQUE(notification_id,channel) for deliveries; UNIQUE(delivery_id,attempt_number) for attempts; next_attempt_at for retries; named enums under #249 and audit NOT NULL/system defaults. Retention/duplicate conflicts follow the shared policy; the writing audit actor differs from operator_id of the source action. Do not create new audit_logs or health tables.

### notification_db.outbox

**Owner / source:** notification_db; notification V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | No | `uuidv7()` | Outbox record ID; proposed to remain stable and identical to eventId during publishing/retries | `PRIMARY KEY DEFAULT uuidv7()` |
| `aggregate_type` | `varchar(50)` | No | — | Event aggregate type name | `NOT NULL` |
| `aggregate_id` | `varchar(100)` | No | — | Text aggregate ID, which may be a UUID or business key; no FK | `NOT NULL` |
| `event_type` | `varchar(80)` | No | — | Event name from the catalogue | `NOT NULL` |
| `event_version` | `integer` | No | `1` | Event schema version | `NOT NULL DEFAULT 1` |
| `topic` | `varchar(80)` | No | — | Kafka topic owned by the producer | `NOT NULL` |
| `payload` | `jsonb` | No | — | Event JSON published under the envelope contract | `NOT NULL` |
| `trace_id` | `varchar(64)` | Yes | — | Trace ID linking the request/event | `No additional constraints` |
| `occurred_at` | `timestamptz` | No | `now()` | Time when the event occurred | `NOT NULL DEFAULT now()` |
| `published_at` | `timestamptz` | Yes | — | Successful publication time; NULL means not yet marked as published | `No additional constraints` |

**Keys/constraints/indexes:**
- `CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;`
- `CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);`
- PK `(id)` is already specified on the field; there is no FK to a business table or another database. Do not add a positive event_version CHECK absent from the baseline; the producer contract must supply a valid version.

**Business behavior:** the business change and outbox INSERT share one local transaction. The row ID is proposed to equal eventId in the envelope; relay retries retain the same eventId, aggregate key and payload rather than generating a new ID. A relay may resend if it crashes after Kafka acknowledgement but before published_at: consumers must deduplicate; outbox does not itself provide exactly-once delivery. The selected approach stores the complete envelope in payload JSONB as in the demo code; outbox.id equals eventId, and the relay sends the envelope unchanged and preserves eventId on every retry without double-wrapping. `aggregate_id` is varchar(100); do not change it to UUID or an FK because some aggregates use text keys. Published outbox records are retained for at least 90 days; unpublished records are not purged. The business ledger/uniqueness rules and processed_events policy in the retention section protect their respective replay scenarios.

### notification_db.processed_events

**Owner / source:** notification_db; notification V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `event_id` | `uuid` | No | — | eventId of the successfully processed envelope | `NOT NULL` |
| `consumer` | `varchar(80)` | No | — | Stable logical consumer name; distinguishes multiple handlers of the same event | `NOT NULL` |
| `handled_at` | `timestamptz` | No | `now()` | Time when local processing completed | `NOT NULL DEFAULT now()` |

**Keys/constraints/indexes:**
- `PRIMARY KEY (event_id, consumer)`.
- `CREATE INDEX idx_processed_events_cleanup ON processed_events (handled_at);`
- Composite PK `(event_id, consumer)`; do not change it to event_id alone. No FK to another service's outbox/event_logs.

**Business behavior:** write the processed marker and business/outbox changes in the same transaction; roll back both if the handler fails. Marker uniqueness prevents the same event from being processed twice by one handler, but does not block different eventIds for the same business operation, so the domain still needs the corresponding unique order/provider reference. If the envelope lacks the data needed to apply it, do not mark it successfully processed and discard it. Retain markers for at least 90 days for ordinary consumers; critical order/payment/refund consumers retain them for 5 years or for the ledger's lifetime. Replay outside that window must use a restore/backfill workflow and business uniqueness; do not imply indefinite deduplication after purging. Consumer names are stable per handler, not per instance.

### notification_db.shedlock

**Owner / source:** notification_db; notification V1. Retain the V1 definition; do not move it to another DB.

| Field | PostgreSQL type | NULL? | Default | Meaning | Source constraints |
| --- | --- | --- | --- | --- | --- |
| `name` | `varchar(64)` | No | — | Job lock name, unique within the database | `PRIMARY KEY` |
| `lock_until` | `timestamptz` | No | — | Lease expiration time | `NOT NULL` |
| `locked_at` | `timestamptz` | No | — | Lock acquisition time | `NOT NULL` |
| `locked_by` | `varchar(255)` | No | — | Identifier of the instance holding the lease | `NOT NULL` |

**Keys/constraints/indexes:**
- PK `(name)`; no secondary index or FK; no defaults for the timestamps/owner.

**Business behavior:** the scheduler writes and renews the lease through a local lock provider. This table does not replace business idempotency or foreign-key enforcement. The lockAtMostFor/lockAtLeastFor durations are job configuration, not CHECK literals. Retain the existing definition; a new service adds a scheduler lock table only when its implementation issue confirms that it is needed.

<a id="migration-map"></a>

## Source-to-Target Mapping and Migration Handoff

Every baseline table below has an explicit disposition. Removed auth tables are not application tables to recreate manually inside auth_db.

| Baseline source table | Target | Action / source |
|---|---|---|
| `user_db.users` | `user_db.users` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `user_db.roles` | No longer a project-owned table | Remove from user_db; Keycloak owns the corresponding functionality (#238). |
| `user_db.permissions` | No longer a project-owned table | Remove from user_db; Keycloak owns the corresponding functionality (#238). |
| `user_db.user_roles` | No longer a project-owned table | Remove from user_db; Keycloak owns the corresponding functionality (#238). |
| `user_db.role_permissions` | No longer a project-owned table | Remove from user_db; Keycloak owns the corresponding functionality (#238). |
| `user_db.refresh_tokens` | No longer a project-owned table | Remove from user_db; Keycloak owns the corresponding functionality (#238). |
| `user_db.password_reset_tokens` | No longer a project-owned table | Remove from user_db; Keycloak owns the corresponding functionality (#238). |
| `user_db.user_reputations` | `rating_db.user_reputations` | Transfer ownership under #242; remove the FK to users. |
| `user_db.ratings` | `rating_db.ratings` | Transfer ownership under #242; remove the FK to users. |
| `user_db.outbox` | `user_db.outbox` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `user_db.processed_events` | `user_db.processed_events` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `user_db.shedlock` | `user_db.shedlock` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.categories` | `catalog_db.categories` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.products` | `catalog_db.products` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.skus` | `catalog_db.skus` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.product_images` | `catalog_db.product_images` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.product_stats` | `catalog_db.product_stats` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.outbox` | `catalog_db.outbox` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `catalog_db.processed_events` | `catalog_db.processed_events` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.carts` | `commerce_db.carts` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.cart_items` | `commerce_db.cart_items` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.orders` | `commerce_db.orders` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.order_items` | `commerce_db.order_items` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.order_status_history` | `commerce_db.order_status_history` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.payment_transactions` | `payment_db.payment_transactions` | Move under #240; replace direct order ownership with a session FK and funded order instructions for the selected shared-QR extension. Preserve each real provider transaction once and retain enum/deduplication evidence. |
| `commerce_db.promo_mega_sale_events` | `commerce_db.promo_mega_sale_events` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_vouchers` | `commerce_db.promo_vouchers` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_event_sellers` | `commerce_db.promo_event_sellers` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_customer_vouchers` | `commerce_db.promo_customer_vouchers` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_voucher_products` | `commerce_db.promo_voucher_products` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_voucher_sellers` | `commerce_db.promo_voucher_sellers` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_voucher_usages` | `commerce_db.promo_voucher_usages` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.promo_voucher_usage_allocations` | `commerce_db.promo_voucher_usage_allocations` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.outbox` | `commerce_db.outbox` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.processed_events` | `commerce_db.processed_events` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `commerce_db.shedlock` | `commerce_db.shedlock` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `auction_db.auctions` | `auction_db.auctions` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `auction_db.bids` | `auction_db.bids` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `auction_db.auction_watchers` | `auction_db.auction_watchers` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `auction_db.outbox` | `auction_db.outbox` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `auction_db.processed_events` | `auction_db.processed_events` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `auction_db.shedlock` | `auction_db.shedlock` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `fulfillment_db.warehouses` | `inventory_db.warehouses` | Rename database/service under #243; preserve IDs and local FKs. |
| `fulfillment_db.inventory_balances` | `inventory_db.inventory_balances` | Rename database/service under #243; preserve IDs and local FKs. |
| `fulfillment_db.inventory_reservations` | `inventory_db.inventory_reservations` | Rename database/service under #243; preserve IDs and local FKs. |
| `fulfillment_db.inventory_movements` | `inventory_db.inventory_movements` | Rename database/service under #243; preserve IDs and local FKs. |
| `fulfillment_db.shipments` | `shipment_db.shipments` | Move under #241; remove the warehouse FK crossing the service boundary. |
| `fulfillment_db.shipment_history` | `shipment_db.shipment_history` | Move under #241; retain the local shipment_id → shipments.id FK. This table has no warehouse FK. |
| `fulfillment_db.outbox` | `inventory_db.outbox` | Rename database/service under #243; preserve IDs and local FKs. |
| `fulfillment_db.processed_events` | `inventory_db.processed_events` | Rename database/service under #243; preserve IDs and local FKs. |
| `fulfillment_db.shedlock` | `inventory_db.shedlock` | Rename database/service under #243; preserve IDs and local FKs. |
| `notification_db.event_logs` | `notification_db.event_logs` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.notification_templates` | `notification_db.notification_templates` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.notifications` | `notification_db.notifications` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.notification_deliveries` | `notification_db.notification_deliveries` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.notification_delivery_attempts` | `notification_db.notification_delivery_attempts` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.notification_preferences` | `notification_db.notification_preferences` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.outbox` | `notification_db.outbox` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.processed_events` | `notification_db.processed_events` | Retain ownership; apply the documented field/enum/audit changes where applicable. |
| `notification_db.shedlock` | `notification_db.shedlock` | Retain ownership; apply the documented field/enum/audit changes where applicable. |

New #257 additions relative to the review baseline: `user_db.seller`, `payment_db.seller_bank`, `payment_db.payouts` and `payment_db.payout_history`.

New baseline tables relative to the six V1 schemas: `commerce_db.order_groups`, `payment_db.payment_instructions`, `payment_db.payment_sessions`, `payment_db.refunds`, `rating_db.rating_eligibility` and `rating_db.reputation_history`. R1 stores each order's share directly in its payment instruction. These target additions are distinguished from existing source migrations and the original issue wording. The refunds/reputation_history names remain design proposals satisfying #247/#213. Each payment/shipment/rating database has its own outbox and processed_events under #240/#241/#242; none shares the source service's technical tables.

**Important column changes:** remove users.password_hash/failed_login_count/locked_until under #238; move the canonical QR memo from orders to payment_sessions.memo_code while retaining orders.payment_due_at. Add orders.order_group_id and the payment instruction's session_id/funded_transaction_id. Keep refunds.order_id UNIQUE. Replace payment_transactions.order_id with session_id: one NORMAL receipt funds several per-order instructions. Add commerce payment_note under the #157 design. Each table specifies its audit, enum and snapshot definitions.

**Verified migration for existing orders/payments:** assign a singleton order group to each historical order unless trustworthy source evidence proves an existing group. Retain the original order ID, unique order number and historical idempotency key. Create the matching singleton session and per-order instruction from verified buyer, currency, memo, amount and deadline evidence; preserve each actual transaction ID, provider reference and amount. Set instruction.funded_transaction_id only for a verified NORMAL/SUCCESS transaction belonging to that session. Existing refunds remain linked by order_id; reconcile multiple historical refund rows against actual transfers before adding UNIQUE(order_id), without deleting financial evidence merely to satisfy the constraint. Missing deadlines or contradictory payment evidence go to migration exceptions; do not fabricate SUCCESS receipts or timestamps. Keep historical memo mappings for late callbacks and never reuse a memo. Add and validate the new session/group FKs and NOT NULL constraints only after verified backfill. Applied V1/V2 migrations remain unchanged; use new migrations and a controlled application cutover.

<a id="review-decisions"></a>

## Decisions Applied on 24 October 2026

| Topic | Decision and source |
|---|---|
| Audit | created_by/updated_by are NOT NULL with the all-zero system UUID default, following reviewed #249 code and the user's choice; also applies to product_stats/history. |
| UUID | Preserve each existing writer's generator; do not force application v4/JPA UUIDs to v7. After the split, user ID equals Keycloak ID. |
| Profile | Keep last_login_at and LOCKED because they exist in V1; Keycloak retains brute-force lockout ownership. |
| Contacts | Permit email/phone reuse after soft deletion through partial active-record uniqueness and release of the old Keycloak username/email; new users receive new UUIDs. |
| Categories | Root level is 1; normalize the existing tree before CHECK level>=1. |
| Search | Do not add search_document/GIN/triggers/unaccent to this schema, per user decision. |
| Promotion enums/rules | PENDING REVIEWER: use the provisional #249 branch definitions for comparison without marking them approved. |
| Refund | R1 keeps refunds.order_id UNIQUE: one refund record per paid child order, bounded by that order's funded payment instruction. Canceling one child never refunds the full shared QR or a sibling order. Item returns remain a later contract. |
| Order group/payment | Checkout creates one order group containing seller-specific orders through orders.order_group_id. One payment session issues the shared QR; one physical bank receipt funds all per-order instructions. Before payment, cancel the entire group or let its session expire. After payment, each eligible child order has its own shipment/cancellation and at most one refund. |
| Rating | Bidirectional buyer/seller ratings, one per party/transaction; canonical completed orders also cover auctions under #242. Dispute corrections have history/version; scoring uses a versioned configuration policy because the SRS gives no numeric formula. |
| Notification/health | Durable delivery/attempt state; default maximum of 3 total sends; shared persistent Redis health state and immutable event/audit logs. |

### Selected Multi-shop Order Group and Shared Payment

Checkout creates an order group with one child order per seller/shop. The stored aggregate is `order_groups`; one payment session issues the group's one QR, and each payment instruction stores its child order's amount. One physical bank receipt is recorded once in payment_transactions. Its amount is not copied as the paid amount of every child.

BR-109 originally binds a payment request to one order. The selected R1 target extends that contract to one order-group session with per-order instructions. Update #113/#118/#188 for group creation, #240/#121 for session initiation, #155-#157/#207 for receipt processing/listing, #186 for event payloads, and #247 for the one-refund-per-child rule. The target document does not imply that the current APIs or publishers already implement this extension.

**Example:** order group X contains order A=200000 and order B=300000. Its session QR requests 500000. One accepted NORMAL/SUCCESS receipt funds instruction A=200000 and instruction B=300000. Canceling A after payment creates only A's refund; B remains paid and continues its own fulfillment. A completed refund records the agreed amount returned; a permitted smaller manual refund does not prove that the full original child amount was returned.

### Selected Payment and Cancellation Coordination

1. Commerce commits the order group, child orders/items, finalized prices and one complete CheckoutPaymentRequested manifest in one local transaction. Retrying checkout returns the same group and child IDs. Individual OrderCreated events remain available to their existing consumers.
2. Payment creates the group's single session and all instructions from the complete manifest. An exact, on-time verified receipt locks the session/instructions, writes one NORMAL/SUCCESS ledger row, funds every instruction and saves CheckoutPaymentConfirmed to outbox atomically. Commerce applies valid child transitions and emits individual OrderPaid events in its own transaction.
3. Before payment, cancel the entire order group or let its single session expire. R1 does not remove one unpaid child, recalculate the total or issue a replacement session. Group cancellation and payment acceptance are serialized locally; asynchronous delivery can still mean that payment has accepted money before the cancellation event reaches it. Preserve that financial fact and compensate the canceled child orders rather than resurrecting them or claiming immediate cross-database cancellation.
4. After payment, an eligible child cancellation retains the existing shipment handover/carrier-cancellation coordination. Its automatic refund uses only that child's funded instruction amount and the fixed system actor. UNIQUE(refunds.order_id) permits at most one refund record for that child; item returns are outside the R1 contract.
5. Local version columns control local concurrency. Per-order refund events retain refundVersion and orders.last_refund_version to order request/completion snapshots; envelope eventVersion describes the event schema. The removed session-replacement fields are not used. Deduplicate event/provider/business keys, retain financial evidence, and use durable retry/DLQ for missing prerequisites.

### Retention, Historical Data and Implementation Verification

- Under SRS §5.8 / BR-240, retain orders and related payment/refund evidence for at least 5 years; auction/bid/settlement history for at least 3 years; system/event/audit logs for at least 90 days. Audit is append-only, without APIs to edit/delete history to change its content; audit read permissions follow #192/BR-241.
- Retain published outbox rows for at least 90 days; never purge unpublished rows. Ordinary processed markers remain for 90 days; critical order/payment/refund markers remain for 5 years/the ledger lifetime. After purging, old replays require controlled backfill with business-key safeguards rather than relying on removed markers.
- Preserve source UUIDs when ownership changes. Backfill actors according to #249; contact reuse drops old unique constraints and creates partial uniques after active-duplicate checks. New snapshot/eligibility NOT NULL/FKs are validated only after verified backfill. Historical rows lacking evidence go into migration exceptions; do not invent buyers/times/addresses to satisfy constraints.
- Configure reputation formula/threshold policy versions before enabling enforcement. The SRS only states CONFIGURED; do not present unsourced numeric thresholds or weights as SRS requirements. Store the policy version for each change in history so recomputation is verifiable.
- Source files and hashes are retained in task verification. Field/constraint tables specify the target; this turn ran no migrations and changed no production data.

### Draw.io and Layout

`schema-after-service-split.drawio` is an updated copy of `D:/KyOJT/erd/service-erd.drawio`. Retain the original page order and the x/y positions and widths of retained tables; rename fulfillment to inventory and move transferred tables to their new service pages. Unchanged technical tables retain their shapes/styles/geometries. Field rows are adjusted only inside changed tables to prevent overlap, without rerunning layout algorithms over existing tables. Payment/shipment/rating pages and auth ownership notes are added; the user uploads the diagram to Drive and captures evidence after review.
