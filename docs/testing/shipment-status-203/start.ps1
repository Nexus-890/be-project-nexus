param(
    [switch]$Rebuild,
    [string]$Issuer = 'http://localhost:18080/realms/nexus',
    [string]$JwksUrl = ''
)

$ErrorActionPreference = 'Stop'
$kitRoot = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $kitRoot '../../..')).Path
$evidenceRoot = Join-Path $kitRoot 'evidence'
$statePath = Join-Path $evidenceRoot 'runtime-state.json'
New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
foreach ($tool in @('docker', 'node', 'java')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Install $tool before starting the test environment." }
}
if (Test-Path -LiteralPath $statePath) { & (Join-Path $kitRoot 'stop.ps1') -KeepContainers }
$jdk21 = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.jdks/corretto-21.0.12.1'
if (Test-Path -LiteralPath (Join-Path $jdk21 'bin/java.exe')) { $env:JAVA_HOME = $jdk21 }
$javaExecutable = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$jarPath = Join-Path $repoRoot 'shipment-service/target/shipment-service-0.0.1-SNAPSHOT.jar'
$sourceChanged = $false
if (Test-Path -LiteralPath $jarPath) {
    $jarDate = (Get-Item -LiteralPath $jarPath).LastWriteTimeUtc
    $sourceChanged = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'shipment-service/src/main') -Recurse -File | Where-Object LastWriteTimeUtc -gt $jarDate).Count -gt 0
}
if ($Rebuild -or $sourceChanged -or -not (Test-Path -LiteralPath $jarPath)) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Install Maven to build shipment-service.' }
    $localRepositoryArgument = '-Dmaven.repo.local=' + [Environment]::GetFolderPath('UserProfile') + '/.m2/repository'
    Push-Location $repoRoot
    try {
        & mvn $localRepositoryArgument -f lib/pom.xml install -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Shared library build failed.' }
        & mvn $localRepositoryArgument -f shipment-service/pom.xml package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Shipment build failed.' }
    } finally { Pop-Location }
}
& docker compose -f (Join-Path $kitRoot 'compose.yaml') up -d --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'The isolated Docker stack did not become healthy.' }
foreach ($topic in @('nexus.commerce.events', 'nexus.shipment.events')) {
    & docker exec issue203-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $topic --partitions 1 --replication-factor 1
    if ($LASTEXITCODE -ne 0) { throw "Cannot prepare Kafka topic $topic" }
}
if ([string]::IsNullOrWhiteSpace($JwksUrl)) { $JwksUrl = "$Issuer/protocol/openid-connect/certs" }
$configPath = Join-Path $evidenceRoot 'application-issue203.properties'
$logPath = (Join-Path $evidenceRoot 'shipment-service.log').Replace('\', '/')
$properties = @"
server.port=18088
server.address=127.0.0.1
spring.application.name=shipment-service
spring.cloud.config.enabled=false
spring.cloud.discovery.enabled=false
eureka.client.enabled=false
spring.cloud.compatibility-verifier.enabled=false
spring.datasource.url=jdbc:postgresql://localhost:15433/shipment_db
spring.datasource.username=issue203
spring.datasource.password=issue203-local-password
spring.jpa.open-in-view=false
spring.jpa.hibernate.ddl-auto=validate
spring.kafka.bootstrap-servers=localhost:39092
spring.kafka.admin.auto-create=false
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.listener.ack-mode=record
spring.kafka.listener.missing-topics-fatal=true
spring.kafka.producer.acks=all
nexus.security.jwt.issuer=$Issuer
nexus.security.jwt.audience=nexus-services
nexus.security.jwt.jwk-set-uri=$JwksUrl
nexus.security.public-endpoints[0].method=POST
nexus.security.public-endpoints[0].path=/api/v1/webhooks/carriers/*
nexus.shipping.carrier=GHN
nexus.shipping.ghn.base-url=http://127.0.0.1:9903
nexus.shipping.ghn.shop-id=203
nexus.shipping.order-paid-consumer-enabled=false
nexus.shipping.order-cancelled-consumer-enabled=true
nexus.shipping.status-poll-enabled=true
nexus.shipping.status-poll-interval-ms=2000
nexus.shipping.status-poll-job-ms=500
nexus.shipping.status-poll-initial-delay-ms=1000
nexus.outbox.relay.enabled=true
nexus.outbox.relay.initial-delay=100
nexus.outbox.relay.fixed-delay=250
management.tracing.sampling.probability=1
management.tracing.export.zipkin.enabled=false
management.endpoints.web.exposure.include=health
logging.file.name=$logPath
"@
[IO.File]::WriteAllText($configPath, $properties, [Text.UTF8Encoding]::new($false))
$env:GHN_TOKEN = 'issue203-local-outbound-token'
$env:GHN_WEBHOOK_SECRET = 'issue203-local-webhook-secret'
$env:ISSUE203_DB_CONTAINER = 'issue203-postgres'
$env:ISSUE203_DB_USER = 'issue203'
$env:ISSUE203_DB_NAME = 'shipment_db'
$env:ISSUE203_KAFKA_CONTAINER = 'issue203-kafka'
$env:ISSUE203_SHIPMENT_LOG = $logPath
$companion = Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"' + (Join-Path $kitRoot 'tools/companion.mjs') + '"')) -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidenceRoot 'companion.stdout.log') -RedirectStandardError (Join-Path $evidenceRoot 'companion.stderr.log')
$javaArgs = @('-Xms128m', '-Xmx512m', '-jar', ('"' + $jarPath + '"'), '--spring.config.name=issue203', ('"--spring.config.location=file:' + $configPath.Replace('\', '/') + '"'))
$service = Start-Process -FilePath $javaExecutable -ArgumentList $javaArgs -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidenceRoot 'service.stdout.log') -RedirectStandardError (Join-Path $evidenceRoot 'service.stderr.log')
@{ servicePid = $service.Id; companionPid = $companion.Id; kitRoot = $kitRoot; startedAt = (Get-Date).ToString('o'); issuer = $Issuer } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
$deadline = (Get-Date).AddSeconds(120)
$ready = $false
while ((Get-Date) -lt $deadline) {
    if ($service.HasExited) { throw "Shipment service exited. See $evidenceRoot/service.stdout.log" }
    if ($companion.HasExited) { throw "Companion exited. See $evidenceRoot/companion.stderr.log" }
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:18088/actuator/health' -TimeoutSec 3
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw "Service readiness timed out. See $evidenceRoot/service.stdout.log" }
$seed = Invoke-RestMethod 'http://127.0.0.1:9903/__test/seed' -Method Post -ContentType 'application/json' -Body '{}' -TimeoutSec 30
& node (Join-Path $kitRoot 'postman/build-collection.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Postman artifact generation failed.' }
Write-Host "READY: shipment API http://localhost:18088; evidence http://127.0.0.1:9903; seeded $($seed.fixtureCount) cases."
Write-Host 'Import the Postman collection and environment, fill four access tokens, then run the collection.'
