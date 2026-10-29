# Rating-service manual smoke check

This checks the #242 scaffold on a local test stack: service startup, duplicate
`OrderCompleted` delivery, persistence in `rating_db`, and removal of the legacy
tables from `user_db`. This scaffold has no ratings/reputation HTTP endpoints;
rating APIs, score calculation and auction eligibility calls belong to #212–#214.
Postman can check HTTP health/configuration. The consumer uses Kafka's protocol,
so exercising it requires an event publisher such as the Kafka CLI below.

## Start the services

Use JDK 21, installed Maven and Docker Desktop with Linux containers. Open
PowerShell in `be-project-nexus`; adjust the JDK path if needed:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21.0.12.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -f .\lib\pom.xml install -DskipTests
mvn -f .\rating-service\pom.xml package -DskipTests
```

The service wrapper `mvnw.cmd` is also available when running from its service
directory. Fill `infra/local-dev/.env` using the [repository startup guide](../../README.md#running-it).
For an existing PostgreSQL volume, create missing databases using the
[database instructions](../../README.md#database); `init.sql` only runs for a new
volume.

```powershell
Push-Location .\infra\local-dev
docker compose up -d postgres kafka-init zipkin
docker compose ps -a postgres kafka kafka-init
Pop-Location
```

Wait for PostgreSQL/Kafka to be healthy and `kafka-init` to exit with code 0.
Run each following command in a separate PowerShell terminal from
`be-project-nexus`, with JDK 21 selected. Start Config Server first, then discovery,
then the business services:

```powershell
mvn -f .\config-service\pom.xml spring-boot:run
```

```powershell
mvn -f .\discovery-service\pom.xml spring-boot:run
```

```powershell
mvn -f .\user-service\pom.xml spring-boot:run
```

```powershell
mvn -f .\rating-service\pom.xml spring-boot:run
```

User-service startup applies `V6__reputation_moved.sql`. It locks the legacy
tables and refuses to drop either table if ratings or reputations exist. A
populated source needs a separately reviewed, reconciled backfill and cutover;
the smoke check does not erase those rows or bypass the guard.

Check `http://localhost:8888/rating-service/default` returns the rating config,
`http://localhost:8089/actuator/health` reports `UP`, and
`http://localhost:8761` lists `RATING-SERVICE` as UP.

## Publish the same event twice

Open a PowerShell terminal in `be-project-nexus/infra/local-dev`. These synthetic
UUIDs create one test eligibility row without requiring matching user/order
records. Keep this terminal open for the SQL checks below.

```powershell
$orderId = [guid]::NewGuid().ToString()
$eventId = [guid]::NewGuid().ToString()
$completedAt = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.ffffffZ")
$eventJson = @{
    eventId = $eventId
    eventType = "OrderCompleted"
    aggregateId = $orderId
    occurredAt = $completedAt
    traceId = [guid]::NewGuid().ToString("N")
    payload = @{
        orderId = $orderId
        orderGroupId = [guid]::NewGuid().ToString()
        buyerId = [guid]::NewGuid().ToString()
        sellerId = [guid]::NewGuid().ToString()
        orderType = "STANDARD"
        referenceId = $null
        orderVersion = 1
        completedAt = $completedAt
    }
} | ConvertTo-Json -Depth 5 -Compress

$record = "$orderId|$eventJson"
@($record, $record) | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh `
    --bootstrap-server kafka:9092 --topic nexus.commerce.events `
    --property parse.key=true --property 'key.separator=|'
if ($LASTEXITCODE -ne 0) { throw "Kafka publish failed" }
```

The CLI path is provided by the existing `apache/kafka:4.3.1` Compose image.
The key and `aggregateId` both equal `orderId`. The complete payload follows
[OrderCompleted](api/events.md#ordercompleted); AUCTION payloads instead require
`orderType = "AUCTION"` and an auction UUID in `referenceId`.

## Verify persistence and the cutover

Wait for the rating-service log entries `RATING_ELIGIBILITY_RECORDED` and
`EVENT_ALREADY_HANDLED` for this event. Read the PostgreSQL username from the
existing container environment, then query only the generated test IDs:

```powershell
$dbUser = docker compose exec -T postgres printenv POSTGRES_USER
if ($LASTEXITCODE -ne 0) { throw "Cannot read PostgreSQL username" }
$dbUser = $dbUser.Trim()
$ratingSql = @"
SELECT order_id, buyer_id, seller_id, completed_at
FROM rating_eligibility WHERE order_id = '$orderId';
SELECT count(*) AS eligibility_count
FROM rating_eligibility WHERE order_id = '$orderId';
SELECT count(*) AS processed_count
FROM processed_events
WHERE event_id = '$eventId' AND consumer = 'rating-service.order-completed';
"@
docker compose exec -T postgres psql -U $dbUser -d rating_db -v ON_ERROR_STOP=1 -c $ratingSql
if ($LASTEXITCODE -ne 0) { throw "Rating persistence check failed" }

$userSql = @"
SELECT to_regclass('public.ratings') IS NULL AS ratings_moved,
       to_regclass('public.user_reputations') IS NULL AS reputations_moved;
"@
docker compose exec -T postgres psql -U $dbUser -d user_db -v ON_ERROR_STOP=1 -c $userSql
if ($LASTEXITCODE -ne 0) { throw "User cutover check failed" }
```

Expected: the matching eligibility row contains the published participants and
completion time; `eligibility_count = 1`, `processed_count = 1`, and both user-db
flags are `t`. Publishing `$record` again must leave both counts at 1. The sample
row stays in this local test database for inspection.
