<#
.SYNOPSIS
Opt-in persistent local Buyer Account and Seller Portal review data.
.DESCRIPTION
Requires migrated user/catalog/commerce/inventory/auction/notification databases.
Adds only task UUIDs in namespace 93000000. Retains credentials, existing records,
cart history, read markers, edited orders and consumed stock on every rerun.
Creates no provider transactions, email deliveries or production startup seeds.
.EXAMPLE
pwsh -File ./seed-account-seller.ps1
.EXAMPLE
pwsh -File ./seed-account-seller.ps1 -VerifyOnly
#>
[CmdletBinding()]
param(
    [string]$Container = 'nexus-postgres',
    [string]$DatabaseUser = '',
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$encoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = $encoding
[Console]::OutputEncoding = $encoding
if (-not $DatabaseUser) {
    $line = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^DB_USERNAME=' } | Select-Object -First 1
    if (-not $line) { throw 'Configure DB_USERNAME or supply -DatabaseUser. No credential is changed by this helper.' }
    $DatabaseUser = $line.Substring('DB_USERNAME='.Length).Trim().Trim('"').Trim("'")
}
function Invoke-AccountSql([string]$Database,[string]$Sql) {
    $result = $Sql | & docker exec -i $Container psql -X -q -A -t -v ON_ERROR_STOP=1 -U $DatabaseUser -d $Database 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL failed in $Database. Ensure the selected local container and current migrations are ready. No pending transaction was committed." }
    return ($result -join "`n").Trim()
}
# Validate all databases and identities before the first write.
$checks = @{
    user_db = "SELECT count(*)=2 FROM users WHERE id IN ('68000000-0000-4000-8000-000000000002','68000000-0000-4000-8000-000000000003') AND status='ACTIVE' AND deleted_at IS NULL;"
    catalog_db = "SELECT count(*)=2 FROM information_schema.columns WHERE (table_name,column_name) IN (('products','created_by'),('product_images','created_by'));"
    commerce_db = "SELECT count(*)=1 FROM information_schema.columns WHERE table_name='orders' AND column_name='order_group_id';"
    inventory_db = "SELECT count(*)=1 FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typname='inventory_movement_type' AND e.enumlabel='INTAKE';"
    auction_db = "SELECT count(*)=1 FROM information_schema.columns WHERE table_name='auctions' AND column_name='created_by';"
    notification_db = "SELECT count(*)=1 FROM information_schema.columns WHERE table_name='notifications' AND column_name='created_by';"
}
foreach ($database in $checks.Keys) {
    if ((Invoke-AccountSql $database $checks[$database]) -ne 't') { throw "Missing schema/account prerequisite in $database. Start/migrate its current service before seeding. No task data was written." }
}
if ((Invoke-AccountSql 'catalog_db' "SELECT count(*) FROM categories WHERE id='69000000-0000-4000-8000-000000000103';") -ne '1') { throw 'Catalog V3 demo category must exist before seeding.' }
if ((Invoke-AccountSql 'inventory_db' "SELECT count(*) FROM warehouses WHERE code='WH_001' AND status='ACTIVE';") -ne '1') { throw 'Start inventory-service so its configured WH_001 warehouse exists before seeding. No task data was written.' }
$imageManifestPath = Join-Path $PSScriptRoot 'account-seller/images.json'
$imageEntries = @(Get-Content -LiteralPath $imageManifestPath -Raw | ConvertFrom-Json)
$expectedImages = @{
    '93000000-0000-4000-8000-000000000401' = '93000000-0000-4000-8000-000000000201'
    '93000000-0000-4000-8000-000000000402' = '93000000-0000-4000-8000-000000000202'
}
if ($imageEntries.Count -ne 2 -or @($imageEntries.imageId | Select-Object -Unique).Count -ne 2) { throw 'The task image manifest must contain the two fixed task image IDs.' }
foreach ($entry in $imageEntries) { if ($expectedImages[[string]$entry.imageId] -ne [string]$entry.productId) { throw 'Unexpected task image ID/product pair; no task data was written.' } }
# Storage provisioning is a separate, explicit opt-in command. Check public bytes
# before DB writes and share the same env/bucket resolution with the provisioner.
& (Join-Path $PSScriptRoot 'provision-product-images.ps1') -EnvFile $EnvFile -ManifestFile $imageManifestPath -VerifyOnly | Out-Null
$imageBase = ([string]$env:SUPABASE_URL).TrimEnd('/')
$imageBucket = if ($env:SUPABASE_BUCKET) { $env:SUPABASE_BUCKET } else { 'product-images' }
$legacyImageUrl = Invoke-AccountSql 'catalog_db' "SELECT image_url FROM product_images WHERE id='69000000-0000-4000-8000-000000000304' AND product_id='69000000-0000-4000-8000-000000000204';"
$imageManifest = @($imageEntries | ForEach-Object { [ordered]@{
    image_id = $_.imageId; product_id = $_.productId
    image_url = "$imageBase/storage/v1/object/public/$imageBucket/products/$($_.productId)/$($_.imageId).jpg"
    legacy_image_url = $legacyImageUrl
} }) | ConvertTo-Json -Compress
if (-not $VerifyOnly) {
    $catalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'account-seller/catalog.sql') -Raw
    if (-not $catalog.Contains('-- account-seller-image-manifest')) { throw 'Catalog image manifest marker is missing; no task data was written.' }
    $catalog = $catalog.Replace('-- account-seller-image-manifest', "CREATE TEMP TABLE account_seller_images ON COMMIT DROP AS SELECT * FROM jsonb_to_recordset('"+$imageManifest.Replace("'","''")+"'::jsonb) AS x(image_id uuid,product_id uuid,image_url text,legacy_image_url text);")
    Invoke-AccountSql 'catalog_db' $catalog | Out-Null
    $manifest = Invoke-AccountSql 'catalog_db' @'
SELECT row_to_json(x) FROM (
 SELECT p.id product_id,p.name product_name,s.id sku_id,s.sku_code,s.price,
 (SELECT image_url FROM product_images i WHERE i.product_id=p.id ORDER BY sort_order,id LIMIT 1) image_url
 FROM products p JOIN skus s ON s.product_id=p.id
 WHERE s.id='93000000-0000-4000-8000-000000000301' AND p.status='ACTIVE' AND s.status='ACTIVE' AND s.currency='VND'
) x;
'@
    if (-not $manifest -or ($manifest | ConvertFrom-Json).price -lt 0) { throw 'Active task catalogue snapshot is unavailable; no commerce data was written.' }
    $commerce = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'account-seller/commerce.sql') -Raw
    $commerce = $commerce.Replace('-- account-seller-snapshot', "CREATE TEMP TABLE account_seller_snapshot ON COMMIT DROP AS SELECT * FROM jsonb_to_record('"+$manifest.Replace("'","''")+"'::jsonb) AS x(product_id uuid,product_name text,sku_id uuid,sku_code text,price numeric(19,2),image_url text);")
    Invoke-AccountSql 'commerce_db' $commerce | Out-Null
    foreach ($domain in @('inventory','auction','notification')) {
        Invoke-AccountSql ($domain+'_db') (Get-Content -LiteralPath (Join-Path $PSScriptRoot "account-seller/$domain.sql") -Raw) | Out-Null
    }
}
$counts = [ordered]@{
    products = Invoke-AccountSql 'catalog_db' "SELECT count(*) FROM products WHERE id::text LIKE '93000000-%';"
    skus = Invoke-AccountSql 'catalog_db' "SELECT count(*) FROM skus WHERE sku_code LIKE 'ACCOUNT-SELLER-%';"
    images = Invoke-AccountSql 'catalog_db' "SELECT count(*) FROM product_images WHERE id::text LIKE '93000000-%';"
    orderGroups = Invoke-AccountSql 'commerce_db' "SELECT count(*) FROM order_groups WHERE idempotency_key LIKE 'account-seller-group-%';"
    orders = Invoke-AccountSql 'commerce_db' "SELECT count(*) FROM orders WHERE idempotency_key LIKE 'account-seller-order-%';"
    orderItems = Invoke-AccountSql 'commerce_db' "SELECT count(*) FROM order_items WHERE id::text LIKE '93000000-%';"
    orderHistory = Invoke-AccountSql 'commerce_db' "SELECT count(*) FROM order_status_history WHERE id::text LIKE '93000000-%';"
    stockBalances = Invoke-AccountSql 'inventory_db' "SELECT count(*) FROM inventory_balances WHERE id::text LIKE '93000000-%';"
    stockIntakes = Invoke-AccountSql 'inventory_db' "SELECT count(*) FROM inventory_movements WHERE id::text LIKE '93000000-%';"
    auctions = Invoke-AccountSql 'auction_db' "SELECT count(*) FROM auctions WHERE id::text LIKE '93000000-%';"
    notifications = Invoke-AccountSql 'notification_db' "SELECT count(*) FROM notifications WHERE id::text LIKE '93000000-%';"
}
$expected = @{products=4;skus=6;images=2;orderGroups=8;orders=8;orderItems=8;orderHistory=8;stockBalances=6;stockIntakes=6;auctions=2;notifications=6}
foreach ($key in $expected.Keys) { if ([int]$counts[$key] -ne $expected[$key]) { throw "Task record verification failed for $key; expected $($expected[$key]), found $($counts[$key]). Existing records were retained." } }
$imagePrefix = ($imageBase+"/storage/v1/object/public/$imageBucket/products/").Replace("'","''")
$scopedImages = Invoke-AccountSql 'catalog_db' ("WITH manifest AS (SELECT * FROM jsonb_to_recordset('"+$imageManifest.Replace("'","''")+"'::jsonb) AS x(image_id uuid,product_id uuid,image_url text,legacy_image_url text)) SELECT count(*) FROM product_images image JOIN manifest ON image.id=manifest.image_id AND image.product_id=manifest.product_id WHERE starts_with(image.image_url,'"+$imagePrefix+"' || image.product_id::text || '/') AND substring(image.image_url FROM length('"+$imagePrefix+"' || image.product_id::text || '/')+1) ~ '^[0-9a-fA-F-]{36}\.(jpg|png|webp)$';")
if ($scopedImages -ne '2') { throw 'Seed image metadata is not scoped to its own product storage path. Other URLs/edits were preserved; review the two fixed task image records.' }
$counts | ConvertTo-Json
Write-Output 'Verified persistent local review data. Reruns preserve user changes; current statuses/read counts may differ after review.'
