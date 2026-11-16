# Nexus STAGING runbook — #162

**Status: implementation available; VM deployment and independent rehearsal
are pending.** No command below is claimed to have run on STAGING yet.
Local verification is recorded separately in the MR and issue evidence.

## 1. Host and scope

Complete [the written VM handover](staging-vm-handover.md) first: hostname,
access custodian, RAM/disk, payer, expiry and public DNS. Budget 16 GB RAM and
60 GB disk. DNS A and any AAAA record must reach this VM. Permit public TCP
80/443 and restrict SSH to operators. Docker Compose publishes **only nginx**;
PostgreSQL, Redis, Kafka, Eureka, Config, Keycloak, Zipkin and application ports
have no host binding. Docker-published ports can bypass UFW rules, so inspect
the Compose bindings and verify reachability from another machine too.

Linux prerequisites: Git, Docker Engine with Compose v2+, Bash, Python 3,
curl, util-linux (`flock`), outbound HTTPS, DNS and SMTP connectivity.
Use an account already authorized for Docker and this repository. Docker
builds need internet access and more temporary disk/RAM than steady state;
build images on a separate builder/registry if the VM cannot accommodate it.

The stack contains nine business services plus Config, Eureka and Gateway
(12 application JVMs), Keycloak, Kafka KRaft, Redis, Zipkin, Next.js and nginx.
The six PostgreSQL instances retain all ten logical databases used by develop:

| Compose instance | Logical databases |
| --- | --- |
| postgres-user | user_db, auth_db |
| postgres-catalog | catalog_db |
| postgres-commerce | commerce_db, payment_db |
| postgres-auction | auction_db |
| postgres-inventory | inventory_db |
| postgres-operations | notification_db, shipment_db, rating_db |

This explicitly covers the services added since the issue's “six databases”
estimate. Backups contain **ten** dumps, rather than omitting four databases
to make an obsolete count pass. One-shot realm/topic provisioning containers
exit with code 0; all long-running containers must be healthy.

## 2. First deployment

Clone the repository using your existing GitLab access, then check out the
reviewed full commit SHA from !183. Keep its old commit/image set available.
The host `.env`, `.state`, backups and evidence are ignored and survive normal
Git checkouts. Run the following from the repository root:

```bash
git fetch origin
git switch --detach REVIEWED_FULL_COMMIT_SHA
cd be-project-nexus/infra/staging
cp .env.example .env
chmod 600 .env
${EDITOR:-vi} .env
export RELEASE_TAG="$(git rev-parse HEAD)"
bash scripts/staging.sh preflight
bash scripts/staging.sh build
bash scripts/staging.sh tls-init
bash scripts/staging.sh deploy "$RELEASE_TAG"
```

Replace `REVIEWED_FULL_COMMIT_SHA` before running. Fill all required `.env`
values on the VM; do not paste them into GitLab or command logs. Generate
different random values for database, Redis, Keycloak clients, demo users,
cart HMAC and webhook secrets. `PAYOUT_ACCOUNT_ENCRYPTION_KEY` is base64 for
32 random bytes and must remain stable across releases. Obtain actual SePay
and SMTP settings from their owners; Supabase and carrier values stay blank
until those integrations are available. Empty Supabase disables working image
uploads; `SHIPPING_CARRIER=NONE` does not prove a carrier purchase/label flow.
For GHN, fill its token/shop ID, pickup province/ward/address and the existing
warehouse UUID before selecting `SHIPPING_CARRIER=GHN`. Payout uses the source's
`sandbox` provider by default; a real provider requires its reviewed URL/token.
Do not report the sandbox payout as a bank transfer.

`build` packages the current committed tree with JDK 21 and Node 24; backend
libraries are installed before service jars. Tags are the full Git SHA, not
`latest`. The frontend uses the same public origin for API calls and Keycloak
at `/auth`; mocks and unmerged account extensions are disabled. Config and
JWKS use private container URLs. Browser token issuer remains the **public**
`https://DOMAIN/auth/realms/nexus`, so the issuer and JWKS URL intentionally
differ. Do not point the browser at container names or localhost.

`tls-init` temporarily starts the HTTP-only ACME listener, obtains a valid
Let's Encrypt certificate with webroot validation, then removes that listener.
It does not mint a self-signed STAGING certificate. `deploy` then starts the
infrastructure, waits for Keycloak, provisions demo identities/client roles,
starts the apps and validates health, seeded products and unsigned webhook
401 before recording `.state/current`. The imported realm is prepared from
the tracked local realm with the STAGING redirect origin and SMTP. The seed
tool deliberately resets demo passwords to the host values; run this only on
STAGING, not on a production realm.

If the domain/SMTP configuration changes after the first import, Keycloak
does not re-import an existing realm. Update the existing realm/client through
the private admin CLI and recheck redirect/logout/SMTP settings; do not delete
the realm or database. See the realm administration command below.

Certbot checks renewal twice a day; nginx validates and reloads configuration
every six hours to load renewed certificates. Inspect both service logs and
run an ACME renewal dry-run after DNS has settled:

```bash
bash scripts/staging.sh compose logs --tail=100 nginx certbot
bash scripts/staging.sh compose run --rm --no-deps --entrypoint certbot certbot renew --dry-run --webroot -w /var/www/acme
```

## 3. Verify the deployment and browser

```bash
bash scripts/staging.sh compose ps --all
bash scripts/staging.sh verify
bash scripts/staging.sh restart-check
bash scripts/staging.sh compose port postgres-catalog 5432
bash scripts/staging.sh compose port api-gateway 8080
```

The last two commands must report **no binding** (a nonzero status is expected).
`restart-check` restarts the full Compose stack and checks that all nine domain
services and Gateway return to Eureka as UP inside 120 seconds. Config is not
an Eureka client; Eureka itself is the server. It subsequently checks health
and HTTP again. Save actual elapsed output, rather than assuming startup order
proves the recovery time.

From another machine, replace the hostname below and inspect valid TLS,
HTTP redirect and the unsigned webhook response:

```bash
curl -I https://STAGING_HOST/
curl -I http://STAGING_HOST/
curl -i -X POST -H 'Content-Type: application/json' --data '{}' https://STAGING_HOST/api/v1/webhooks/sepay
```

Expected: homepage 200 over valid TLS, HTTP 308 to HTTPS, webhook 401. No `-k`
is allowed for STAGING verification. nginx sends `/api/**` and `/.well-known/**`
to Gateway without stripping the path; ACME challenge paths are served locally.
Keycloak realm/resources use `/auth/`; its admin and management HTTP paths are
blocked publicly. `/actuator`, Swagger and internal API paths are not exposed.

Open HTTPS in a browser, register a new test identity and sign in. Check two
successful XHRs to this same origin's `/api/v1/...` (for example register and
session/profile), no mixed content, blocked CORS or certificate warning.
Export a **sanitized** HAR that omits authorization/cookie headers, passwords
and tokens. Do not attach an unsanitized authentication HAR. A local fixture
or successful `curl` is not this browser acceptance evidence.

From two real external client IPs, exercise one harmless GET route until
client A reaches 429; client B must still receive 200 independently. Wait for
the next rate window before retesting. Try a forged X-Forwarded-For header as
well: nginx overwrites it with the actual connection IP. Gateway's STAGING
`SERVER_FORWARD_HEADERS_STRATEGY=framework` makes its existing rate limiter use
that transformed remote address. This setting is safe only while Gateway has
no published port and every public request passes through nginx.

## 4. Deploy a new commit and roll back

Before upgrading, take a backup, record the previous `.state/current`, review
Flyway changes and ensure the old images remain locally available. Application
rollback requires backward-compatible migrations; this tool never reverses a
migration or restores a database automatically. Review migrations before the
upgrade if the previous application cannot read the new schema.

```bash
bash scripts/staging.sh backup "backups/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ)"
git fetch origin
git switch --detach NEXT_REVIEWED_FULL_COMMIT_SHA
export RELEASE_TAG="$(git rev-parse HEAD)"
bash scripts/staging.sh build
bash scripts/staging.sh deploy "$RELEASE_TAG"
```

If the candidate fails, preserve logs and run:

```bash
bash scripts/staging.sh rollback
bash scripts/staging.sh compose ps
cat .state/current
```

A failed candidate leaves `.state/pending`; rollback targets the last successful
release in `.state/current`. After a successful upgrade, rollback targets
`.state/previous`. Only application containers are replaced, using existing
images with `--no-deps --no-build --pull never`; persistent services/volumes are
not recreated. Verify image IDs using `compose images`, response behavior and
the stored SHA. Recheck schema/table data during a controlled rehearsal with
business traffic paused. Background jobs may still write business data; the
script does not promise a database snapshot freeze. Never use `down -v`, image
pruning or a database downgrade as a rollback shortcut.

## 5. Logs and trace lookup

```bash
bash scripts/staging.sh compose logs --since=10m --tail=200 commerce-service
bash scripts/staging.sh compose logs -f --tail=100 commerce-service
bash scripts/staging.sh compose logs --since=10m api-gateway commerce-service inventory-service payment-service | grep TRACE_ID
```

Replace `TRACE_ID` with a trace from one actual request. Use that same ID in the
downstream services, outbox/event-log correlation and Zipkin. Zipkin is private;
use a disposable curl client on the Compose network or an operator SSH tunnel,
then query `/api/v2/trace/TRACE_ID` at `zipkin:9411`. Log rotation caps each
container at three 10 MB files. Do not publish Zipkin or service ports to
collect evidence. Confirm the trace actually spans the requested services.

Private Keycloak administration uses a disposable CLI with the host credentials
and a temporary credential file. For example, inspect the configured realm and
web client with:

```bash
bash scripts/staging.sh auth-admin get realms/nexus --fields realm,sslRequired
bash scripts/staging.sh auth-admin get clients -r nexus -q clientId=nexus-web --fields id,redirectUris,webOrigins
```

For an origin change, use the returned client UUID with `auth-admin update clients/CLIENT_UUID
-r nexus -s 'redirectUris=["https://NEW_HOST/*"]' -s
'webOrigins=["https://NEW_HOST"]'` and update the post-logout redirect attribute
too. Public admin console routes are intentionally not provided. Changes to
SMTP/hostname must be reviewed and tested as configuration changes.

## 6. Backup and restore rehearsal

```bash
bash scripts/staging.sh backup "backups/manual-$(date -u +%Y%m%dT%H%M%SZ)"
cd backups/ACTUAL_BACKUP_DIRECTORY
sha256sum -c SHA256SUMS
cd ../..
bash scripts/staging.sh restore-test backups/ACTUAL_BACKUP_DIRECTORY/catalog_db.dump postgres-catalog scratch_catalog_restore
```

Replace the backup directory with the one just created. There must be ten
nonempty custom-format `pg_dump` files, grouped across all six instances,
plus the release SHA and checksums. Each dump is transactionally consistent
within its own database; this is not an atomic snapshot across all services.
Pause business writes/jobs first if the rehearsal needs cross-database
consistency. Restrict backups to operators and move an encrypted copy off-host
using the team's approved backup destination and retention policy.

Restore creates a **new** `scratch_` database and refuses existing or normal
business DB names. `pg_restore --exit-on-error` must finish, then compare the
restored schema and representative row counts/seeded products with the source.
The printed public-table count is a starting check, not proof all data matches.
Drop only this scratch database after recording the successful comparison.

## 7. Capacity and independent rehearsal

Complete one real test purchase and one auction on this VM. Record their IDs,
wait for steady activity, then follow [capacity measurement](staging-capacity.md).
Include every running container, disk, RAM, headroom and the three largest
consumers. Raise a shortfall below 20% that same day; do not silently shrink
heaps without inspecting GC/OOM and request behavior.

Have a teammate who did not author this runbook deploy the next reviewed
commit using only this document. Record elapsed time (target <=30 minutes),
every guessed step, actual command outputs, rollback image/data checks and
backup/restore comparisons. Correct ambiguities and repeat the affected steps.
This rehearsal and the STAGING command log are **pending**, not inferred from
the existence of this runbook.
