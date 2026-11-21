# Reproduction notes

Run `docs/testing/run-quality-suite.ps1` from PowerShell with JDK 21 and Docker.
The output preserves full Maven logs, JUnit XML and JaCoCo HTML/XML per module.

The `common-outbox` integration test expects an isolated PostgreSQL and an owned
Kafka container, as documented by `lib/common-outbox/verify-integration.sh`.
The Windows run used `D:/GitLab/log/qa166-20261121/run-outbox-dedicated.ps1`:
it creates both dedicated containers, sets the five `OUTBOX_TEST_*` variables,
executes `clean verify`, and stops only its own containers. The owned broker
name must start with `nexus-outbox-test-` because the outage test checks that
prefix before stopping/starting the broker. Do not point that test at the live
project broker. Test credentials are generated per run, never committed.

The library dependency bootstrap used explicit `-DskipTests -Djacoco.skip=true
install`; this is not counted as test success. Actual final checks omit those
flags and retain the 70% gate. The final common-outbox and commerce-service
verifies passed with the gate enabled.
