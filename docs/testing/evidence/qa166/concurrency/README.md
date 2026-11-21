# QA #166 / #167 concurrency results — 21 November 2026

Both current test classes passed all ten existing `@RepeatedTest(10)` races.
Each class ran once in a fresh Maven/Failsafe process against an isolated source
snapshot and a disposable PostgreSQL 18 container. No test or assertion changed.

| Test | Races | Requests per race | Passed | Failures/errors/skips | Maven exit |
| --- | ---: | ---: | ---: | --- | ---: |
| `ReservationConcurrencyIT` | 10 | 100 | 10 | 0 / 0 / 0 | 0 |
| `BidConcurrencyIT` | 10 | 50 | 10 | 0 / 0 / 0 | 0 |

Environment: Windows, Temurin JDK 21.0.12.1, Maven 3.9.16, Failsafe 3.5.6,
Docker Desktop Engine 29.8.0, UTC for test JVMs, `-Xmx384m`,
`-XX:ActiveProcessorCount=2`. Execution window: 23:06:11–23:09:26 Asia/Saigon
(16:06:11–16:09:26 UTC). Process wall times: 73.90 s reservation and 121.24 s bid.

Every reservation race returned exactly 10 HTTP 201 and 90 HTTP 409 responses,
with the expected problem codes. Database assertions verified ten reservation
rows, exact returned/HELD IDs, ten RESERVE movements, validated CHECK constraints,
and total=10, available=0, reserved=10, unavailable=0, version=10.

Every bid race sent 50 HTTP requests from ten buyer identities and received 50
HTTP 202 `QUEUED` responses. All fifty bids reached `PLACED` or `REJECTED`, no
queued bid remained, and the highest amount/leader was 3,500,000.00 VND. Database
price updates were strictly increasing; the final bid count equalled placed
rows and `BidPlaced` outbox rows. Placed/rejected counts by race were
3/47, 6/44, 4/46, 4/46, 3/47, 4/46, 2/48, 5/45, 5/45, 4/46. Queue drain times
were 2,935, 2,101, 1,774, 1,746, 2,460, 2,222, 1,907, 3,305, 2,814, 1,980 ms.

These are local correctness checks. Reservation uses a mocked JWT decoder.
Auction validates locally signed RSA JWTs and invokes the real command consumer
directly in reverse outbox-delivery order, with Kafka listeners and the relay
disabled. The reservation fixture points Kafka to an unused port and emits
connection warnings. These results do not certify live Kafka, multi-service
walkthroughs, throughput, latency or STAGING.

## Reproduce

Install `lib` first with JDK 21, then run from the repository root in PowerShell:

```powershell
& './be-project-nexus/docs/testing/run-concurrency-reruns.ps1' `
  -WorkRoot 'D:/SRS-project/qa166-concurrency-NEW' `
  -EvidenceRoot 'D:/GitLab/log/qa166-concurrency-NEW' `
  -JavaHome 'C:/Users/trinh/.jdks/temurin-21.0.12.1'
```

Use a new work directory. The runner copies the parent POM and service source,
records SHA256 hashes, and runs each service with the following selected goals:

```powershell
.\mvnw.cmd -B '-Dstyle.color=never' '-Duser.timezone=UTC' `
  '-Dit.test=ReservationConcurrencyIT' test-compile failsafe:integration-test failsafe:verify
.\mvnw.cmd -B '-Dstyle.color=never' '-Duser.timezone=UTC' `
  '-Dit.test=BidConcurrencyIT' test-compile failsafe:integration-test failsafe:verify
```

The runner fails unless each class reports exactly ten tests and ten invariant
lines, with zero failures/errors/skips and a successful Maven exit. This focused
run does not invoke the full `verify` lifecycle or its coverage gate; full-suite
coverage belongs to the separate coverage evidence.

`summary.csv` records process exits and JUnit counts; the two `*-repetitions.txt`
files contain all twenty actual invariant lines. `source-manifest.csv` identifies
the copied source inputs. Full raw Maven logs and Failsafe XML remain locally at
`D:/GitLab/log/qa166-20261121/concurrency/`; the isolated source snapshot is
`D:/SRS-project/qa166-concurrency-20261121/`.
