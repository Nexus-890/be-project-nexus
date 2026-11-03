# Nexus Keycloak realm (#236)

## Run locally

From `be-project-nexus/infra/local-dev`:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# Fill DB_*, KEYCLOAK_* and the three DEMO_*_PASSWORD values in .env.
docker compose up -d postgres auth-service
.\keycloak\seed-users.ps1
```

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
service account is enabled; browser, implicit and password grants are off. The
service account holds the `realm-management` roles `manage-users`, `view-users`,
`query-users` and `view-realm`, which user-service needs to create accounts,
assign the registration role and read role mappings. The client is not in
`realm-nexus.json`, so an existing realm gets it on the next seed run.

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

## Repeatable verification

Requires Python 3 and Docker Compose. No extra Python packages or OpenSSL.

```powershell
py -3 keycloak/test-realm.py
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
