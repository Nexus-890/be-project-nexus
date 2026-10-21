[CmdletBinding()]
param(
    [int]$KafkaPort = 0,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-DockerExecReady {
    param([string[]]$Arguments)

    & docker @Arguments *> $null
    return $LASTEXITCODE -eq 0
}

if ($KafkaPort -eq 0) {
    $KafkaPort = if ([string]::IsNullOrWhiteSpace($env:OUTBOX_TEST_KAFKA_PORT)) {
        Get-Random -Minimum 20000 -Maximum 30000
    } else {
        [int]$env:OUTBOX_TEST_KAFKA_PORT
    }
}
if ($KafkaPort -lt 1 -or $KafkaPort -gt 65535) {
    throw 'KafkaPort must be between 1 and 65535.'
}

$scriptDir = Split-Path -Parent $PSCommandPath
$libDir = (Resolve-Path (Join-Path $scriptDir '..')).Path
$mavenWrapper = Join-Path $libDir 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "Cannot find Maven wrapper at $mavenWrapper."
}

$prefix = "nexus-outbox-test-$PID-$(Get-Random -Minimum 10000 -Maximum 99999)"
$postgres = "$prefix-postgres"
$kafka = "$prefix-kafka"
$envNames = @(
    'OUTBOX_TEST_DB_USERNAME',
    'OUTBOX_TEST_DB_PASSWORD',
    'OUTBOX_TEST_JDBC_URL',
    'OUTBOX_TEST_KAFKA_BOOTSTRAP_SERVERS',
    'OUTBOX_TEST_KAFKA_CONTAINER'
)
$oldEnv = @{}
foreach ($name in $envNames) {
    $oldEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker daemon is unavailable. Start Docker Desktop with Linux containers.'
    }

    $env:OUTBOX_TEST_DB_USERNAME = 'postgres'
    $env:OUTBOX_TEST_DB_PASSWORD = [guid]::NewGuid().ToString()

    Write-Host '[outbox] Starting disposable PostgreSQL 18...'
    & docker run -d --name $postgres `
        -e 'POSTGRES_DB=outbox_test' `
        -e "POSTGRES_USER=$env:OUTBOX_TEST_DB_USERNAME" `
        -e "POSTGRES_PASSWORD=$env:OUTBOX_TEST_DB_PASSWORD" `
        -p '127.0.0.1::5432' pgvector/pgvector:pg18 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to start PostgreSQL test container.' }

    Write-Host '[outbox] Starting disposable Kafka 4.3.1...'
    $kafkaArgs = @(
        'run', '-d', '--name', $kafka,
        '-p', "127.0.0.1:${KafkaPort}:9094",
        '-e', 'KAFKA_NODE_ID=1',
        '-e', 'KAFKA_PROCESS_ROLES=broker,controller',
        '-e', 'KAFKA_LISTENERS=INTERNAL://:9092,EXTERNAL://:9094,CONTROLLER://:9093',
        '-e', "KAFKA_ADVERTISED_LISTENERS=INTERNAL://localhost:9092,EXTERNAL://127.0.0.1:${KafkaPort}",
        '-e', 'KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER',
        '-e', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
        '-e', 'KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093',
        '-e', 'KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL',
        '-e', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1',
        '-e', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1',
        '-e', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1',
        '-e', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0',
        '-e', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE=false',
        'apache/kafka:4.3.1'
    )
    & docker @kafkaArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to start Kafka test container.' }

    Write-Host '[outbox] Waiting for PostgreSQL and Kafka to become ready...'
    $ready = $false
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $postgresReady = Test-DockerExecReady -Arguments @('exec', $postgres, 'pg_isready', '-U', 'postgres', '-d', 'outbox_test')
        $kafkaReady = $false
        if ($postgresReady) {
            $kafkaReady = Test-DockerExecReady -Arguments @(
                'exec', $kafka, '/opt/kafka/bin/kafka-topics.sh',
                '--bootstrap-server', 'localhost:9092', '--list'
            )
        }
        if ($postgresReady -and $kafkaReady) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        & docker logs $postgres
        & docker logs $kafka
        throw 'Disposable PostgreSQL/Kafka did not become ready.'
    }

    $portOutput = & docker port $postgres '5432/tcp'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine PostgreSQL host port.' }
    $portMatch = [regex]::Match(($portOutput | Select-Object -First 1), ':(\d+)\s*$')
    if (-not $portMatch.Success) { throw "Unexpected docker port output: $portOutput" }
    $postgresPort = $portMatch.Groups[1].Value

    $env:OUTBOX_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/outbox_test"
    $env:OUTBOX_TEST_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:$KafkaPort"
    $env:OUTBOX_TEST_KAFKA_CONTAINER = $kafka

    Write-Host "[outbox] PostgreSQL on localhost:$postgresPort, Kafka on localhost:$KafkaPort"
    Write-Host '[outbox] Running common-outbox integration tests with Maven Failsafe...'
    Push-Location $libDir
    try {
        & $mavenWrapper -pl common-outbox -am -Poutbox-integration verify @MavenArgs
        if ($LASTEXITCODE -ne 0) { throw "Maven verify failed (exit code $LASTEXITCODE)." }
    }
    finally {
        Pop-Location
    }
    Write-Host '[outbox] BUILD SUCCESS.'
}
finally {
    Write-Host '[outbox] Cleaning up disposable containers...'
    try {
        & docker rm -fv $kafka $postgres *> $null
    }
    catch {
        Write-Warning "Could not remove one or more test containers: $_"
    }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $oldEnv[$name], 'Process')
    }
}
