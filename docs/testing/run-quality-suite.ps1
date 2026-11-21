param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$EvidenceDirectory = (Join-Path $PSScriptRoot '../../target/quality-evidence'),
    [string[]]$Modules = @(
        'config-service', 'discovery-service', 'api-gateway', 'user-service',
        'catalog-service', 'inventory-service', 'shipment-service', 'rating-service',
        'payment-service', 'notification-service', 'auction-service', 'commerce-service',
        'lib/common-core', 'lib/common-web', 'lib/common-security', 'lib/common-events',
        'lib/common-outbox', 'lib/common-test', 'infra/db-verification',
        'infra/receipt-completion-acceptance'
    )
)

# Receipt acceptance also needs the four plain service artifacts described in its README.
# Full clean verifies; failures remain failures. Bootstrap shared dependencies with
# lib/mvnw install first. The separate report goal salvages coverage after an
# assertion failure; it does not change the verify exit code or rerun tests.
$ErrorActionPreference = 'Stop'
$backend = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null
$tempDirectory = Join-Path $EvidenceDirectory 'tmp'
New-Item -ItemType Directory -Force $tempDirectory | Out-Null
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome pointing to JDK 21.'
}
$env:JAVA_HOME = $JavaHome
$env:JAVA_TOOL_OPTIONS = "-Xmx384m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC -Dspring.test.context.cache.maxSize=2 -Djava.io.tmpdir=$tempDirectory"
# Preserve caller repository/proxy settings while applying the resource bounds.
$env:MAVEN_OPTS = ($env:MAVEN_OPTS + ' ' + $env:JAVA_TOOL_OPTIONS).Trim()
$results = @()
foreach ($module in $Modules) {
    $directory = Join-Path $backend $module
    $name = $module.Replace('/', '-')
    $output = Join-Path $EvidenceDirectory $name
    New-Item -ItemType Directory -Force $output | Out-Null
    $wrapper = if (Test-Path (Join-Path $directory 'mvnw.cmd')) {
        Join-Path $directory 'mvnw.cmd'
    } else { Join-Path $backend 'lib/mvnw.cmd' }
    $arguments = @('-B', '-Dstyle.color=never', '-Duser.timezone=UTC', 'clean', 'verify')
    $started = [DateTimeOffset]::UtcNow
    @{ module = $module; startedUtc = $started; state = 'running' } |
        ConvertTo-Json | Set-Content (Join-Path $EvidenceDirectory 'progress.json') -Encoding UTF8
    Push-Location $directory
    try {
        # Windows PowerShell redirects native stderr as ErrorRecords.
        $ErrorActionPreference = 'Continue'
        & $wrapper @arguments *> (Join-Path $output 'verify.log')
        $verifyExit = $LASTEXITCODE
        if (Test-Path 'target/jacoco.exec') {
            & $wrapper '-B' '-Dstyle.color=never' 'jacoco:report' *> (Join-Path $output 'report.log')
        }
        $ErrorActionPreference = 'Stop'
        $tests = 0; $failures = 0; $errors = 0; $skipped = 0
        $details = @()
        foreach ($reportDirectory in @('target/surefire-reports', 'target/failsafe-reports')) {
            if (-not (Test-Path $reportDirectory)) { continue }
            Copy-Item $reportDirectory $output -Recurse -Force
            foreach ($file in Get-ChildItem $reportDirectory -Filter 'TEST-*.xml') {
                [xml]$document = Get-Content $file.FullName -Raw
                $suite = $document.testsuite
                $tests += [int]$suite.tests; $failures += [int]$suite.failures
                $errors += [int]$suite.errors; $skipped += [int]$suite.skipped
                foreach ($case in $suite.testcase) {
                    foreach ($failure in @($case.failure) + @($case.error)) {
                        if ($failure) {
                            $details += @{ test = "$($case.classname).$($case.name)"; message = [string]$failure.message }
                        }
                    }
                }
            }
        }
        $covered = $null; $missed = $null; $linePercent = $null
        $coverageFile = 'target/site/jacoco/jacoco.xml'
        if (Test-Path $coverageFile) {
            Copy-Item 'target/site/jacoco' $output -Recurse -Force
            [xml]$coverage = Get-Content $coverageFile -Raw
            $line = $coverage.report.counter | Where-Object { $_.type -eq 'LINE' }
            if ($line) {
                $covered = [int]$line.covered; $missed = [int]$line.missed
                if (($covered + $missed) -gt 0) { $linePercent = 100.0 * $covered / ($covered + $missed) }
            }
        } elseif ((Get-Content (Join-Path $output 'verify.log') -Raw) -match 'Coverage data missing for') {
            # Zero tests and no agent data mean zero executed production lines.
            $linePercent = 0.0
        }
        $result = [ordered]@{
            module = $module; startedUtc = $started; finishedUtc = [DateTimeOffset]::UtcNow
            command = "$wrapper $($arguments -join ' ')"; verifyExit = $verifyExit
            tests = $tests; passed = ($tests - $failures - $errors - $skipped)
            failures = $failures; errors = $errors; skipped = $skipped
            coveredLines = $covered; missedLines = $missed; linePercent = $linePercent
            below70 = if ($null -eq $linePercent) { $null } else { $linePercent -lt 70.0 }
            failureDetails = $details
            jarFiles = @(Get-ChildItem 'target' -Filter '*.jar' -ErrorAction SilentlyContinue | ForEach-Object FullName)
        }
        $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $output 'result.json') -Encoding UTF8
        $results += $result
        $results | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceDirectory 'results.json') -Encoding UTF8
        Write-Output "$module verify=$verifyExit passed=$($result.passed)/$tests fail=$failures error=$errors skip=$skipped lines=$linePercent"
    } finally {
        Pop-Location
    }
}
@{ state = 'complete'; finishedUtc = [DateTimeOffset]::UtcNow } |
    ConvertTo-Json | Set-Content (Join-Path $EvidenceDirectory 'progress.json') -Encoding UTF8
if (@($results | Where-Object { $_.verifyExit -ne 0 }).Count -gt 0) { exit 1 }
