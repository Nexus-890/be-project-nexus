param(
    [string]$BackendRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [Parameter(Mandatory = $true)][string]$WorkRoot,
    [Parameter(Mandatory = $true)][string]$EvidenceRoot,
    [string]$JavaHome = $env:JAVA_HOME
)

# Each existing class has @RepeatedTest(10). One fresh Maven invocation per class
# therefore performs ten races, rather than multiplying the suite to 100 races.
# Copy sources so this focused run never overwrites a concurrent coverage report.
$ErrorActionPreference = 'Stop'
if (-not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set -JavaHome (or JAVA_HOME) to an installed JDK 21.'
}
if (Test-Path $WorkRoot) { throw 'WorkRoot must be a new directory.' }
New-Item -ItemType Directory -Path $WorkRoot, $EvidenceRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $WorkRoot 'lib') | Out-Null
Copy-Item -LiteralPath (Join-Path $BackendRoot 'lib/pom.xml') -Destination (Join-Path $WorkRoot 'lib/pom.xml')
$suites = @(
    @{ Module = 'inventory-service'; Class = 'ReservationConcurrencyIT'; Package = 'com.group5.inventoryservice.reservation'; Marker = '^RESERVATION_CONCURRENCY '; Requests = 100 },
    @{ Module = 'auction-service'; Class = 'BidConcurrencyIT'; Package = 'com.group5.auctionservice.bidding'; Marker = '^BID_QUEUE '; Requests = 50 }
)
foreach ($suite in $suites) {
    $destination = Join-Path $WorkRoot $suite.Module
    New-Item -ItemType Directory -Path $destination | Out-Null
    foreach ($entry in @('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src')) {
        $source = Join-Path (Join-Path $BackendRoot $suite.Module) $entry
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $destination -Recurse }
    }
}
Get-ChildItem -LiteralPath $WorkRoot -Recurse -File | ForEach-Object {
    [pscustomobject]@{ Path = $_.FullName.Substring($WorkRoot.Length + 1); SHA256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
} | Export-Csv -LiteralPath (Join-Path $EvidenceRoot 'source-manifest.csv') -NoTypeInformation -Encoding UTF8

$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
# Apply the same bounds to Maven and its test fork. Do not replace JaCoCo argLine.
$env:JAVA_TOOL_OPTIONS = '-Xmx384m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC'
$results = @()
foreach ($suite in $suites) {
    $serviceRoot = Join-Path $WorkRoot $suite.Module
    $log = Join-Path $EvidenceRoot ($suite.Module + '.log')
    $reportRoot = Join-Path $serviceRoot 'target/failsafe-reports'
    $arguments = @('-B', '-Dstyle.color=never', '-Duser.timezone=UTC', ('-Dit.test=' + $suite.Class), 'test-compile', 'failsafe:integration-test', 'failsafe:verify')
    $started = [DateTimeOffset]::UtcNow
    ('Command: .\mvnw.cmd ' + ($arguments -join ' ')) | Set-Content -LiteralPath $log -Encoding UTF8
    Push-Location $serviceRoot
    try {
        # Keep recording Maven's real exit code even if it writes to stderr.
        $ErrorActionPreference = 'Continue'
        & '.\mvnw.cmd' @arguments 2>&1 | Out-File -LiteralPath $log -Append -Encoding UTF8
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    $finished = [DateTimeOffset]::UtcNow
    $xmlPath = Join-Path $reportRoot ('TEST-' + $suite.Package + '.' + $suite.Class + '.xml')
    $tests = 0; $failures = 0; $errors = 0; $skips = 0
    if (Test-Path -LiteralPath $xmlPath) {
        [xml]$report = Get-Content -LiteralPath $xmlPath -Raw
        $tests = [int]$report.testsuite.tests
        $failures = [int]$report.testsuite.failures
        $errors = [int]$report.testsuite.errors
        $skips = [int]$report.testsuite.skipped
    }
    $markers = @(Get-Content -LiteralPath $log | Where-Object { $_ -match $suite.Marker })
    $markers | Set-Content -LiteralPath (Join-Path $EvidenceRoot ($suite.Module + '-repetitions.txt')) -Encoding UTF8
    if (Test-Path -LiteralPath $reportRoot) {
        Copy-Item -LiteralPath $reportRoot -Destination (Join-Path $EvidenceRoot ($suite.Module + '-failsafe-reports')) -Recurse
    }
    $result = [pscustomobject]@{
        Module = $suite.Module; Test = $suite.Class; StartedUtc = $started.ToString('o'); FinishedUtc = $finished.ToString('o')
        DurationSeconds = [Math]::Round(($finished - $started).TotalSeconds, 2).ToString([Globalization.CultureInfo]::InvariantCulture); ExitCode = $exitCode
        Tests = $tests; Failures = $failures; Errors = $errors; Skipped = $skips; RecordedRaces = $markers.Count
        RequestsPerRace = $suite.Requests
        Passed = ($exitCode -eq 0 -and $tests -eq 10 -and $failures -eq 0 -and $errors -eq 0 -and $skips -eq 0 -and $markers.Count -eq 10)
    }
    $results += $result
    $results | Export-Csv -LiteralPath (Join-Path $EvidenceRoot 'summary.csv') -NoTypeInformation -Encoding UTF8
    $result | ConvertTo-Json -Compress | Write-Output
}
if (@($results | Where-Object { -not $_.Passed }).Count) { exit 1 }
