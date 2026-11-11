<#
.SYNOPSIS
Checks the actual seed cart statement using transaction-local temporary tables.
No persistent cart or order is modified, even when an assertion fails.
#>
[CmdletBinding()]
param([string]$Container = 'nexus-postgres', [string]$DatabaseUser = '', [string]$EnvFile = (Join-Path $PSScriptRoot '.env'))
$ErrorActionPreference = 'Stop'
if (-not $DatabaseUser) {
    $line = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^DB_USERNAME=' } | Select-Object -First 1
    if (-not $line) { throw 'Configure DB_USERNAME or supply -DatabaseUser.' }
    $DatabaseUser = $line.Substring('DB_USERNAME='.Length).Trim().Trim('"').Trim("'")
}
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'account-seller/commerce.sql') -Raw
$cartSql = [regex]::Match($source, '(?s)-- account-seller-cart:start\s*(.*?)-- account-seller-cart:end').Groups[1].Value
if (-not $cartSql.Trim()) { throw 'Cart seed statement markers are missing.' }
$setup = @'
BEGIN;
CREATE TEMP TABLE carts(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid,status text);
CREATE UNIQUE INDEX ON carts(user_id) WHERE status='ACTIVE';
CREATE TEMP TABLE cart_items(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),cart_id uuid,product_id uuid,sku_id uuid,quantity int,price numeric(19,2));
CREATE TEMP TABLE account_seller_snapshot(product_id uuid,sku_id uuid,price numeric(19,2));
INSERT INTO account_seller_snapshot VALUES ('93000000-0000-4000-8000-000000000201','93000000-0000-4000-8000-000000000301',160000);
INSERT INTO pg_temp.carts(user_id,status) VALUES ('68000000-0000-4000-8000-000000000003','ACTIVE');
'@
$emptyAssertion = @'
DO $$ BEGIN
 IF (SELECT count(*) FROM pg_temp.carts)<>1 OR (SELECT count(*) FROM pg_temp.cart_items)<>0 THEN RAISE EXCEPTION 'Existing empty cart was changed'; END IF;
END $$;
INSERT INTO pg_temp.cart_items(cart_id,product_id,sku_id,quantity,price) SELECT c.id,s.product_id,s.sku_id,17,12345 FROM pg_temp.carts c CROSS JOIN pg_temp.account_seller_snapshot s;
'@
$nonemptyAssertion = @'
DO $$ BEGIN
 IF (SELECT count(*) FROM pg_temp.cart_items)<>1 OR (SELECT quantity FROM pg_temp.cart_items)<>17 OR (SELECT price FROM pg_temp.cart_items)<>12345 THEN RAISE EXCEPTION 'Existing cart values were changed'; END IF;
END $$;
UPDATE pg_temp.carts SET status='EXPIRED';
'@
$newAssertion = @'
DO $$ BEGIN
 IF (SELECT count(*) FROM pg_temp.carts)<>2 OR (SELECT count(*) FROM pg_temp.cart_items)<>2 OR NOT EXISTS(SELECT 1 FROM pg_temp.carts c JOIN pg_temp.cart_items i ON i.cart_id=c.id WHERE c.status='ACTIVE' AND i.quantity=1 AND i.price=160000) THEN RAISE EXCEPTION 'New cart was not seeded from the snapshot'; END IF;
END $$;
'@
$sql = $setup + "`n" + $cartSql + "`n" + $emptyAssertion + "`n" + $cartSql + "`n" + $nonemptyAssertion + "`n" + $cartSql + "`n" + $newAssertion + "`n" + $cartSql + "`n" + $newAssertion + "`nROLLBACK;"
$sql | & docker exec -i $Container psql -X -q -v ON_ERROR_STOP=1 -U $DatabaseUser -d commerce_db
if ($LASTEXITCODE -ne 0) { throw 'Temporary cart preservation checks failed. The connection rolled back; persistent tables were not targeted.' }
Write-Output 'PASS: existing empty/nonempty carts retained; expired cart retained; new cart seeded; rerun unchanged. Temporary test transaction rolled back.'
