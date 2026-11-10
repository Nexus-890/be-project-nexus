<#
.SYNOPSIS
Start the local public Buyer Shop APIs and ensure their demo data is in PostgreSQL.
.DESCRIPTION
Reuses healthy IDE services. Starts only missing config/discovery/catalog/auction/gateway
processes, then seeds catalog/auction using fixed demo IDs. Never enables FE mocks,
replaces gateway discovery routes, deletes volumes or changes Keycloak permissions.
.EXAMPLE
pwsh -File ./start-buyer-shop.ps1
.EXAMPLE
pwsh -File ./start-buyer-shop.ps1 -CheckOnly -NoInfrastructure
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$SkipSeed,
    [switch]$IncludeCheckoutData,
    [switch]$NoInfrastructure,
    [switch]$CheckOnly,
    [ValidateRange(15,300)][int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$javaCommand = Get-Command java -ErrorAction Stop
$services = @(
    @{ Name='config-service'; Port=8888; Probe='/actuator/health' },
    @{ Name='discovery-service'; Port=8761; Probe='/actuator/health' },
    @{ Name='catalog-service'; Port=8082; Probe='/actuator/health' },
    @{ Name='auction-service'; Port=8084; Probe='/actuator/health' },
    # Gateway actuator is protected; probe its public catalog route instead.
    @{ Name='api-gateway'; Port=8080; Probe='/api/v1/products?page=0&size=1' }
)

function Test-LocalHttp([string]$Uri) {
    try {
        $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3 -SkipHttpErrorCheck
        return [int]$response.StatusCode -eq 200
    } catch { return $false }
}

function Wait-LocalHttp([string]$Uri, [string]$Description) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not (Test-LocalHttp $Uri)) {
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "$Description did not become ready: $Uri. Read its target/buyer-shop-start.*.log; existing services were not stopped."
        }
        Start-Sleep -Seconds 2
    }
}

function Start-LocalService($Service) {
    $probe = "http://localhost:$($Service.Port)$($Service.Probe)"
    if (Test-LocalHttp $probe) {
        Write-Output "$($Service.Name): reusing the running service on $($Service.Port)."
        return
    }
    if ($CheckOnly) { throw "$($Service.Name) is unavailable at $probe. Run this script without -CheckOnly to start missing services." }
    $listener = Get-NetTCPConnection -LocalPort $Service.Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        Write-Output "$($Service.Name): port $($Service.Port) is occupied; waiting for its health check."
        Wait-LocalHttp $probe $Service.Name
        return
    }
    $serviceRoot = Join-Path $backendRoot $Service.Name
    $target = Join-Path $serviceRoot 'target'
    $jar = Get-ChildItem -LiteralPath $target -Filter "$($Service.Name)-*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($Build -or -not $jar) {
        Push-Location $serviceRoot
        try {
            & (Join-Path $serviceRoot 'mvnw.cmd') '-DskipTests' package
            if ($LASTEXITCODE -ne 0) { throw "Build failed for $($Service.Name). Install shared libraries from be-project-nexus/lib first if dependencies are missing." }
        } finally { Pop-Location }
        $jar = Get-ChildItem -LiteralPath $target -Filter "$($Service.Name)-*.jar" |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if (-not $jar) { throw "No executable JAR found for $($Service.Name)." }
    # Execute a copy so Maven can repackage the original JAR on Windows.
    $runtimeRoot = Join-Path $target 'buyer-shop-runtime'
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    $runtimeJar = Join-Path $runtimeRoot $jar.Name
    Copy-Item -LiteralPath $jar.FullName -Destination $runtimeJar -Force
    $stdout = Join-Path $target 'buyer-shop-start.out.log'
    $stderr = Join-Path $target 'buyer-shop-start.err.log'
    $arguments = @('-Duser.timezone=Asia/Ho_Chi_Minh', '-jar', ('"'+$runtimeJar+'"'))
    # The browser entry point is local-only. Keep standard Eureka upstream routes.
    if ($Service.Name -eq 'api-gateway') { $arguments += '--server.address=127.0.0.1' }
    $process = Start-Process -FilePath $javaCommand.Source -ArgumentList $arguments -WorkingDirectory $serviceRoot `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    Write-Output "$($Service.Name): starting PID $($process.Id) on $($Service.Port)."
    Wait-LocalHttp $probe $Service.Name
}

if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env'))) {
    throw 'Create infra/local-dev/.env from .env.example and configure the local DB/infrastructure first.'
}
if (-not $NoInfrastructure -and -not $CheckOnly) {
    Push-Location $PSScriptRoot
    try {
        & docker compose up -d postgres redis kafka kafka-init zipkin auth-service
        if ($LASTEXITCODE -ne 0) { throw 'Infrastructure startup failed. Existing containers/volumes were preserved.' }
    } finally { Pop-Location }
}
foreach ($service in $services) { Start-LocalService $service }

if (-not $SkipSeed) {
    $domains = if ($IncludeCheckoutData) { @('catalog','inventory','commerce','auction') } else { @('catalog','auction') }
    $seedArguments = @{ Domains=$domains; WaitSeconds=$TimeoutSeconds }
    if ($CheckOnly) { $seedArguments.VerifyOnly=$true }
    & (Join-Path $PSScriptRoot 'seed-buyer-shop.ps1') @seedArguments
}

# Service health alone does not establish gateway discovery readiness.
$routes = @(
    '/api/v1/categories/tree',
    '/api/v1/products?page=0&size=10',
    '/api/v1/auctions?status=ACTIVE&page=0&size=5'
)
foreach ($route in $routes) {
    $uri = 'http://localhost:8080'+$route
    if ($CheckOnly -and -not (Test-LocalHttp $uri)) { throw "Gateway route unavailable: $uri" }
    Wait-LocalHttp $uri 'Gateway discovery route'
    $payload = Invoke-RestMethod -Uri $uri -TimeoutSec 8
    if ($payload.code -ne 0 -or $null -eq $payload.data) { throw "Gateway returned an invalid API envelope for $route" }
    if ($route.StartsWith('/api/v1/products') -and @($payload.data).Count -eq 0) { throw 'Catalog API is reachable but has no products. Run the seed without -SkipSeed.' }
    Write-Output ("Gateway OK: {0} ({1} records)" -f $route,@($payload.data).Count)
}
Write-Output 'Buyer Shop APIs and persistent PostgreSQL data are ready. Restart the FE dev server after env changes, then open http://localhost:3000.'
Write-Output 'Checkout/user/payment capabilities beyond the public Shop still require their own running services and delivered APIs.'
