<#
.SYNOPSIS
Seed 20 units for each of the ten V4 demo SKUs, using their real catalogue UUIDs.
.DESCRIPTION
Run after catalog and inventory migrations. Reads catalog_db; in one inventory_db
transaction creates the demo seller's warehouse (pickup 12 Le Loi, Ben Thanh, HCM),
moves available stock of these SKUs out of shared warehouses with TRANSFER rows and
gives 20 units with an INTAKE row only to SKUs that had no stock there yet. Existing
stock, ledger rows and SKU identities are preserved, including after purchases.
Use libpq password files/environment for credentials; do not put passwords in commands.
.EXAMPLE
pwsh -File .\seed-demo-stock.ps1 -Container nexus-postgres -DatabaseUser nexus
.EXAMPLE
pwsh -File .\seed-demo-stock.ps1 -PsqlPath psql -CatalogConnection 'host=127.0.0.1 dbname=catalog_db user=nexus' -InventoryConnection 'host=127.0.0.1 dbname=inventory_db user=nexus'
#>
[CmdletBinding(DefaultParameterSetName = 'Container')]
param(
    [Parameter(ParameterSetName = 'Container')]
    [string]$Container = 'nexus-postgres',
    [Parameter(Mandatory, ParameterSetName = 'Container')]
    [string]$DatabaseUser,
    [Parameter(ParameterSetName = 'Container')]
    [string]$CatalogDatabase = 'catalog_db',
    [Parameter(ParameterSetName = 'Container')]
    [string]$InventoryDatabase = 'inventory_db',
    [Parameter(ParameterSetName = 'Local')]
    [string]$PsqlPath = 'psql',
    [Parameter(Mandatory, ParameterSetName = 'Local')]
    [string]$CatalogConnection,
    [Parameter(Mandatory, ParameterSetName = 'Local')]
    [string]$InventoryConnection
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$connectionMode = $PSCmdlet.ParameterSetName

function Invoke-SeedDatabase([string]$Database, [string]$Sql) {
    if ($connectionMode -eq 'Local') {
        $connection = if ($Database -eq 'catalog_db') { $CatalogConnection } else { $InventoryConnection }
        $output = $Sql | & $PsqlPath -X -q -A -t -v ON_ERROR_STOP=1 -d $connection
    } else {
        $databaseName = if ($Database -eq 'catalog_db') { $CatalogDatabase } else { $InventoryDatabase }
        $output = $Sql | & docker exec -i $Container psql -X -q -A -t -v ON_ERROR_STOP=1 -U $DatabaseUser -d $databaseName
    }
    if ($LASTEXITCODE -ne 0) { throw "Demo stock seed failed in $Database; inspect the PostgreSQL error above." }
    return ($output -join "`n").Trim()
}

# V3 fixes product IDs; V4 creates two stable codes per product but random UUIDs.
# Select only these demo variants, never arbitrary customer or seller SKUs.
$skuJson = Invoke-SeedDatabase 'catalog_db' @'
BEGIN READ ONLY;
SELECT COALESCE(jsonb_agg(id ORDER BY sku_code), '[]'::jsonb)::text
FROM skus
WHERE product_id IN (
    '69000000-0000-4000-8000-000000000201'::uuid,
    '69000000-0000-4000-8000-000000000202'::uuid,
    '69000000-0000-4000-8000-000000000203'::uuid,
    '69000000-0000-4000-8000-000000000204'::uuid,
    '69000000-0000-4000-8000-000000000205'::uuid
)
AND sku_code IN (
    'SKU-' || replace(product_id::text, '-', '') || '-01',
    'SKU-' || replace(product_id::text, '-', '') || '-02'
);
COMMIT;
'@
# Enumerate the parsed array in both Windows PowerShell 5.1 and PowerShell 7.
$skuIds = @((ConvertFrom-Json -InputObject $skuJson) | ForEach-Object { ([guid]$_).ToString() })
if ($skuIds.Count -ne 10 -or @($skuIds | Select-Object -Unique).Count -ne 10) {
    throw 'Expected all ten distinct V4 demo SKU UUIDs; no inventory data changed. Run catalog migrations first.'
}
# Regenerate JSON from validated UUIDs only, then quote it as SQL data.
$idsLiteral = (ConvertTo-Json -InputObject $skuIds -Compress).Replace("'", "''")
$sqlTemplate = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sql/seed-demo-stock.sql') -Raw
$result = Invoke-SeedDatabase 'inventory_db' ($sqlTemplate.Replace('__DEMO_SKU_IDS_JSON__', $idsLiteral))
ConvertFrom-Json -InputObject $result
