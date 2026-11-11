<#
.SYNOPSIS
Opt-in local buyer shop fixture. Requires the selected service databases to be migrated.
.DESCRIPTION
Adds 24 products, 72 variants/stock balances, 2 active + 2 scheduled auctions and 5 buyer orders.
Fixed demo UUIDs make reruns idempotent. Preserves existing carts, orders and consumed stock.
Does not create users, migrate schemas, send events, contact payment providers or upload images.
.EXAMPLE
& ./seed-buyer-shop.ps1 -Domains @('catalog','auction') -WaitSeconds 120
.EXAMPLE
& ./seed-buyer-shop.ps1 -VerifyOnly
#>
param(
    [string]$Container = 'nexus-postgres',
    [string]$DatabaseUser = '',
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [ValidateSet('catalog','inventory','commerce','auction')]
    [string[]]$Domains = @('catalog','inventory','commerce','auction'),
    [ValidateRange(0,600)]
    [int]$WaitSeconds = 0,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$seedEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = $seedEncoding
[Console]::OutputEncoding = $seedEncoding
if ($Domains.Count -eq 0) { throw 'Choose at least one demo domain.' }
$selectedDomains = @('catalog','inventory','commerce','auction') | Where-Object { $_ -in $Domains }
if (-not $DatabaseUser) {
    $dbUserLine = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^DB_USERNAME=' } | Select-Object -First 1
    if (-not $dbUserLine) { throw 'Set DatabaseUser or DB_USERNAME in the local .env.' }
    $DatabaseUser = $dbUserLine.Substring('DB_USERNAME='.Length).Trim().Trim('"').Trim("'")
}
function Invoke-ShopSql([string]$Database, [string]$Sql, [switch]$Probe) {
    $result = $Sql | & docker exec -i $Container psql -X -q -A -t -v ON_ERROR_STOP=1 -U $DatabaseUser -d $Database 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($Probe) { return '' }
        throw "Buyer shop query failed in $Database. Ensure the local PostgreSQL container and service migrations are ready; no pending transaction was committed."
    }
    return ($result -join "`n").Trim()
}
# Check every dependency before the first write. Transactions are per service DB.
$checks = @{
    catalog_db="SELECT count(*)=1 FROM information_schema.columns WHERE table_name='product_images' AND column_name='created_by';"
    commerce_db="SELECT count(*)=1 FROM information_schema.columns WHERE table_name='orders' AND column_name='order_group_id';"
    inventory_db="SELECT count(*)=1 FROM information_schema.columns WHERE table_name='inventory_balances' AND column_name='version';"
    auction_db="SELECT count(*)=1 FROM information_schema.columns WHERE table_name='auctions' AND column_name='created_by';"
}
$seedReadyUntil = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
do {
    $pendingDatabases = @($selectedDomains | Where-Object {
        (Invoke-ShopSql ($_+'_db') $checks[$_+'_db'] -Probe) -ne 't'
    })
    if ($pendingDatabases.Count -eq 0) { break }
    if ([DateTime]::UtcNow -ge $seedReadyUntil) {
        throw "Start/migrate these selected services before seeding: $($pendingDatabases -join ', '). No fixture was written."
    }
    Start-Sleep -Seconds 2
} while ($true)

# Product categories come from the existing catalog V3 seed. Verify prerequisites
# before writing any selected domain; never create or replace user credentials.
if ('catalog' -in $selectedDomains) {
    $categories = Invoke-ShopSql 'catalog_db' "SELECT count(*) FROM categories WHERE id IN ('69000000-0000-4000-8000-000000000102','69000000-0000-4000-8000-000000000103');"
    if ($categories -ne '2') { throw 'The catalog V3 demo categories must exist first. No fixture was written.' }
}
if ('commerce' -in $selectedDomains) {
    $accounts = Invoke-ShopSql 'user_db' "SELECT count(*) FROM users WHERE id IN ('68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000003') AND status='ACTIVE' AND deleted_at IS NULL;"
    if ($accounts -ne '2') { throw 'The standard demo seller and buyer accounts must exist first. No fixture was written.' }
}
if ('catalog' -notin $selectedDomains -and ('auction' -in $selectedDomains -or 'commerce' -in $selectedDomains -or 'inventory' -in $selectedDomains)) {
    $catalogReferences = Invoke-ShopSql 'catalog_db' "SELECT count(*) FROM skus WHERE sku_code LIKE 'DEMO-SHOP-%';"
    if ($catalogReferences -ne '72') { throw 'Seed the catalog domain before inventory, commerce or auction demo data. No fixture was written.' }
}
if (-not $VerifyOnly) {
    foreach ($domain in $selectedDomains) {
        $domainSql = Get-Content -LiteralPath (Join-Path $PSScriptRoot "buyer-shop/$domain.sql") -Raw
        if ($domain -eq 'commerce') {
            # Cross-service references are data, not cross-database joins/FKs.
            # Read current catalog prices after the catalog domain has been seeded.
            $cartManifest = Invoke-ShopSql 'catalog_db' @'
SELECT coalesce(json_agg(snapshot ORDER BY snapshot.sku_id),'[]'::json)
FROM (
 SELECT s.product_id,s.id AS sku_id,s.price::text AS price
 FROM skus s JOIN products p ON p.id=s.product_id
 WHERE s.id IN ('92000000-0000-4000-8000-000000001011',
                '92000000-0000-4000-8000-000000001091',
                '92000000-0000-4000-8000-000000001171')
   AND s.status='ACTIVE' AND s.currency='VND' AND s.price>=0
   AND p.status='ACTIVE' AND p.deleted_at IS NULL
   AND EXISTS (SELECT 1 FROM product_images pi WHERE pi.product_id=p.id)
) snapshot;
'@
            $cartSnapshot = @($cartManifest | ConvertFrom-Json)
            if ($cartSnapshot.Count -ne 3) { throw 'The three active, image-backed cart demo SKUs must exist in catalog. No commerce fixture was written.' }
            $manifestMarker = '-- buyer-shop-cart-manifest'
            if (-not $domainSql.Contains($manifestMarker)) { throw 'The commerce seed cart manifest marker is missing. No commerce fixture was written.' }
            $cartLiteral = $cartManifest.Replace("'", "''")
            $cartManifestSql = @"
CREATE TEMP TABLE buyer_shop_cart_manifest ON COMMIT DROP AS
SELECT * FROM jsonb_to_recordset('$cartLiteral'::jsonb)
 AS x(product_id uuid,sku_id uuid,price numeric(19,2));
"@
            $domainSql = $domainSql.Replace($manifestMarker, $cartManifestSql)
        }
        Invoke-ShopSql ($domain+'_db') $domainSql | Out-Null
        Write-Output "$domain demo fixture applied."
    }
}
$counts = [ordered]@{}
if ('catalog' -in $selectedDomains) {
    $counts.products = Invoke-ShopSql 'catalog_db' "SELECT count(*) FROM products WHERE id::text LIKE '92000000-%';"
    $counts.skus = Invoke-ShopSql 'catalog_db' "SELECT count(*) FROM skus WHERE sku_code LIKE 'DEMO-SHOP-%';"
    $counts.productsWithImages = Invoke-ShopSql 'catalog_db' "SELECT count(*) FROM products p WHERE p.id::text LIKE '92000000-%' AND EXISTS (SELECT 1 FROM product_images i WHERE i.product_id=p.id);"
    if ($counts.products -ne '24' -or $counts.skus -ne '72' -or $counts.productsWithImages -ne '24') { throw 'Catalog demo row-count verification failed.' }
}
if ('inventory' -in $selectedDomains) {
    $counts.stockBalances = Invoke-ShopSql 'inventory_db' "SELECT count(*) FROM inventory_balances WHERE warehouse_id='92000000-0000-4000-8000-000000000401';"
    if ($counts.stockBalances -ne '72') { throw 'Inventory demo row-count verification failed.' }
}
if ('commerce' -in $selectedDomains) {
    $counts.orders = Invoke-ShopSql 'commerce_db' "SELECT count(*) FROM orders WHERE idempotency_key LIKE 'buyer-shop-demo-order-%';"
    if ($counts.orders -ne '5') { throw 'Commerce demo row-count verification failed.' }
}
if ('auction' -in $selectedDomains) {
    $counts.auctions = Invoke-ShopSql 'auction_db' "SELECT count(*) FROM auctions WHERE id::text LIKE '92000000-%';"
    if ($counts.auctions -ne '4') { throw 'Auction demo row-count verification failed.' }
}
$counts | ConvertTo-Json
