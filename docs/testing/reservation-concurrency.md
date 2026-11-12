# Reservation concurrency regression (#135)

`ReservationConcurrencyIT` sends 100 real HTTP requests of 1 unit each to
`POST /api/v1/inventory/reservations` for one SKU that has 10 units available.
All 100 requests are built first, then released together by one latch.
`@RepeatedTest(10)` inserts a fresh balance row with a new SKU for every run,
after the reservation, movement and balance tables are emptied.

## What one run checks

1. Exactly 10 responses are 201 and 90 are 409.
2. Every 409 carries the problem code `INVENTORY_INSUFFICIENT_STOCK`.
3. The balance row reads total 10, available 0, reserved 10, unavailable 0, and
   version 10 (one increment per accepted reservation).
4. The `inventory_balances` CHECK constraints exist and are validated
   (`pg_constraint.convalidated`): `total = available + reserved + unavailable`,
   `available >= 0` and `reserved >= 0`.
5. `SELECT count(*) FROM inventory_reservations` is 10.
6. The 10 HELD reservation ids in the table are exactly the ids returned by the
   10 accepted responses.
7. There are exactly 10 `RESERVE` movements of quantity 1 for the SKU.

All checks are soft assertions, so a failing run reports every broken check,
not only the first. Each run also prints one line before asserting:

```
RESERVATION_CONCURRENCY run=1 requests=100 created=10 conflicts=90 reservation_rows=10 available=0 reserved=10 total=10
```

The test uses the embedded HTTP server, the security filter chain and method
authorization, the controller, service, repositories, transactions, Flyway
migrations, Hibernate schema validation and PostgreSQL 18 from the shared
`common-test` base. The JWT decoder is mocked to return a BUYER token. Kafka,
Config Server and Eureka are not needed. The test does not measure throughput.

## Run locally

Use Java 21, Maven and a running Docker daemon. Install `lib` first, then run
`verify` from the inventory-service directory (one existing test reads a
config-service file relative to that directory).

```powershell
Set-Location be-project-nexus/lib
.\mvnw.cmd -B install
Set-Location ../inventory-service
.\mvnw.cmd -B clean verify
```

Failsafe runs `*IT` during `verify` and fails if no integration test is found.
`common-test` starts a real PostgreSQL container and fails if Docker is absent.
There is no H2 fallback and no Docker-based skipping.

## CI

CI cannot run on the project's GitLab host, so there is no CI job for this test.
The green and red runs are recorded as terminal screenshots in
[01-green-verify.png](evidence/reservation135/01-green-verify.png) and
[02-red-read-then-write.png](evidence/reservation135/02-red-read-then-write.png).

## Red proof

[read-then-write.patch](evidence/reservation135/read-then-write.patch) changes only
`InventoryBalanceRepository.reserve`: it reads available and reserved, checks
the quantity in Java, then writes the new values back with no condition. The
transaction, reservation insert and movement paths are unchanged, and no sleep
is added.

[red-result.txt](evidence/reservation135/red-result.txt) records 10 failed runs out
of 10, with 0 errors and 0 skips. Between 88 and 98 requests got 201 against
10 units in every run. In runs 1 to 4 the balance row still read available 0
and reserved 10, so a check on the row alone would have passed. The reservation
count, the movement count and the row version caught it.

[green-result.txt](evidence/reservation135/green-result.txt) records the same test
file, by SHA256, passing 10 runs out of 10 on the real conditional reserve.

To reproduce, from the repository root:

```powershell
git apply be-project-nexus/docs/testing/evidence/reservation135/read-then-write.patch
```

Then from inventory-service:

```powershell
.\mvnw.cmd -B '-Dit.test=ReservationConcurrencyIT' test-compile failsafe:integration-test failsafe:verify
```

Then restore the repository from the repository root, even after an unexpected
error, and run the normal green `verify` again:

```powershell
git apply -R be-project-nexus/docs/testing/evidence/reservation135/read-then-write.patch
```

The patch is evidence only and must never be applied to production code.
