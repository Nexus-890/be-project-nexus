# Nexus Keycloak realm (#236)

## Run locally

From `be-project-nexus/infra/local-dev`:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# Fill DB_*, KEYCLOAK_* and the three DEMO_*_PASSWORD values in .env.
docker compose up -d postgres auth-service
.\keycloak\seed-users.ps1
```

Account password pages (#219) additionally need `mailpit`: start it with
`docker compose up -d mailpit auth-service`. The Nexus login theme is mounted
read-only and selected on a fresh realm import; Forgot password sends reset
email through Mailpit at `mailpit:1025`. Existing realms need the login theme,
Forgot password and Email settings applied in the Admin Console because
`--import-realm` skips a realm already in the database. See
[password page setup and live verification](../../../../fe-project-nexus/docs/features/password-pages.md).

Set `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `KEYCLOAK_PORT`,
`KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` and the three demo passwords before
running these commands. Existing `.env` files created before Keycloak was added
may be missing its administrator settings; copying `.env.example` only happens
when `.env` does not exist.

Starting Keycloak and seeding its demo users do not require `SEPAY_BANK_ACCOUNT`
or `SEPAY_ACCOUNT_NAME`. Compose parses every service before applying profile
selection, so these payment settings may remain blank when using auth. When
payment-service itself starts, its configuration validation still requires them.

Open `http://localhost:18080/admin` (or your `KEYCLOAK_PORT`) and select
the `nexus` realm. The bootstrap administrator belongs to `master`; it is
different from the demo `admin@nexus.local` user in `nexus`.

For an alternative local env file:

```powershell
.\keycloak\seed-users.ps1 -EnvFile 'C:\path with spaces\local.env'
```

The PowerShell entry point passes the file to Compose's dotenv parser; it does
not execute its contents. Quote values with
spaces, `$`, or `#` using single quotes. Use your local values; the existing
`infra/staging/.env.example` identifies the project's demo-password variables.
Never commit `.env`. No password or credential hash belongs in the realm JSON.

On an existing Postgres volume, ensure `auth_db` already exists as described in
the root README. Restarting Postgres does not re-run `init.sql`.

## What is provisioned

| Account | UUID suffix (prefix `68000000-0000-4000-8000-`) | Role | Privileges |
|---|---|---|---|
| `admin@nexus.local` | `000000000001` | `ADMIN` | 56 |
| `seller@nexus.local` | `000000000002` | `SELLER` | 28 |
| `buyer@nexus.local` | `000000000003` | `BUYER` | 30 |

`SUPPORT_STAFF` has 35 privileges and no demo account. The 65 distinct client
roles and the four composite realm-role mappings match
`user-service/src/main/resources/db/migration/V3__roles_and_accounts.sql`
(SRS section 2.7). ADMIN intentionally does **not** receive all 65 privileges.

The `nexus-web` client is public, uses Authorization Code with mandatory PKCE
`S256`, allows `http://localhost:3000/*` redirects, and limits browser origins
to `http://localhost:3000`. Implicit flow and password/direct grants are disabled.
There is no client secret. Do not test this client with `grant_type=password`.

The seed command also creates the confidential `user-service` client (#238) when it
is missing and sets its secret from `KEYCLOAK_USER_SERVICE_CLIENT_SECRET`. Only its
service account and the password grant are enabled; browser and implicit flows are off.
user-service uses the password grant only to check the current password when a
signed-in user changes it, and ends that session at once. The
service account holds the `realm-management` roles `manage-users`, `view-users`,
`query-users`, `view-realm`, `query-clients` and `view-clients`, which user-service
needs to administer accounts, read fixed realm roles and read client-role privileges
for #211. The seed leaves other client service accounts unchanged. The client is not in
`realm-nexus.json`, so an existing realm gets it on the next seed run.

The imported password policy includes `passwordHistory(1)` so admin password reset
rejects reuse of the current password. Existing realms require the same policy update
in Keycloak before rollout; importing source again does not update an existing realm.
Repeated demo seeding treats only Keycloak's explicit current-password history
rejection as an already completed reset; other policy and transport failures stop seed.

When `KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET` is set, the seed command also
creates the confidential `notification-service` client (#191). Its service account
holds only `realm-management` `view-users`, which notification-service needs to read
a recipient's email before it sends a notification email. Leave the secret empty to
skip it while `EMAIL_NOTIFICATION_ENABLED=false`.

For #217, set `KEYCLOAK_CONFIG_SERVICE_CLIENT_SECRET` in the deployment environment
for both the seed command and config-service, then re-run the existing seed command.
It creates or updates a dedicated confidential `config-service` client with only
`realm-management` `view-realm`; browser/password grants remain disabled. This also
upgrades an existing realm without replacing it. Config Server starts without IAM;
the authenticated admin config listing requires this credential and a readable realm
and returns `503 CONFIG_IDENTITY_PROVIDER_UNAVAILABLE` otherwise.

The four `KEYCLOAK_MANAGED` rows read one fresh realm snapshot per GET:
`ssoSessionIdleTimeout` and `accessTokenLifespan` become numeric minutes (including
fractions); `failureFactor` is the failed-attempt limit when `bruteForceProtected`
is true, or `0` when it is disabled; `passwordPolicy` is returned verbatim. These
are realm defaults; client-specific timeout/lifespan overrides are separate Keycloak
settings. `configuredValue` remains the Config Server source and `defaultValue`
remains the historical SRS value. PUT for these four keys stays read-only.

When `AUCTION_RATING_CLIENT_SECRET` is set, the seed command creates or updates
the confidential `nexus-auction-service` client for internal rating eligibility.
Its access token contains only the eligibility privilege and the machine marker;
it receives no realm-management roles. Leave this optional secret empty while
reputation enforcement is off. See [rating eligibility machine setup](../../../docs/security-keycloak/rating-eligibility.md)
for its claims, allowlist, existing-realm provisioning and verification.

Access tokens are signed with RS256 and contain:

- `sub`: the imported user UUID, unchanged from the other service seeds.
- `roles`: a JSON array of Nexus realm roles, without the `ROLE_` prefix.
- `privileges`: a JSON array of the user's effective `nexus-web` client roles.
- `aud`: includes `nexus-services`.

Explicit role scope mappings keep default Keycloak realm roles out of `roles`.
The built-in `basic` client scope is retained so Keycloak 26.7 emits `sub`.
The current gateway and common-security converters already read these flat
claims. Switching their issuer/JWKS configuration is **#237**, not this issue.
Removing the old user-service login/token code is **#238**.

## Password policy and lockout

The supplied [SRS section 2.6](https://docs.google.com/document/d/1nw7_WwZoWhjgA1WiZJzu37ps_MisI5Ls/edit)
names `PASSWORD_POLICY=STANDARD`, without specifying a composition rule.
[Business Rules BR-005](https://docs.google.com/spreadsheets/d/1x2ulrpa9n5Xmgb8G7b3zJ9sisHjYJG7wGycalQGHB-U/edit)
also refers to STANDARD without defining uppercase/digit/symbol requirements.
This realm maps STANDARD to the existing `RegisterRequest` business baseline:
at least 8 characters and not entirely whitespace. It does not invent composition
requirements. The old 72-byte BCrypt input limit is implementation-specific;
Keycloak manages its own credential hashing instead of importing BCrypt hashes.
If STANDARD is clarified later, update the realm policy and its tests together.

Five consecutive failed logins temporarily lock the user for 900 seconds;
the next attempt is refused even with a correct password. Permanent lockout
is disabled. Quick-login throttling is disabled so rapid attempts do not lock
the account before the fifth failure. A successful login resets the counter.

The PowerShell entry point runs `seed-users.sh` inside the Keycloak container.
That container script only resets credentials of the three exact imported IDs. It
checks their usernames, uses a private temporary kcadm config, and removes it
on exit. Passwords are non-temporary, so no forced reset blocks demo login.
The script can be re-run without creating users or changing IDs.
It uses `kcadm update users/<id>/reset-password`, so demo passwords starting
with `-` are accepted as credential values. Access-token lifetime and SSO
session idle timeout are both 3600 seconds; the session maximum is 36000 seconds.

## Password reset, email verification and mail (#210)

Users change their password on /profile (`POST /api/v1/users/me/password`) and
reset a forgotten one through /forgot-password, which emails a one-time link to
/reset-password valid for 30 minutes (`POST /api/v1/auth/password-reset` and
`/confirm`). The in-app reset and change sign out the other sessions.

The realm also turns on "Forgot password" (`resetPasswordAllowed`), so Keycloak's
own reset page stays available from its sign-in form, and "Verify email"
(`verifyEmail`), so a new account cannot sign in until the link in the
verification email is opened. Keycloak's reset link is valid for 15 minutes
(`actionTokenGeneratedByUserLifespan: 900`); the verification email that
user-service asks for after registration is valid for 12 hours
(`actionTokenGeneratedByAdminLifespan: 43200`). Each link works once. After a
password change or reset Keycloak offers "Sign out from other devices", which
ends the other sessions.

Keycloak sends mail through `smtpServer`, which reads `NEXUS_SMTP_HOST`,
`NEXUS_SMTP_PORT` and `NEXUS_SMTP_FROM` (defaults `mailpit`, `1025`,
`no-reply@nexus.local`, no login). Compose fills them from `KEYCLOAK_SMTP_*` in
`.env`. Locally every email lands in Mailpit at http://localhost:8025.

Password, reset, verification, execute-action, logout and failed-login events are
stored for 30 days (`eventsExpiration: 2592000`) and written to the Keycloak log;
admin events are stored without request bodies. Admin console: Realm settings,
then Events.

Realm import skips a realm that already exists. On an existing local stack run
the seed script once more; it applies the same settings with `kcadm`:

```powershell
.\keycloak\seed-users.ps1
```

## Repeatable verification

Requires Python 3 and Docker Compose. No extra Python packages or OpenSSL.

```powershell
py -3 keycloak/test-realm.py
py -3 keycloak/test-keycloak.py --config-only
py -3 keycloak/test-keycloak.py
# Full AC: also observe rejection near 15 minutes and automatic recovery.
py -3 keycloak/test-keycloak.py --full-lockout
```

If Python is installed without the Windows launcher, use `python` in place of
`py -3`. The offline suite checks the PowerShell entry point on Windows and the
Bash entry point on Unix. Bash remains inside the Linux Keycloak container.

The offline tests compare **every** privilege and mapping with V3, check token
mapper/security settings, and verify the host seed entry point does not execute
dotenv contents. On Unix, they also check missing-secret handling in the Bash
container script.

The `--config-only` check uses temporary environment files to verify that both
missing and blank payment settings allow the auth configuration to render. It
needs the Docker Compose CLI and starts no containers or database operations.

The integration test renders the real Compose configuration, then starts only
Postgres, Keycloak and the disposable seed client in a unique test project.
It uses random credentials and dynamically allocated loopback ports, never the
developer's `.env`, named containers, or database volumes. Cleanup removes only
resources belonging to that test project.

It exercises browser Authorization Code + PKCE for all demo accounts, verifies
JWT signatures against JWKS with OpenSSL, checks exact IDs/roles/privileges,
rejects missing/wrong PKCE and invalid redirects, tests password policy, and
proves the sixth correct-password attempt is blocked. The full mode waits the
real 900 seconds without weakening policy or manually unlocking the user.

Startup `--import-realm` deliberately skips a realm that already exists. Editing
`realm-nexus.json` and restarting does not overwrite an existing `nexus` realm.
Use the isolated test project above to validate changes without touching local data.

For #256, the existing seed command also installs the Promotions client privileges
and composite role grants in an existing realm. Re-run the seed and sign in again
to receive the new privileges in access tokens. User-service V7 removed the old
role/permission tables, so Keycloak is the live source for these grants.

To reload the JSON into a disposable local development setup, back up any needed
Keycloak data, stop auth-service, and reset **only `auth_db`**. This deletes all
Keycloak realms, users, credentials and sessions in that database, including
`master`. From `infra/local-dev`, replace `<DB_USERNAME>` with your local value:

```powershell
docker compose stop auth-service
docker compose exec postgres psql -U "<DB_USERNAME>" -d postgres -c "DROP DATABASE auth_db WITH (FORCE);" -c "CREATE DATABASE auth_db;"
docker compose up -d auth-service
.\keycloak\seed-users.ps1
```

Startup imports the updated JSON into the empty database, and the seed command
restores demo passwords from `.env`. Do not use `docker compose down -v` for this:
the shared PostgreSQL volume also holds the other services' databases. For a
database whose data must be retained, apply reviewed realm changes through the
Admin Console/API instead of resetting it.

The repository file is `realm-nexus.json` as requested by #236; Compose mounts
it as `/opt/keycloak/data/import/nexus-realm.json` following Keycloak's import
naming convention. See the official [import guide](https://www.keycloak.org/server/importExport)
and [administration guide](https://www.keycloak.org/docs/latest/server_admin/).
