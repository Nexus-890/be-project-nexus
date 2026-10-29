param(
    [int]$Port = 4958,
    [string]$MavenCommand = 'mvn'
)

$ErrorActionPreference = 'Stop'
$beRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$simulationFile = Join-Path $PSScriptRoot 'catalog-envelope.sim.yaml'
$outputDirectory = Join-Path $PSScriptRoot 'target'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$requestLog = Join-Path $outputDirectory 'simulation.ndjson'
$errorLog = Join-Path $outputDirectory 'simulation.err'
$postmanExecutable = (Get-Command postman -ErrorAction Stop).Source
$mavenExecutable = (Get-Command $MavenCommand -ErrorAction Stop).Source
$originalSimulationUrl = $env:CATALOG_CONTRACT_SIMULATION_URL
$simulationProcess = $null

try {
    $simulationProcess = Start-Process -FilePath $postmanExecutable -ArgumentList @(
        'simulation', 'run', ('"' + $simulationFile + '"'),
        '--port', $Port, '--output', 'ndjson'
    ) -WorkingDirectory $beRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $requestLog -RedirectStandardError $errorLog

    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($simulationProcess.HasExited) {
            throw "Postman exited before listening: $(Get-Content $errorLog -Raw)"
        }
        if (Test-Path $requestLog) {
            $events = @(Get-Content $requestLog | ForEach-Object { $_ | ConvertFrom-Json })
            if ($events | Where-Object { $_.event -eq 'listening' -and $_.port -eq $Port }) {
                $ready = $true
                break
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "Postman did not start; see $errorLog" }

    $env:CATALOG_CONTRACT_SIMULATION_URL = "http://127.0.0.1:$Port/catalog"
    & $mavenExecutable -f (Join-Path $beRoot 'commerce-service/pom.xml') '-Dtest=CatalogEnvelopeHttpTest' test
    if ($LASTEXITCODE -ne 0) { throw "CatalogEnvelopeHttpTest failed (exit $LASTEXITCODE)." }

    $requests = @(Get-Content $requestLog | ForEach-Object { $_ | ConvertFrom-Json } |
            Where-Object { $_.event -eq 'request' -and $_.routeKey -eq 'catalog' })
    $expected = @{ '003' = 200; '004' = 404; '005' = 503; '006' = 200 }
    foreach ($suffix in $expected.Keys) {
        $path = '/api/v1/skus/c8000000-0000-4000-8000-000000000' + $suffix
        if (-not ($requests | Where-Object { $_.method -eq 'GET' -and $_.path -eq $path `
                    -and $_.statusCode -eq $expected[$suffix] })) {
            throw "Missing real HTTP request $path with status $($expected[$suffix]); see $requestLog"
        }
    }
    $productPath = '/api/v1/products/c8000000-0000-4000-8000-000000000002'
    if (-not ($requests | Where-Object { $_.method -eq 'GET' -and $_.path -eq $productPath -and $_.statusCode -eq 200 })) {
        throw "Missing real HTTP product request $productPath; see $requestLog"
    }
    Write-Host "Verified wrapped SKU/product, 404, 503 and missing-data paths over HTTP. Log: $requestLog"
}
finally {
    $env:CATALOG_CONTRACT_SIMULATION_URL = $originalSimulationUrl
    if ($simulationProcess -and -not $simulationProcess.HasExited) {
        # Kill only this runner's process tree, including the Postman mock child.
        $simulationProcess.Kill($true)
        $simulationProcess.WaitForExit(5000) | Out-Null
    }
}
