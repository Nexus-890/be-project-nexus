<#
.SYNOPSIS
Seed exactly 10 real catalogue SKUs in buyer@nexus.local's ACTIVE cart.
.DESCRIPTION
Requires the existing local PostgreSQL container and migrated user/catalog/commerce
databases. Resolves the buyer by email; keeps account credentials, other accounts,
and historical carts untouched. Re-running resets only this buyer's active cart
to the same first 10 active VND SKUs, quantity 1, using current catalogue prices.
Exports the committed database cart as the Sprint 1 frontend mock fixture because
the commerce cart controllers have not landed yet.
.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\seed-buyer-cart.ps1
#>
param(
    [string]$Container = 'nexus-postgres',
    [string]$DatabaseUser = '',
    [string]$FixturePath = (Join-Path $PSScriptRoot '../../../fe-project-nexus/mocks/cart-seed.ts')
)

$ErrorActionPreference = 'Stop'
$buyerEmail = 'buyer@nexus.local'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = $utf8
[Console]::OutputEncoding = $utf8

if (-not $DatabaseUser) {
    $usernameLine = Get-Content (Join-Path $PSScriptRoot '.env') |
        Where-Object { $_ -match '^DB_USERNAME=' } | Select-Object -First 1
    if (-not $usernameLine) { throw 'Set DatabaseUser or configure DB_USERNAME in infra/local-dev/.env.' }
    $DatabaseUser = $usernameLine.Substring('DB_USERNAME='.Length).Trim().Trim('"').Trim("'")
}

function Invoke-Database([string]$Database, [string]$Sql) {
    $output = $Sql | & docker exec -i $Container psql -X -q -A -t -v ON_ERROR_STOP=1 -U $DatabaseUser -d $Database
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL command failed for $Database." }
    return ($output -join "`n").Trim()
}

$buyer = Invoke-Database 'user_db' @"
SELECT row_to_json(u) FROM (
    SELECT id, email FROM users
    WHERE lower(email) = '$buyerEmail' AND status = 'ACTIVE' AND deleted_at IS NULL
) u;
"@ | ConvertFrom-Json
if (-not $buyer -or -not $buyer.id) { throw 'Active buyer@nexus.local account was not found; no data changed.' }
$buyerId = ([guid]$buyer.id).ToString()

$skuJson = Invoke-Database 'catalog_db' @'
SELECT coalesce(json_agg(x ORDER BY x."productId", x."skuId"), '[]'::json) FROM (
    SELECT s.id AS "skuId", p.id AS "productId", p.name AS "productName",
           s.price::text AS "unitPrice", s.color, s.size, p.seller_id AS "sellerId",
           (SELECT image_url FROM product_images i WHERE i.product_id=p.id
            ORDER BY sort_order, id LIMIT 1) AS "imageUrl"
    FROM skus s JOIN products p ON p.id=s.product_id
    WHERE s.status='ACTIVE' AND p.status='ACTIVE' AND p.deleted_at IS NULL
      AND s.currency='VND'
    ORDER BY p.id, s.id LIMIT 10
) x;
'@
$skus = ConvertFrom-Json -InputObject $skuJson
if (@($skus).Count -ne 10) { throw 'At least 10 active VND catalogue SKUs are required; no data changed.' }

# The JSON is SQL data, never shell code. Quote apostrophes for its SQL literal.
$skuLiteral = $skuJson.Replace("'", "''")
$cartId = Invoke-Database 'commerce_db' @"
BEGIN;
CREATE TEMP TABLE seed_cart_skus ON COMMIT DROP AS
SELECT * FROM jsonb_to_recordset('$skuLiteral'::jsonb)
    AS x("skuId" uuid, "productId" uuid, "unitPrice" numeric(19,2));
CREATE TEMP TABLE seed_cart ON COMMIT DROP AS
WITH active_cart AS (
    INSERT INTO carts (user_id, status) VALUES ('$buyerId', 'ACTIVE')
    ON CONFLICT (user_id) WHERE status='ACTIVE' AND user_id IS NOT NULL
    DO UPDATE SET updated_at=now()
    RETURNING id
) SELECT id FROM active_cart;
DELETE FROM cart_items
WHERE cart_id=(SELECT id FROM seed_cart)
  AND sku_id NOT IN (SELECT "skuId" FROM seed_cart_skus);
INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price)
SELECT c.id, s."productId", s."skuId", 1, s."unitPrice"
FROM seed_cart c CROSS JOIN seed_cart_skus s
ON CONFLICT (cart_id, sku_id) DO UPDATE
SET product_id=excluded.product_id, quantity=excluded.quantity, price=excluded.price;
UPDATE carts SET updated_at=now() WHERE id=(SELECT id FROM seed_cart);
SELECT id FROM seed_cart;
COMMIT;
"@
$cartId = ([guid]$cartId).ToString()

$cart = Invoke-Database 'commerce_db' @"
SELECT json_build_object(
    'id',c.id,'status',c.status,
    'items',(SELECT json_agg(json_build_object(
        'id',i.id,'skuId',i.sku_id,'productId',i.product_id,
        'quantity',i.quantity,'unitPrice',i.price::text,
        'lineTotal',(i.price*i.quantity)::numeric(19,2)::text
    ) ORDER BY i.product_id,i.sku_id) FROM cart_items i WHERE i.cart_id=c.id),
    'subtotal',(SELECT sum(i.price*i.quantity)::numeric(19,2)::text FROM cart_items i WHERE i.cart_id=c.id),
    'currency','VND', 'updatedAt',to_char(c.updated_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
) FROM carts c WHERE c.id='$cartId' AND c.user_id='$buyerId' AND c.status='ACTIVE';
"@ | ConvertFrom-Json
if (@($cart.items).Count -ne 10 -or ($cart.items | Measure-Object quantity -Sum).Sum -ne 10) {
    throw 'Cart verification failed: expected 10 lines and 10 total units.'
}

$sellerRows = Invoke-Database 'user_db' 'SELECT coalesce(json_agg(x),''[]''::json) FROM (SELECT id, full_name FROM users WHERE status=''ACTIVE'' AND deleted_at IS NULL) x;' | ConvertFrom-Json
foreach ($item in $cart.items) {
    $sku = $skus | Where-Object { $_.skuId -eq $item.skuId } | Select-Object -First 1
    $seller = $sellerRows | Where-Object { $_.id -eq $sku.sellerId } | Select-Object -First 1
    $item | Add-Member productName $sku.productName
    $item | Add-Member sellerId $sku.sellerId
    if ($seller) { $item | Add-Member sellerName $seller.full_name }
    foreach ($field in @('color','size','imageUrl')) {
        if ($sku.$field) { $item | Add-Member $field $sku.$field }
    }
}

$fixtureJson = ConvertTo-Json -InputObject $cart -Depth 10
$fixtureSource = @"
// Generated from local PostgreSQL by be-project-nexus/infra/local-dev/seed-buyer-cart.ps1.
// Sprint 1 fixture: cart endpoints are still mocked until the commerce implementation lands.
import type { Cart } from "../types/cart/response/type";

export const buyerCartSeedUserId = "$buyerId";
export const buyerCartSeedEmail = "$buyerEmail";

export const buyerCartSeed: Cart = $fixtureJson;
"@
$resolvedFixturePath = [System.IO.Path]::GetFullPath($FixturePath)
if (-not (Test-Path -LiteralPath (Split-Path -Parent $resolvedFixturePath))) { throw 'Fixture directory does not exist.' }
[System.IO.File]::WriteAllText($resolvedFixturePath, $fixtureSource.Replace("`r`n", "`n") + "`n", $utf8)
Write-Host "Seeded $buyerEmail (user $buyerId): 10 lines, 10 units, subtotal $($cart.subtotal) VND."
Write-Host "ACTIVE cart: $cartId"
Write-Host "Exported fixture: $resolvedFixturePath"
