# Shipment scaffold: ownership and cutover handoff (#241)

## Design baseline and scope

The scaffold implements the reviewed `shipment-service / shipment_db` target in
[#250 / !71](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/merge_requests/71),
revision `363c50d`, together with the PostgreSQL enum/audit conventions of #249.
!71 is pending merge at implementation time: rebase the dependent schema changes
onto `develop` after !71 merges, resolve any design changes, and repeat migration
validation before merging #241. The other service scaffolds, including #240,
are independent.

`shipment-service` listens on **8088**, receives `config/shipment-service.yml` from
Config Server, registers in Eureka and owns **shipment_db** exclusively. Its V1
creates `shipments`, `shipment_history`, `outbox` and `processed_events`. PostgreSQL
18 supplies the retained `uuidv7()` default. `init.sql` already provisions the
database under #235; an existing PostgreSQL volume requires an explicit one-time
`CREATE DATABASE shipment_db`, rather than deleting/recreating the volume.

One seller order has one warehouse and at most one shipment (`UNIQUE(order_id)`).
Several seller orders in one shared payment/order group remain several independent
shipment identities. `order_id`, `warehouse_id` and `buyer_id` are logical UUIDs;
there is no warehouse table, cross-database FK or direct access to another service's
database. History retains its local cascading FK to `shipments.id`.

Both business tables use `shipment_status`, including `PENDING_TRACKING` and the
source spelling `CANCELLED`, and NOT NULL `created_by`/`updated_by` with the #249
all-zero system actor default. Unknown historical audit attribution uses that
technical actor; it does not invent a user identity. History's `changed_by` may
remain NULL when unknown. Technical tables have no business audit actors.

The local auditing configuration follows #249 and disables itself when the shared
`CurrentUserAuditor` is available, so integrating #249 does not register JPA auditing
twice. Reconcile the local actor superclass with the shared implementation on rebase.

This issue prepares buyer/shipping snapshots, manual-request idempotency,
version/retry fields and cancellation fields (`cancel_request_id`,
`cancel_fenced_at`, `handed_over_at`). Carrier adapters/calls (#201), the consumer
and retry behavior (#202), and tracking/webhooks/state transitions (#203) remain
follow-up work. Those implementations must honor row locks/version and the
cancellation fence; columns alone neither prevent a carrier handover nor make an
external carrier call atomic. Routes are integrated under #245.

## Source and target ownership with #243

| Data | Before cutover | After verified cutover |
|---|---|---|
| Shipment business rows | `fulfillment_db.shipments` | `shipment_db.shipments`, only shipment-service writes |
| Shipment history | `fulfillment_db.shipment_history` | `shipment_db.shipment_history`, original IDs/relationships preserved |
| Warehouses/inventory | fulfillment-service / `fulfillment_db` | inventory-service / `inventory_db` under #243 |
| Existing outbox/processed markers | fulfillment-service | Stay with inventory's technical state under #243 |
| New shipment outbox/markers | Absent | Fresh tables owned solely by shipment-service |

If #243 renames fulfillment to inventory first, the source shipping rows temporarily
reside in `inventory_db`; the same preservation/cutover rules apply. Agree that
source name and a single shipping writer before release. Do not share source
technical tables or copy their processed markers into shipment's consumer state.

The original applied fulfillment **V1 and V2 remain unchanged**; V2 only seeds
warehouse data. This scaffold neither copies nor drops source rows automatically.
Source removal belongs to a later, newly versioned migration coordinated with #243,
after target readiness and verified cutover. Coordinate its free migration number
and enum/audit overlap with #249 rather than rewriting an applied migration.

## Existing-row copy, validation and switch

1. Inventory the actual source, including an explicitly verified empty source.
   Record source/target versions, counts, PK sets, current shipping writers,
   unpublished shipping outbox rows and a restorable consistent backup/export.
   Freeze source shipping writes or use an agreed change-capture/final-delta
   window; keep inventory writes independent where possible.
2. Build a reviewed per-order backfill manifest from trusted historical
   exports/contracts. Existing V1 lacks `buyer_id` and `shipping_info`; both are
   required in the clean target. Resolve the real buyer, historical recipient,
   address and order lines before importing. Missing/contradictory evidence is a
   migration exception that **blocks cutover**. Do not use `{}`, guessed buyer or
   warehouse IDs, current profile data in place of historical addresses, or
   runtime queries into `commerce_db` to fill the gap. Use verified parcel/pickup
   information when required, without inventing carrier measurements.
3. Initialize the clean target with shipment Flyway V1. Import parents before
   history using a controlled migration tool/release operation, not application
   cross-database queries. Preserve original shipment/history IDs, order and
   warehouse IDs, fees, carrier/service, tracking, status, descriptions and
   timestamps. Cast known statuses to the shared enum; resolve unknown history
   statuses as exceptions instead of silently remapping them. New audit actors
   follow #249 (known attribution where verified, otherwise system UUID); new
   retry/idempotency/cancellation values require explicit documented decisions.
4. Compare counts and bidirectional PK sets, every preserved field, per-order
   uniqueness, history-to-shipment membership and history ordering. Verify no
   orphans, unrecognized statuses, negative fees/retry counts or invalid JSON
   objects; validate required snapshot content against the agreed contract.
   Confirm there is exactly the one local history FK and no warehouse/cross-service
   FK. Record failed rows and require zero unresolved exceptions before switch.
5. Reconcile source unpublished shipping events separately: either drain them
   through the source relay before switch or use a reviewed transfer/replay plan
   retaining original event IDs/envelopes and preventing two relays publishing the
   same intent. Keep shipment's `processed_events` fresh; use the target consumer's
   agreed replay/deduplication procedure, never inventory's success markers.
6. Record the validation evidence and switch the sole shipping writer/routing to
   shipment-service in a controlled release. Keep the source shipping tables
   read-only for the agreed rollback/retention interval. Verify health, Eureka,
   access ownership and event routing before enabling real traffic. If verification
   fails, stop the new writer and reconcile target changes before reverting; do
   not enable two independent writers or discard newly accepted target evidence.
7. Only after successful target validation, the switch and the agreed rollback
   interval may #243 retire source shipping tables with a new migration. Preserve
   backup/export evidence and required shipment/history retention (at least five
   years under #250). Do not cascade-drop warehouse/inventory data. A populated
   source without verified snapshots stays intact and blocks retirement.

## OrderPaid readiness: #186 / #202

The current [`OrderPaid` payload](../api/events.md) lacks the warehouse and complete
shipping snapshot required by #250. The trusted contract must provide, **per seller
order**, `orderId`/`orderVersion`, `buyerId`, `warehouseId`, carrier/service selection,
`shippingFee`, recipient name/phone/address and snapshotted items (`skuId`,
`productName`, positive quantity), plus adapter-required parcel measurements and
pickup/locality information. A payment/order group is context, not the shipment
aggregate key. Verify the originating contract's trust, version and complete
snapshot semantics before implementing the real flow.

#186 owns publishing/contract coordination; #202 owns ingestion/manual creation
and validation. Their readiness gates the real OrderPaid shipping flow, not
creation of this scaffold. This issue does not claim the publisher or consumer
already supplies/uses the target snapshot. Missing data must be retried/rejected
through the agreed contract handling without marking successful processing,
fabricating snapshots or querying `commerce_db`. Future handlers commit their own
business/history/outbox/processed marker changes in one shipment_db transaction.
