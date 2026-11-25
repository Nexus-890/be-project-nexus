# Demo stock seed (#133)

Catalog V4 creates ten demo variants with database-generated `uuidv7()` IDs.
Copying IDs from one database into an inventory Flyway migration would seed
unrelated UUIDs on another fresh database. The agreed #133 approach retains those
IDs: a local operator script reads the ten real IDs from catalog, then inserts
stock in inventory. Neither service accesses the other service's database.

No applied migration is changed. Inventory V7 is already the ledger opening
balance migration and V8 belongs to reservations; the issue's original
`V7__seed_stock.sql` filename cannot be reused. The SQL template lives at
`infra/local-dev/sql/seed-demo-stock.sql` and runs after both Flyway histories.
This is an explicit demo setup step, not a production startup migration.

## Run after the services have migrated

From `be-project-nexus`, use PowerShell 7 (`pwsh`) with the local PostgreSQL container:

```powershell
pwsh -File ./infra/local-dev/seed-demo-stock.ps1 -Container nexus-postgres -DatabaseUser nexus
```

Use the actual configured database user. For local PostgreSQL/libpq connections:

```powershell
pwsh -File ./infra/local-dev/seed-demo-stock.ps1 -PsqlPath psql `
  -CatalogConnection 'host=127.0.0.1 port=5432 dbname=catalog_db user=nexus' `
  -InventoryConnection 'host=127.0.0.1 port=5432 dbname=inventory_db user=nexus'
```

Keep passwords in libpq password files/environment or the container's configured
authentication. The script never reads account data or prints connection strings.
On a fresh inventory, expect `demoSkus=10`, `newBalances=10`,
`movedFromSharedWarehouses=0`, `newIntakes=10` and
`sellerWarehouse=SELLER-68000000000040008000000000000002`. `demoSkus` counts the
distinct input UUIDs, including on reruns where nothing changes. An identical rerun
returns zero new balances, moves and intakes. The ten UUIDs must match
catalog V4's two exact codes for each of the five V3 demo products; missing SKUs
abort before any inventory write. Custom SKUs are outside this seed.

## Data and transaction boundary

The catalog SELECT is read-only. The five demo products belong to
`seller@nexus.local` (`68000000-0000-4000-8000-000000000002`), so their stock lives
in that seller's own warehouse, not in WH_001. One SQL transaction:

1. creates `SELLER-68000000000040008000000000000002` when it is missing, with the
   pickup `12 Lê Lợi`, ward Bến Thành `1003504`, Hồ Chí Minh `1000001`, and sets
   that pickup on an existing seller warehouse only when it has none (a pickup the
   seller saved is kept). The warehouse must be ACTIVE, otherwise nothing is written;
2. inserts each missing `(sku_id, seller warehouse)` balance at zero;
3. moves the available units these SKUs still hold in shared warehouses (WH_001,
   WH_HN) into the seller warehouse with a TRANSFER pair, as the first seller intake
   does. Reserved and unavailable units stay where they are and are settled there;
4. gives `total=available=20` with one INTAKE of 20 (reference_id = the balance ID)
   only to SKUs whose seller balance was just inserted and that had nothing to move.

The schema's quantity CHECKs remain enabled. Audit actors use the schema's existing
system UUID defaults. A failed ledger write rolls back the balances too. A rerun does
not refill purchased or reserved units, alter a seller balance that already exists,
change catalog UUIDs or append another intake. Each balance's ledger sum stays equal
to its total.

After restarting the app, stock remains persisted. Restarting or explicitly
rerunning this script does not add another seed intake. Query `inventory_balances`
to verify quantities; the seed verifier does not exercise the HTTP stock API.

## One warehouse per seller (V14)

Since inventory V14 each seller has one warehouse, created on the seller's first
intake (or first save on the seller Warehouse page). The seed writes into that
warehouse directly. Other sellers' SKUs follow the same rule on their first intake:
it moves the SKU's available units out of WH_001 with a TRANSFER pair (-q in WH_001,
+q in the seller warehouse). Units reserved at that moment stay in WH_001 and are
settled there.

Since inventory V15 a seller can set the pickup area of that warehouse (province,
ward and house number and street) on the seller Warehouse page. Checkout and auction
quotes then send that pickup to shipment-service, and the order keeps it in
`shipping_info.pickupAddress` when its stock is held in the seller warehouse. A
warehouse without a complete pickup area, or one that is not ACTIVE, keeps quoting
from the platform pickup (`SHIPPING_PICKUP_*`).

Saving a new or changed pickup calls shipment-service
`POST /internal/v1/shipping/pickup-address-checks` with the seller's token (SELLER
with SHIPPING.QUOTE). Shipment resolves the province and ward from carrier master
data, then quotes a 500 g parcel from the pickup to its own ward. GHN answers
FROM_ADDRESS_CONFLICT when the street does not belong to the ward (for example
`25 Hàng Bài` in Hoàn Kiếm); that becomes `SHIPMENT_PICKUP_ADDRESS_CONFLICT` and,
in inventory, 422 `INVENTORY_INVALID_PICKUP_ADDRESS`. Nothing is saved on a
rejection or when the check is unavailable (503 `INVENTORY_PICKUP_CHECK_UNAVAILABLE`).

## Verify in disposable PostgreSQL 18 databases

With an existing disposable PostgreSQL 18 container (no host psql or exposed
database port required):

```powershell
pwsh -File ./docs/testing/verify-demo-stock.ps1 -Container nexus-postgres -DatabaseUser nexus
```

Or with a local PostgreSQL 18 server and psql:

```powershell
pwsh -File ./docs/testing/verify-demo-stock.ps1 -Port 56533 -DatabaseUser postgres `
  -PsqlPath 'path/to/PostgreSQL18/bin/psql.exe'
```

The verifier creates only randomly named test databases and drops them in a
finally block; it never seeds the container's existing catalog_db/inventory_db.
The selected database user must be able to create and drop these test databases.
The container must already be running; the verifier does not create or remove it.
It applies the real catalog/inventory SQL migrations, separately
committing each file so V6's new enum values are available afterward. It executes
the production script, checks all four #133 data criteria, reruns with consumed
stock and unrelated data, compares two independently seeded catalogs, and forces
a ledger error to verify rollback. It also checks missing catalog variants, an inactive
seller warehouse, moving legacy WH_001 stock, and keeping a pickup the seller saved. This checks SQL setup and seeding, not
Flyway's engine, HTTP authorization or checkout integration. Seed accepts
`-CatalogDatabase` and `-InventoryDatabase` in Container mode so the verifier and
its concurrent jobs target only the temporary databases.
