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

`USER_SOFT_DELETE=true` marks the profile DELETED with deletedAt;
false removes the profile. Both disable the Keycloak identity. Missing/already
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

Password and brute-force counters have no restore operation in Keycloak. The
service flushes profile/outbox before the final remote call and rolls SQL back
when Keycloak rejects it. A lost remote response, process death or commit failure
after that call cannot be made atomic with the current dependency. This remains
a limitation against the issue's strict “neither changed” distributed-failure
criterion; the implementation does not claim an XA guarantee.

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
value is null (effective realm value not read here), configuredValue is the
historical source value, and PUT rejects them with CONFIG_KEY_EXTERNALLY_MANAGED.
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
