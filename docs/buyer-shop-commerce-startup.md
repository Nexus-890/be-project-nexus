# Commerce startup check — 10 November 2026

At the time of this check, Commerce was already running outside IntelliJ on port 8083. The existing process was PID 380, started at 23:18:12 local time. Its log, `commerce-service/target/kafka-recovery-20261110-231812.log`, recorded Tomcat listening on 8083 and a successful Commerce startup, including subscription to `nexus.payment.events`.

## Verified result

- `GET http://localhost:8083/actuator/health` returned HTTP200 and `{"status":"UP"}`.
- Eureka listed `COMMERCE-SERVICE` as `UP` on port 8083.
- Config Server 8888 and Discovery Server 8761 returned HTTP200.
- `commerce-service/mvnw.cmd -o -DskipTests compile` completed successfully. Output is in `commerce-service/target/commerce-startup-audit-compile.log`.
- No Commerce source, configuration, schema or applied migration was changed during this investigation.

## Avoid starting two instances on the same port

A second Commerce instance cannot bind port 8083 while the existing instance is listening. If IntelliJ reports `Port 8083 was already in use`, use the existing service or stop the identified previous Commerce process before running the IntelliJ configuration. Do not stop unrelated Java applications.

The user subsequently provided the IntelliJ Run output for PID 22680 at 23:45:41:
`Web server failed to start. Port 8083 was already in use.` This confirms the
duplicate-port failure. A later recheck found the previous background process no
longer running and no listener on 8083; it was not forcibly stopped by this check.
Run one Commerce instance from the IDE after confirming that the port is free.

Useful read-only checks from PowerShell:

```powershell
Invoke-RestMethod http://localhost:8083/actuator/health
Get-NetTCPConnection -State Listen -LocalPort 8083 |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

## Normal local run configuration

Start infrastructure, Config Server and Discovery first. Run Commerce with working directory `be-project-nexus/commerce-service`, or use the Backend startup helper. The service imports `../infra/local-dev/.env` from that directory and Config Server at `http://localhost:8888`.

The local Docker PostgreSQL host port is 5433; its container port remains 5432. Host-run Commerce therefore needs the configured `DB_HOST=localhost` and `DB_PORT=5433`. Java 21 is the repository target. The canonical JVM timezone `Asia/Ho_Chi_Minh` avoids the PostgreSQL 18 rejection of the host's `Asia/Saigon` alias observed during earlier database tests.

Commerce health does not prove the entire checkout/payment flow is complete. Shipping quote, real inventory reservation and SePay settlement limitations are recorded separately in the Buyer Shop/SePay reports.
