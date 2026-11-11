<#
.SYNOPSIS
Regression check of the actual guarded image correction using temporary tables.
No persistent metadata, images or storage objects are changed.
#>
[CmdletBinding()]
param([string]$Container = 'nexus-postgres', [string]$DatabaseUser = '', [string]$EnvFile = (Join-Path $PSScriptRoot '.env'))
$ErrorActionPreference = 'Stop'
if (-not $DatabaseUser) {
    $line = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^DB_USERNAME=' } | Select-Object -First 1
    if (-not $line) { throw 'Configure DB_USERNAME or supply -DatabaseUser.' }
    $DatabaseUser = $line.Substring('DB_USERNAME='.Length).Trim().Trim('"').Trim("'")
}
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'account-seller/catalog.sql') -Raw
$correction = [regex]::Match($source, '(?s)-- account-seller-image-correction:start\s*(.*?)-- account-seller-image-correction:end').Groups[1].Value
if (-not $correction.Trim()) { throw 'Image correction statement markers are missing.' }
$setup = @'
BEGIN;
CREATE TEMP TABLE product_images(id uuid PRIMARY KEY,product_id uuid,image_url text,sort_order int);
CREATE TEMP TABLE account_seller_images(image_id uuid,product_id uuid,image_url text,legacy_image_url text);
INSERT INTO pg_temp.account_seller_images VALUES
('93000000-0000-4000-8000-000000000401','93000000-0000-4000-8000-000000000201','own-product-one','borrowed-original'),
('93000000-0000-4000-8000-000000000402','93000000-0000-4000-8000-000000000202','own-product-two','borrowed-original');
INSERT INTO pg_temp.product_images VALUES
('93000000-0000-4000-8000-000000000401','93000000-0000-4000-8000-000000000201','borrowed-original',3),
('93000000-0000-4000-8000-000000000402','93000000-0000-4000-8000-000000000202','user-edited-url',7),
('69000000-0000-4000-8000-000000000304','69000000-0000-4000-8000-000000000204','borrowed-original',0);
'@
$assertions = @'
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_temp.product_images WHERE id='93000000-0000-4000-8000-000000000401' AND image_url='own-product-one' AND sort_order=3) THEN RAISE EXCEPTION 'Matching borrowed URL was not corrected or its sort changed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_temp.product_images WHERE id='93000000-0000-4000-8000-000000000402' AND image_url='user-edited-url' AND sort_order=7) THEN RAISE EXCEPTION 'User URL/sort was overwritten'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_temp.product_images WHERE id='69000000-0000-4000-8000-000000000304' AND image_url='borrowed-original' AND sort_order=0) THEN RAISE EXCEPTION 'Original product image was modified'; END IF;
END $$;
UPDATE pg_temp.product_images SET product_id='93000000-0000-4000-8000-000000000299',image_url='borrowed-original' WHERE id='93000000-0000-4000-8000-000000000402';
'@
$finalAssertions = @'
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_temp.product_images WHERE id='93000000-0000-4000-8000-000000000402' AND product_id='93000000-0000-4000-8000-000000000299' AND image_url='borrowed-original' AND sort_order=7) THEN RAISE EXCEPTION 'Mismatched product ownership was not preserved'; END IF;
END $$;
ROLLBACK;
SELECT 'PASS: exact borrowed URL corrected; URL edits, sort edits, original product and mismatched ownership preserved; transaction rolled back';
'@
$sql = $setup + "`n" + $correction + "`n" + $correction + "`n" + $assertions + "`n" + $correction + "`n" + $finalAssertions
$sql | & docker exec -i $Container psql -X -q -A -t -v ON_ERROR_STOP=1 -U $DatabaseUser -d catalog_db
if ($LASTEXITCODE -ne 0) { throw 'Image correction regression failed; no persistent metadata/storage was modified.' }
