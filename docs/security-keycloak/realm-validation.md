# Realm provisioning verification (#236)

Verified on 2026-11-03, 15:24:55–15:41:49 UTC+7, against an isolated
Keycloak 26.7.5 and PostgreSQL stack rendered from the current Compose file.
The source was refreshed from `develop` at `352987ba`; the exact tested file
SHA-256 values are saved by `test-keycloak.py --evidence-dir` so evidence remains
attributable when the unchanged working files are committed afterward.

## Changes since the original realm branch

- Restore realm import, explicit demo password seeding and the Keycloak files on
  the current service layout without removing payment, shipment or rating config.
- Match `RegisterRequest`'s Java `String.isBlank()` baseline for password policy.
  The old `\S` pattern accepted passwords containing only U+2003 EM SPACE.
  The policy now requires a character outside Java's `javaWhitespace` class.
- Supply isolated payment configuration values when rendering the shared Compose
  file; disabled services also undergo required-variable interpolation.
- Save sanitized, decoded runtime token claims, source hashes, UTC timestamps and
  measured lockout samples through `--evidence-dir`. No raw token or credential
  response is written. Long waits report progress at intervals of at most 30s.

## Results

| Check | Result |
| --- | --- |
| Offline realm/seed contract on Windows | 8 passed, 3 Unix Bash checks skipped |
| Startup/import and demo seeding twice | Passed; fixed UUIDs unchanged |
| ADMIN/Seller/Buyer real code + PKCE | Passed; exact privileges 56/28/30, respective fixed UUIDs and realm roles |
| Roles/privileges/audience contract | Passed; exact V3 matrix and `nexus-services` audience |
| RS256 signature/issuer/time | Verified against the running Keycloak JWKS |
| Missing/wrong PKCE, foreign redirect, password grant | Rejected |
| Password policy | Short, ASCII spaces, U+2003-only, newline/tab-only rejected; eight non-whitespace characters accepted |
| Five failed logins | Counters 1–5; disabled false for 1–4, true on 5 |
| Sixth attempt with correct password | Rejected |
| Near end of actual lockout | Correct password still rejected at 890.140s |
| Automatic recovery | Login + code exchange 200 and signed seller token verified at 903.156s |
| Cleanup | Only the isolated test project's containers, volume and network removed |

The full runtime test passed without skipped phases. The configured 900-second
lockout was retained throughout; no manual unlock or policy shortening was used.
Rejecting the sixth correct-password attempt demonstrates the account is locked,
independently of rejection caused by another incorrect password.

## Reproduce

From `be-project-nexus/infra/local-dev`, with Python 3 and Docker Compose:

```powershell
python keycloak/test-realm.py -v
python -u keycloak/test-keycloak.py --full-lockout --evidence-dir C:\path\to\empty-evidence-directory
```

Each evidence directory must be empty before the run. Retain `evidence.json`,
`summary.log` and the allowlisted token-claim files together. The claim files
represent real JWTs after signature verification; they contain no reusable token.

Startup import skips an existing realm. Updating this JSON and restarting does
not migrate a developer's existing realm; use the isolated verification project
or provision a fresh environment. Gateway issuer/JWKS cutover (#237), user-profile
cutover (#238) and frontend OIDC (#239) are separate integration tasks.
