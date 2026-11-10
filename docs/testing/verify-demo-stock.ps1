<#
.SYNOPSIS
Verify #133 in temporary databases on a disposable PostgreSQL 18 server.
#>
param(
    [Parameter(Mandatory)][ValidateRange(1, 65535)][int]$Port,
    [string]$HostName = '127.0.0.1',
    [string]$DatabaseUser = 'postgres',
    [string]$PsqlPath = 'psql'
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$backend = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$seedScript = Join-Path $backend 'infra/local-dev/seed-demo-stock.ps1'
$prefix = 'nexus133_' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$databases = [System.Collections.Generic.List[string]]::new()
$jobs = @()
$checks = 0

function Connection([string]$Database) {
    return "host=$HostName port=$Port user=$DatabaseUser dbname=$Database"
}
function Sql([string]$Database, [string]$Statement) {
    $output = $Statement | & $PsqlPath -X -q -A -t -v ON_ERROR_STOP=1 -d (Connection $Database)
    if ($LASTEXITCODE -ne 0) { throw "Verification SQL failed in $Database." }
    return ($output -join "`n").Trim()
}
function Check([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw "FAIL: $Message" }
    $script:checks++
    Write-Host "PASS: $Message"
}
function New-Database([string]$Suffix, [string]$Service) {
    $database = $prefix + '_' + $Suffix
    # The identifier is generated locally, never supplied by the caller.
    Sql 'postgres' "CREATE DATABASE $database" | Out-Null
    $databases.Add($database)
    $migrationPath = Join-Path $backend "$Service/src/main/resources/db/migration"
    $migrations = Get-ChildItem -LiteralPath $migrationPath -Filter 'V*__*.sql' | Sort-Object {
        $number = [regex]::Match($_.Name, '^V(\d+(?:_\d+)*)__').Groups[1].Value.Replace('_', '.')
        if (!$number.Contains('.')) { $number += '.0' }
        [version]$number
    }
    foreach ($migration in $migrations) {
        # Each file commits separately, as needed by the V6 enum additions.
        $output = & $PsqlPath -X -q -v ON_ERROR_STOP=1 -1 -d (Connection $database) -f $migration.FullName
        if ($LASTEXITCODE -ne 0) { throw "Migration $($migration.Name) failed in $database." }
    }
    return $database
}
function Seed([string]$Catalog, [string]$Inventory) {
    & $seedScript -PsqlPath $PsqlPath -CatalogConnection (Connection $Catalog) -InventoryConnection (Connection $Inventory)
}
function StockSnapshot([string]$Database) {
    Sql $Database "SELECT jsonb_build_object('balances',(SELECT jsonb_agg(to_jsonb(b) ORDER BY id) FROM inventory_balances b),'movements',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM inventory_movements m))::text"
}
function Check-Seed([string]$Catalog, [string]$Inventory) {
    $expected = Sql $Catalog 'SELECT jsonb_agg(id::text ORDER BY id)::text FROM skus'
    $actual = Sql $Inventory "SELECT jsonb_agg(sku_id::text ORDER BY sku_id)::text FROM inventory_balances WHERE warehouse_id=(SELECT id FROM warehouses WHERE code='WH_001')"
    Check ($actual -eq $expected) 'every real catalogue UUID has exactly one WH_001 balance'
    Check ((Sql $Inventory 'SELECT count(*) FROM inventory_balances WHERE total_quantity=20 AND available_quantity=20 AND reserved_quantity=0 AND unavailable_quantity=0') -eq '10') 'all ten balances start total=available=20 and reserved=unavailable=0'
    Check ((Sql $Inventory 'SELECT count(*) FROM inventory_balances WHERE total_quantity<>available_quantity+reserved_quantity+unavailable_quantity OR LEAST(total_quantity,available_quantity,reserved_quantity,unavailable_quantity)<0') -eq '0') 'all seeded rows satisfy the stock CHECK constraints'
    Check ((Sql $Inventory "SELECT count(*) FROM inventory_balances b WHERE (SELECT count(*) FROM inventory_movements m WHERE m.sku_id=b.sku_id AND m.warehouse_id=b.warehouse_id AND m.movement_type='INTAKE' AND m.quantity=20 AND m.reference_id=b.id)=1") -eq '10') 'each seeded balance has exactly one matching INTAKE of 20'
    Check ((Sql $Inventory 'SELECT count(*) FROM inventory_movements') -eq '10') 'there are no extra movements'
}

try {
    Check ((Sql 'postgres' "SELECT current_setting('server_version_num')::integer >= 180000") -eq 't') 'PostgreSQL 18 or newer supports the real uuidv7 migrations'
    $catalogA = New-Database 'cat_a' 'catalog-service'
    $catalogB = New-Database 'cat_b' 'catalog-service'
    $inventoryA = New-Database 'inv_a' 'inventory-service'
    $inventoryB = New-Database 'inv_b' 'inventory-service'
    $guardInventory = New-Database 'inv_guard' 'inventory-service'
    $idsA = Sql $catalogA 'SELECT jsonb_agg(id::text ORDER BY id)::text FROM skus'
    $idsB = Sql $catalogB 'SELECT jsonb_agg(id::text ORDER BY id)::text FROM skus'
    Check ($idsA -ne $idsB) 'independently migrated catalogues have different V4 UUIDs'
    $result = Seed $catalogA $inventoryA
    Check ($result.newBalances -eq 10 -and $result.newMovements -eq 10) 'fresh seed creates ten balances and ten intakes'
    Check-Seed $catalogA $inventoryA
    $before = StockSnapshot $inventoryA
    $result = Seed $catalogA $inventoryA
    Check ($result.newBalances -eq 0 -and $result.newMovements -eq 0) 'restarting the seed process adds zero rows'
    Check ((StockSnapshot $inventoryA) -eq $before) 'rerun preserves balance IDs, timestamps, versions, actors and ledger'

    # A constraint error must be raised, not just an invariant checked in a SELECT.
    foreach ($assignment in @('total_quantity=-1', 'available_quantity=-1', 'reserved_quantity=-1', 'unavailable_quantity=-1', 'available_quantity=21')) {
        Sql $inventoryA ('DO $$ BEGIN BEGIN UPDATE inventory_balances SET ' + $assignment + '; RAISE EXCEPTION ''stock CHECK was not enforced''; EXCEPTION WHEN check_violation THEN NULL; END; END $$;') | Out-Null
    }
    Check ((StockSnapshot $inventoryA) -eq $before) 'all five invalid quantity mutations are rejected by PostgreSQL CHECKs'
    Sql $inventoryA "UPDATE inventory_balances SET available_quantity=17,reserved_quantity=3 WHERE sku_id=(SELECT sku_id FROM inventory_balances ORDER BY sku_id LIMIT 1)" | Out-Null
    $consumed = StockSnapshot $inventoryA
    $result = Seed $catalogA $inventoryA
    Check ($result.newBalances -eq 0 -and (StockSnapshot $inventoryA) -eq $consumed) 'rerun never refills reserved or consumed stock'

    # Use two processes so the unique-key race runs against a real database.
    foreach ($index in 1..2) {
        $jobs += Start-Job -ScriptBlock {
            param($Script, $Psql, $Catalog, $Inventory)
            & $Script -PsqlPath $Psql -CatalogConnection $Catalog -InventoryConnection $Inventory
        } -ArgumentList $seedScript, $PsqlPath, (Connection $catalogB), (Connection $inventoryB)
    }
    $null = $jobs | Wait-Job -Timeout 45
    foreach ($job in $jobs) { Check ($job.State -eq 'Completed') 'concurrent seed process completed' }
    $results = @($jobs | Receive-Job -ErrorAction Stop)
    Check (($results | Measure-Object newBalances -Sum).Sum -eq 10 -and ($results | Measure-Object newMovements -Sum).Sum -eq 10) 'concurrent seeds create only ten balances/intakes in total'
    Check-Seed $catalogB $inventoryB

    Sql $guardInventory 'CREATE FUNCTION fail_seed_movement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION ''forced ledger failure''; END $$; CREATE TRIGGER fail_seed_movement BEFORE INSERT ON inventory_movements FOR EACH ROW EXECUTE FUNCTION fail_seed_movement();' | Out-Null
    $failed = $false
    try { Seed $catalogA $guardInventory | Out-Null } catch { $failed = $true }
    Check $failed 'a failed ledger insert makes the seed fail'
    Check ((Sql $guardInventory 'SELECT (SELECT count(*) FROM inventory_balances)+(SELECT count(*) FROM inventory_movements)') -eq '0') 'ledger error rolls back every newly inserted balance and movement'
    Sql $guardInventory 'DROP TRIGGER fail_seed_movement ON inventory_movements; DROP FUNCTION fail_seed_movement();' | Out-Null

    $firstSku = (ConvertFrom-Json -InputObject $idsA)[0]
    $otherSku = [guid]::NewGuid().ToString()
    Sql $guardInventory "INSERT INTO inventory_balances (sku_id,warehouse_id,total_quantity,available_quantity) SELECT '$firstSku',id,7,7 FROM warehouses WHERE code='WH_001'; INSERT INTO inventory_movements (sku_id,warehouse_id,movement_type,quantity,reference_id) SELECT sku_id,warehouse_id,'INTAKE',7,id FROM inventory_balances; INSERT INTO inventory_balances (sku_id,warehouse_id,total_quantity,available_quantity) SELECT '$otherSku',id,9,9 FROM warehouses WHERE code='WH_001'" | Out-Null
    $result = Seed $catalogA $guardInventory
    Check ($result.newBalances -eq 9 -and $result.newMovements -eq 9) 'an existing demo balance is skipped and nine missing balances are seeded'
    Check ((Sql $guardInventory "SELECT available_quantity FROM inventory_balances WHERE sku_id='$firstSku'") -eq '7') 'pre-existing demo quantity is unchanged'
    Check ((Sql $guardInventory "SELECT available_quantity FROM inventory_balances WHERE sku_id='$otherSku'") -eq '9') 'unrelated SKU quantity is unchanged'
    Check ((Sql $guardInventory "SELECT count(*) FROM inventory_movements WHERE sku_id='$firstSku' AND quantity=7") -eq '1') 'existing demo intake is preserved without another intake'

    Sql $catalogA "DELETE FROM skus WHERE id='$firstSku'" | Out-Null
    $before = StockSnapshot $guardInventory
    $failed = $false
    try { Seed $catalogA $guardInventory | Out-Null } catch { $failed = $true }
    Check ($failed -and (StockSnapshot $guardInventory) -eq $before) 'missing catalogue variant aborts before any inventory write'

    Sql $guardInventory "UPDATE warehouses SET status='INACTIVE' WHERE code='WH_001'" | Out-Null
    $before = StockSnapshot $guardInventory
    $failed = $false
    try { Seed $catalogB $guardInventory | Out-Null } catch { $failed = $true }
    Check ($failed -and (StockSnapshot $guardInventory) -eq $before) 'inactive warehouse rejects seed without changes'
    $missingInventory = New-Database 'inv_missing' 'inventory-service'
    Sql $missingInventory "DELETE FROM warehouses WHERE code='WH_001'" | Out-Null
    $failed = $false
    try { Seed $catalogB $missingInventory | Out-Null } catch { $failed = $true }
    Check ($failed -and (Sql $missingInventory 'SELECT count(*) FROM inventory_balances') -eq '0') 'missing warehouse rejects seed without creating balances'
    Write-Host "PASS: $checks checks against real PostgreSQL; temporary databases will be removed."
} finally {
    foreach ($job in $jobs) { if ($job.State -eq 'Running') { Stop-Job $job }; Remove-Job $job -Force }
    foreach ($database in $databases) {
        Sql 'postgres' "DROP DATABASE IF EXISTS $database WITH (FORCE)" | Out-Null
    }
}
