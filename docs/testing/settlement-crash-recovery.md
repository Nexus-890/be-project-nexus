# Settlement crash recovery (#166, former #174)

`commerce-service/.../AuctionSettlementRecoveryIT.java` starts the packaged production
Commerce and Auction applications in separate JVMs. It uses a Kafka Testcontainer and
two PostgreSQL 18 Testcontainers. Each scenario receives fresh database schemas; all
test resources are disposable and the local development databases are never used.
No consumer, service, repository, outbox writer or Kafka publisher is mocked.

Use Java 21 and a running Docker engine. Install the shared libraries, then package
Auction before running Commerce's integration tests. The production JARs must include
the latest `src/main` files; the test rejects missing or stale artifacts.

```powershell
# From be-project-nexus/lib. Dependency bootstrap only while below-70 library follow-ups remain open; not verification.
.\mvnw.cmd -DskipTests -Djacoco.skip=true install

# From be-project-nexus/auction-service, prepare the application launched by the test.
.\mvnw.cmd -DskipTests package

# From be-project-nexus/commerce-service, normal lifecycle runs unit tests and the IT.
.\mvnw.cmd verify

# Focused diagnostic rerun, after packaging both applications:
.\mvnw.cmd '-Dtest=AuctionSettlementRecoveryIT' test
```

`-DskipTests package` above prepares the process artifact; it is not a successful
test result. The final `verify` still runs the actual tests and coverage rule.
For the focused command, first run `-DskipTests package` in Commerce as well.

The three scenarios verify:

1. Commerce's relay receives an initial delay of one hour. After an external SQL
   connection observes one committed order and its pending `OrderCreated` outbox
   row, and the Kafka log contains no corresponding `OrderCreated`, the test
   forcibly terminates Commerce's JVM. Restarting the same production JAR against
   the same database, with its ordinary relay enabled, must publish the event and
   settle the auction.
2. The identical `AuctionWon` envelope is delivered through Kafka three times.
   The test waits for the Commerce consumer group's committed offset to cover all
   three records before counting one order, one processed marker and one matching
   `OrderCreated` record in the broker log.
3. The Auction settlement group first commits a warm-up record. The test stops
   Auction, then waits for Commerce to publish `OrderCreated` and confirms its
   presence in Kafka while the auction remains `WON`. Restarting Auction against
   its existing database and consumer group must consume the retained record and
   reach `SETTLED`.

After every scenario, `SELECT count(*) FROM orders WHERE reference_id = ?` must
return 1. Auction must reference that exact order and contain one `AuctionSettled`
outbox row. The test reads the Kafka log through a captured end offset, independently
of the services' publication markers, to assert exactly one matching `OrderCreated`.

Application logs and queried SQL transitions are written to
`commerce-service/target/settlement-recovery-evidence/`, including partial steps on
failure. Surefire/Failsafe retain the authoritative test result. The application
processes and containers are stopped after the run. This evidence proves isolated
local crash recovery; it does not establish STAGING deployment or payment acceptance.

Child JVMs are launched independently from the Maven coverage agent, so their work
does not inflate the module's unit/integration coverage percentage. These scenarios
verify observable cross-process behavior in addition to the in-process suite.


