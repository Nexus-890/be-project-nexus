param(
    [Parameter(Mandatory)][ValidateSet('config-service','discovery-service','api-gateway','user-service','catalog-service','commerce-service','frontend')][string]$Service,
    [string]$DatabaseName
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$demoDatabases = @{
    'user-service' = 'nexus_demo_user_db'
    'catalog-service' = 'nexus_demo_catalog_db'
    'commerce-service' = 'nexus_demo_commerce_db'
}
if ($demoDatabases.ContainsKey($Service) -and $DatabaseName -ne $demoDatabases[$Service]) {
    throw "Pass the dedicated demo database $($demoDatabases[$Service]) for $Service"
}
if (!$demoDatabases.ContainsKey($Service) -and $DatabaseName) { throw 'This service does not accept a database override' }
if ($Service -eq 'frontend') {
    Set-Location (Join-Path $repoRoot 'fe-project-nexus')
    & node node_modules/next/dist/bin/next dev --hostname 127.0.0.1 --port 3000
} else {
    Set-Location (Join-Path $repoRoot "be-project-nexus/$Service")
    if ($DatabaseName) {
        $demoEnvPath = Join-Path $PSScriptRoot '../local-dev/.env'
        $demoDbKey = @{ 'user-service'='USER_DB_NAME'; 'catalog-service'='CATALOG_DB_NAME'; 'commerce-service'='COMMERCE_DB_NAME' }[$Service]
        $demoConfiguredDb = $null
        foreach ($demoLine in Get-Content -LiteralPath $demoEnvPath) {
            if ($demoLine -match ('^' + $demoDbKey + '=(.*)$')) { $demoConfiguredDb = $Matches[1].Trim() }
        }
        if ($demoConfiguredDb -ne $DatabaseName) { throw "Set $demoDbKey=$DatabaseName in infra/local-dev/.env before using the demo launcher" }
    }
    & .\mvnw.cmd spring-boot:run
}
exit $LASTEXITCODE
