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
On a fresh inventory, expect `demoSkus=10`, `newBalances=10`, `newMovements=10`.
`demoSkus` reports the input UUID array length, including on reruns where no new
balance is inserted. An identical rerun returns zero new balances/movements. The ten UUIDs must match
catalog V4's two exact codes for each of the five V3 demo products; missing SKUs
abort before any inventory write. Custom SKUs are outside this seed.

## Data and transaction boundary

The catalog SELECT is read-only. In inventory, WH_001 must already exist and be
ACTIVE. One SQL transaction inserts each missing `(sku_id, warehouse_id)` with
`total=available=20`, `reserved=unavailable=0`, then one INTAKE of 20 whose
reference_id is that new balance's ID. Every INSERT has ON CONFLICT DO NOTHING.
The schema's quantity CHECKs remain enabled. Audit actors use the schema's
existing system UUID defaults.

Only the INSERT RETURNING rows create movements. A failed ledger write rolls
back its balances too. Concurrent invocations rely on the balance unique key;
only the transaction that inserted a balance can add its seed movement. A rerun
does not refill purchased/reserved units, alter pre-existing balances, backfill
unrelated ledger history, change catalog UUIDs, or append an intake to an existing
balance. Existing data is left for its owning reconciliation workflow.

After restarting the app, stock remains persisted. Restarting or explicitly
rerunning this script does not add another seed intake. Query `inventory_balances`
to verify quantities; the seed verifier does not exercise the HTTP stock API.

## One warehouse per seller (V14)

Since inventory V14 each seller has one warehouse, created on the seller's first
intake (or first save on the seller Warehouse page). The seed still writes to WH_001;
those units stay readable and reservable there until the SKU first lands in a seller
warehouse. That intake (by the seller, or by an admin without a warehouse) moves the
SKU's available units out of WH_001 with a TRANSFER pair (-q in WH_001, +q in the
seller warehouse). Units reserved at that moment stay in WH_001 and are settled there.

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
a ledger error to verify rollback. It also checks missing catalog variants and
missing/inactive warehouse failures. This checks SQL setup and seeding, not
Flyway's engine, HTTP authorization or checkout integration. Seed accepts
`-CatalogDatabase` and `-InventoryDatabase` in Container mode so the verifier and
its concurrent jobs target only the temporary databases.
