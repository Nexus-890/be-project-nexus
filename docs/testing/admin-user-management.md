# Admin users and system configuration — #211

Sources: SRS 2.6/3.1.1, endpoint catalogue USR-04/07–11, ROL-02/03/06,
SYS-01/02, live issue #211 and MR !176 (14 November 2026). The issue combines
the previous #217 scope. The implementation starts at develop `09b3cf6f`.

## User administration

All nine operations are in `docs/api/openapi/user-service.yaml`. ADMIN needs the
exact USER/ROLE privilege. SUPPORT_STAFF may update profile details, unlock,
and read roles/permissions; it cannot delete, set a password or replace roles,
including when a token happens to contain the corresponding privilege.
The gateway checks privileges and the service checks both privileges and roles.

Creation accepts registration fields plus nonempty `roleCodes`. The fixed set is
ADMIN, SUPPORT_STAFF, BUYER and SELLER. Roles and client-role permissions are read
from Keycloak; there is no role CRUD or revived local credentials/role database.
Email, phone and identity number use existing normalization/uniqueness behavior.
Profile updates accept fullName, dateOfBirth, gender, address and email only.

`USER_SOFT_DELETE=true` marks the profile DELETED with deletedAt and retains any
seller profile. With false, the service deletes the related seller profile and
flushes it before removing the user, within the same transaction. UserDeleted
audit remains independent of both profiles. An outbox or commit failure rolls
back both deletions and attempts to restore the original Keycloak state.
Both modes disable the Keycloak identity. Missing/already
deleted profiles return 404. Unlock checks Keycloak's brute-force detector,
clears it and restores a local LOCKED profile to ACTIVE; an unlocked target
returns 409. A disabled account is distinct from a brute-force lock.

UserRegistered, UserUpdated, UserDeleted, UserRoleAssigned, UserPasswordChanged
and UserUnlocked are written through common-outbox in the profile transaction
to `nexus.user.events`. Payloads retain actor/target/timestamp and the relevant
metadata, excluding credentials and full contact/profile snapshots. No-op
profile/role updates do not emit a false change event. The existing outbox relay
and notification event-log consumer perform delivery and deduplication.

Create compensates a failed role/profile/outbox/commit operation by removing its
new Keycloak identity. Update/delete/role replacement snapshot remote state and
restore it after SQL rollback, including transaction commit failures. Remote
compensation itself can fail; this is logged for reconciliation. Keycloak Admin
API and PostgreSQL do not participate in a shared transaction.

Password and brute-force counters have no restore operation in Keycloak. After
flushing profile/outbox, the service commits an independent journal intent before
the remote call. A successful response is recorded independently as
REMOTE_SUCCEEDED; COMPLETED commits together with the original profile/outbox.
SQL rollback therefore retains evidence of the remote result. A confirmed remote
success followed by local failure returns USER_IDENTITY_RECONCILIATION_REQUIRED
(503) with operationId. Do not repeat the password/unlock request on that error.
An uncertain response leaves PENDING; do not assume the remote operation failed.

The worker runs every 30 seconds (nexus.iam.reconciliation-interval in
milliseconds), including after restart. It processes only REMOTE_SUCCEEDED and
never repeats the remote call. It recreates the matching audit event and, for
unlock, restores LOCKED to ACTIVE only if the profile version is unchanged.
Audit, profile and COMPLETED commit together; repeated workers do not duplicate
the event. If the profile changed or disappeared, it records the confirmed remote
audit with profileReconciled=false and leaves REVIEW_REQUIRED for an operator.

The durable admin_identity_operations table belongs only to user_db; it stores
operation/actor/target identifiers, event type, profile version, state and times.
It never stores passwords or provider response bodies. Intent and confirmation
use brief, separate database connections built from the existing datasource
configuration, so they can commit even while the main profile pool is full.
Completion uses the main transaction connection. Allow capacity for these brief
journal connections in PostgreSQL. Check unresolved records:

```sql
SELECT operation_id, user_id, actor_id, event_type, profile_version, state,
       created_at, remote_confirmed_at
FROM admin_identity_operations
WHERE state <> 'COMPLETED'
ORDER BY created_at, operation_id;
```

For REMOTE_SUCCEEDED, restore local database/outbox availability and allow the
worker to retry; inspect its operationId log if it persists. PENDING may mean a
crash before sending, a rejected request, a lost response, or an applied remote
change whose confirmation could not commit. The responsible IAM operator must
correlate the operation time, actor and target with Keycloak admin audit and
confirm the outcome. Never infer a password from a hash or automatically resend
it. After positively confirming success, promote only that still-PENDING record
to REMOTE_SUCCEEDED with the verified remote_confirmed_at in a controlled local
transaction, then let the worker recreate audit/profile. If the outcome cannot
be established, retain the record for manual resolution. REVIEW_REQUIRED needs
an owner decision against the current profile; do not overwrite newer account
state or manufacture a second successful operation.

This implements durable recording and reconciliation, not strict distributed
rollback. The issue's original “neither changed” criterion still requires the
responsible owner's approval of this recovery behavior before approval/merge.

The imported realm adds `passwordHistory(1)` to reject reusing the current
password. Existing realms must apply this policy through their normal Keycloak
configuration rollout; re-importing a file into an already-created realm does
not automatically update its policy. Password policy rejection returns
USER_IDENTITY_REJECTED (400), with no password material in events/log DTOs.

## System configuration

The two public operations are owned by config-service and documented in
`docs/api/openapi/config-service.yaml`. Gateway routes them to
`nexus.services.config-url` (default http://localhost:8888); Config Server is
needed before Eureka and does not require discovery registration.

GET lists all 57 SRS keys with typed value, historical SRS default,
description, owning service, applyMode and configuredValue, wrapped with complete-list page metadata.
Defaults from the old SRS are intentionally distinct from deployed values:
BUYER/SEPAY can differ from the historical USER/STRIPE defaults. Values fall
back to the SRS default when there is no Config Server source property.
The four realm settings SESSION_TIMEOUT_MINUTES, MAX_LOGIN_ATTEMPTS,
PASSWORD_POLICY and ACCESS_TOKEN_EXPIRY_MINUTES have applyMode KEYCLOAK_MANAGED:
value is read from the current realm (SSO idle timeout, brute-force failureFactor,
passwordPolicy and accessTokenLifespan). Seconds are converted to minutes without
integer truncation; MAX_LOGIN_ATTEMPTS is zero when brute-force protection is
disabled. configuredValue remains the source value; PUT rejects these keys with
CONFIG_KEY_EXTERNALLY_MANAGED. A missing credential, unavailable realm or invalid
realm response returns CONFIG_IDENTITY_PROVIDER_UNAVAILABLE (503), rather than
null or a fabricated default. Config Server startup does not contact Keycloak.
Provision the dedicated config-service account with view-realm using seed-users.sh
and KEYCLOAK_CONFIG_SERVICE_CLIENT_SECRET; see the Keycloak README. Existing
realms need this provisioning too; realm import does not upgrade them.
The retry limit has applyMode RUNTIME; the remaining source settings have
applyMode CONFIG_SOURCE. This prevents a source-only change being reported as
an applied identity-provider policy.
Some SRS flags have no implemented business consumer; persisting such a source
setting does not introduce that business feature. Credentials and arbitrary
Spring property names are excluded from the registry.

PUT validates the actual JSON type and supported ranges. A string `"4"` is not an
integer and a string `"false"` is not a boolean. Unknown keys/types use owning
CONFIG_* Problem Details (400). It writes an overlay under the owning service;
the original versioned Config Server YAML files and applied migrations are kept.
Owner refresh uses the authenticated request's JWT with bounded connect/read
timeouts. Notification's retry properties are refreshed so the next decision
uses the new NOTIFICATION_RETRY_LIMIT without restart. Other settings retain
their consumer's refresh/restart behavior. Keycloak-managed settings require
the identity provider's configuration rollout, as described by their registry
metadata; writing a Spring property cannot update a Keycloak realm.

Overrides and pending audit envelopes share one atomic JSON snapshot at
`SYSTEM_CONFIG_STATE_FILE` (default `./data/system-configs.json`). Use a persistent
disk, one active Config Server per state file, and backups. An OS file lock
rejects a second writer. A staged intent is persisted before owner refresh;
restart recovers the desired source and the same audit eventId. Services still
holding pre-crash properties need their normal refresh/restart. A failed update
aborts its intent, restores the source and requests compensating owner refresh;
an unavailable owner can also prevent that compensation and is logged.

Set the same `CONFIG_AUDIT_SHARED_SECRET` (at least 32 characters, generated and
kept outside Git) in config-service and notification-service. The durable relay
signs the exact envelope bytes with HMAC-SHA256 and sends them to
`POST /internal/v1/system-config-audit`. This endpoint is absent from gateway
routes. Notification verifies the signature, event type and envelope metadata,
records it transactionally, and acknowledges only after persistence. Retries and
restart keep eventId; the existing event_logs uniqueness rule deduplicates them.
An unavailable recorder or missing secret retains the pending event for retry.
Config Server reads this secret from its process environment or JVM property;
it does not import the developer `.env` file. Configure the same secret for both
processes; setting it only in the notification `.env` is insufficient.
No new Kafka topic or cross-service SQL access is introduced.

## Migration upgrade after develop integration

The user-service chain keeps develop's `V8__seller.sql` and adds the identity
operation journal as `V9__admin_identity_operation_journal.sql`. The journal SQL
body is unchanged; existing develop migrations must retain their bytes/checksums.
The chain has versions 1, 3, 4, 5, 6, 7, 8 and 9; V2 is intentionally absent.

`SellerMigrationTest` checks a fresh chain and an upgrade from develop V8. The
upgrade seeds a nonempty seller profile, snapshots every users/seller column and
the complete Flyway history, requires exactly one V9 migration, and verifies that
the snapshots and all prior history entries remain unchanged. Both paths run
Flyway validate and then require zero migrations on rerun. The preexisting V7
upgrade and `EnumMigrationTest` retain coverage of seller and enum compatibility.
Byte equality of V1–V8 against the exact merged develop commit is checked during
conflict-resolution verification, separately from these database assertions.

The PostgreSQL test helper defaults to a disposable PostgreSQL 18 container. An
isolated database can instead be selected with `admin.test.jdbc-url`,
`admin.test.username` and `admin.test.password`; this skips container startup and
uses the same properties as `AdminUserTransactionTest`. Use a new disposable test
database for a run, since the tests own and write their named schemas.

```powershell
# In user-service, with JDK 21 and the isolated PostgreSQL test database available
.\mvnw.cmd '-Duser.timezone=UTC' '-Dtest=SellerMigrationTest,EnumMigrationTest,AdminUserTransactionTest' test
```

Conflict-resolution verification on 16 November 2026 against develop `d8dbe681`:
all seven existing user-service SQL files match develop; the journal is a 100%
rename from V8 to V9. Local checks passed: 93 Admin/User tests (including 23
PostgreSQL transaction/recovery), five migration/enum tests, six seller persistence
tests and four seller controller tests. The controller tests passed on a separate
rerun after excluding event/consumer auto-configuration from their MVC-only context.
Gateway tests passed 88, contract inventory passed 153 operations, and user/config
OpenAPI lint passed. PostgreSQL 18 used a disposable database; no real Keycloak,
full running-stack acceptance or remote CI success is claimed for this resolution.

## Validation

Use JDK 21 and install `lib` before building services. Unit and HTTP fixtures use
controlled Keycloak responses or a JWT decoder fixture; they do not certify a
deployed Keycloak realm. PostgreSQL transaction regressions use an isolated test
database, not user_db from the developer's running stack.

```powershell
# In lib
.\mvnw.cmd install -DskipTests
# In user-service
.\mvnw.cmd test -Dtest=AdminUserServiceTest,AdminUserControllerSecurityTest,KeycloakAdminClientHttpTest,AdminUserTransactionTest
# In config-service
.\mvnw.cmd test
# In notification-service
.\mvnw.cmd test -Dtest=RetryPolicyTest,RetryPolicyRefreshTest,SystemConfigAuditControllerTest
# In api-gateway
.\mvnw.cmd test
# In backend root
node docs/api/check-contract.mjs
```

Verified on 14 November 2026 with JDK 21: full user-service verify 227 tests,
full config-service verify 36, full gateway 85; notification audit integration
10, retry/HMAC focused 9 and actual actuator HTTP refresh 2. All passed without
failures, errors or skips. Shared libraries install/tests also passed. A final
registry boundary check rejects MAX_PRODUCT_IMAGES above 32767.

The user suite includes actual PostgreSQL 18 and Keycloak 26.7 HTTP operations,
PKCE sign-in after password change/unlock, sign-in rejection after deletion, and
SQL commit-failure compensation. Notification audit tests send Kafka records,
verify PostgreSQL audit rows/API visibility and deduplicate a replay. The retry
HTTP test rereads a controlled ConfigData file through the real Cloud refresh
endpoint and retains the existing policy/application context. A separate booted
Config Server test verifies native HTTP source overlays and service isolation.

The isolated Keycloak script also passed service-account permissions (65 client
roles), repeat seed, password history and manual brute-force unlock with PKCE.
It explicitly skips natural recovery after the full 15-minute lockout; that is
not needed to verify administrative unlocking. Test resources belong to isolated
projects/databases. No production realm or developer database was reset.

OpenAPI user/config lint passes with no warnings, and check-contract matches
111 delivered operations. Execution logs are under D:\GitLab\log\admin211-*.
These are local results; no remote CI pass is implied.


## Primary local project verification

The implementation and tests are also installed in the developer project
`D:\GitLab\hcm26_cpl_java_11_group_5`. This is the source checkout used for
local development and IntelliJ runs. The initial isolated publication did not
populate this checkout; the follow-up applied only #211 changes, preserved
existing issue work, and reran the checks in this exact local directory.

Primary verification on 14 November 2026: user-service clean compilation and
verify 227 PASS; config-service verify 36 PASS; gateway verify 83 PASS; notification retry/HMAC/HTTP
refresh 11 PASS and actual Kafka/PostgreSQL event-log integration 10 PASS.
Docker was initially stopped; those setup failures were superseded by the
successful runs after starting Docker Desktop. No developer database, Docker
image or volume was reset.

Logs: admin211-primary-user-verify-docker.log, admin211-local-config-verify.log,
admin211-local-notification-unit-http.log and admin211-local-notification-eventlog.log
under `D:\GitLab\log`. Local contract inventory has 97 delivered operations
because this integration checkout carries its existing service scope.

Workflow: make changes and run tests in this primary local project first, then
publish only the changes belonging to the exact issue MR branch. Preserve
other issues and their staging when shared files need updates.
