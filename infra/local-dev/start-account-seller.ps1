<#
.SYNOPSIS
Opt-in local Buyer Account / Seller Portal backend startup; retains existing services and volumes.
.DESCRIPTION
Builds from this checkout when requested or when a JAR is missing, starts Config and
Discovery before business services, then checks actual public gateway operations.
This does not provision credentials, seed data, or claim private business APIs passed.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$CheckOnly,
    [ValidateRange(15,300)][int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java = (Get-Command java -ErrorAction Stop).Source
$services = @(
    @{Name='config-service'; Port=8888},
    @{Name='discovery-service'; Port=8761},
    @{Name='user-service'; Port=8081},
    @{Name='catalog-service'; Port=8082},
    @{Name='commerce-service'; Port=8083},
    @{Name='inventory-service'; Port=8085},
    @{Name='auction-service'; Port=8084},
    @{Name='notification-service'; Port=8086},
    @{Name='api-gateway'; Port=8080}
)
function Test-AccountHttp([string]$Uri) {
    try { return [int](Invoke-WebRequest -Uri $Uri -TimeoutSec 4 -SkipHttpErrorCheck).StatusCode -eq 200 }
    catch { return $false }
}
function Wait-AccountHttp([string]$Uri) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not (Test-AccountHttp $Uri)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw "Not ready: $Uri. Inspect target/account-start.*.log. Existing services were retained." }
        Start-Sleep -Seconds 2
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env'))) { throw 'Configure infra/local-dev/.env first; see .env.example. No credential setup is performed by this helper.' }
foreach ($service in $services) {
    $probe = if ($service.Name -eq 'api-gateway') { "http://localhost:8080/api/v1/products?page=0&size=1" } else { "http://localhost:$($service.Port)/actuator/health" }
    if (Test-AccountHttp $probe) { Write-Output "$($service.Name): reused on $($service.Port)"; continue }
    if ($CheckOnly) { throw "Unavailable: $probe" }
    if (Get-NetTCPConnection -LocalPort $service.Port -State Listen -ErrorAction SilentlyContinue) {
        Wait-AccountHttp $probe
        Write-Output "$($service.Name): existing listener became ready"
        continue
    }
    $serviceRoot = Join-Path $backendRoot $service.Name
    $jar = Get-ChildItem -LiteralPath (Join-Path $serviceRoot 'target') -Filter "$($service.Name)-*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($Build -or -not $jar) {
        Push-Location $serviceRoot
        try {
            & (Join-Path $serviceRoot 'mvnw.cmd') package '-DskipTests'
            if ($LASTEXITCODE -ne 0) { throw "Package failed: $($service.Name). Install shared lib modules first. This is not Maven verify." }
        } finally { Pop-Location }
        $jar = Get-ChildItem -LiteralPath (Join-Path $serviceRoot 'target') -Filter "$($service.Name)-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    # Run a copy so Maven can repackage the source JAR while Java is running on Windows.
    $runtimeRoot = Join-Path $serviceRoot 'target/account-seller-runtime'
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    $runtimeJar = Join-Path $runtimeRoot $jar.Name
    Copy-Item -LiteralPath $jar.FullName -Destination $runtimeJar -Force
    $arguments = @('-Xms64m','-Xmx384m','-Duser.timezone=Asia/Ho_Chi_Minh','-jar',('"'+$runtimeJar+'"'))
    # Eureka advertises the host interface; services must accept that interface.
    # Retain discovery-based upstreams and restrict only the browser gateway entry.
    if ($service.Name -eq 'api-gateway') { $arguments += '--server.address=127.0.0.1' }
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $serviceRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $serviceRoot 'target/account-start.out.log') -RedirectStandardError (Join-Path $serviceRoot 'target/account-start.err.log') -PassThru
    Write-Output "$($service.Name): started PID $($process.Id)"
    Wait-AccountHttp $probe
}
foreach ($route in @('/api/v1/categories/tree','/api/v1/products?page=0&size=1','/api/v1/inventory?skuIds=69000000-0000-4000-8000-000000000301')) {
    $uri = 'http://localhost:8080'+$route
    Wait-AccountHttp $uri
    $body = Invoke-RestMethod -Uri $uri -TimeoutSec 8
    if ($body.code -ne 0 -or $null -eq $body.data) { throw "Invalid gateway success envelope: $route" }
    Write-Output "Public business operation passed: GET $route (HTTP 200, application code 0)"
}
Write-Output 'Local services are running. Seed with seed-account-seller.ps1. Private buyer/seller operations still require real browser sign-in and ownership checks.'
