# #162 implementation validation

Baseline: `develop` **d8dbe6815150b1912c59517167b4cdd74212ba70**.
The existing !183 branch was fast-forwarded from aa238638 before implementation.
No VM handover or public STAGING hostname is confirmed. Results here are local.

## Checks performed

| Check | Result and boundary |
| --- | --- |
| Compose config with disposable dotenv fixture | Pass; 12 app JVMs, six PostgreSQL instances, only nginx publishes host ports |
| Config rejection probes | Four pass: published DB port, missing heap, debug profile and laptop hostname are rejected |
| Bash, Python and Node script syntax | Pass |
| Maven library install and 12 service packages | Pass using JDK 21; packaging used `-DskipTests`, not a claim that domain tests ran |
| Gateway full Maven suite | 88 pass, no failures/errors/skips; includes direct IP, transformed IPv4/IPv6 and unresolved-hostname cases |
| Native nginx 1.28.0 / TLS smoke | 29 assertions pass, using fixture upstreams and a disposable local CA certificate |
| Frontend unit suite | 389 pass, no failures/skips |
| Production dependency audit | Zero reported vulnerabilities after patching Next/eslint-config-next to 16.4.6, Zod to 4.6.6 and follow-redirects to 1.16.3 |
| Frontend generated API types, lint, TypeScript | Pass |
| Frontend production build | Webpack standalone build passes with patched dependencies |
| Standalone runtime | Homepage and three rendered JS/CSS assets return HTTP 200; backend integration not exercised |
| Frontend browser gate | 58 pass, 56 configured skips with a fresh dependency directory and Turbopack; HTTP fixtures, not STAGING |

The nginx test substitutes only listen ports, upstream addresses, domain,
ACME/certificate filesystem paths for local execution. It verifies path/query
preservation, HTTP 308, TLS, public/internal route boundaries, two distinct
socket IPs and removal of forged Forwarded/Prefix/SSL headers. Its webhook
401 comes from a fixture and is **not** a live SePay acceptance result.

The new Gateway regression first reproduced a null rate-limit key after
`ForwardedHeaderTransformer`: Spring supplies an unresolved numeric socket
address. The resolver now parses only numeric IPv4/IPv6 literals without DNS.
Direct mode still ignores raw forwarding headers; a hostname remains rejected.
STAGING's framework transformer is permitted only behind nginx with no public
Gateway binding and overwritten forwarding headers.

The original production audit reported Next as critical and Zod/follow-redirects
as high. The patch update addresses those runtime dependencies before a public
deployment; `npm audit --omit=dev` now reports zero. This is the production audit
scope, not a claim that all development-tool advisories were resolved. Existing
review worktrees retain their dependency directories; this branch has its own.

## Reproduce

With Docker available and a filled host `.env`, validate the resolved model
without printing credentials:

```bash
cd infra/staging
bash scripts/staging.sh check
```

From `be-project-nexus`, use JDK 21:

```bash
mvn -B -ntp -f lib/pom.xml -DskipTests install
mvn -B -ntp -f api-gateway/pom.xml test
node infra/staging/tests/nginx-smoke.mjs
```

The nginx test needs `nginx` and `openssl` on PATH; `NGINX_BINARY` and
`OPENSSL_BINARY` can select explicit binaries. It starts only local ephemeral
listeners, uses fixture upstreams and removes its disposable private key.
Run the normal frontend type/lint/unit/browser gates from `fe-project-nexus`.
For the production artifact, set `NEXUS_STANDALONE=true` and the public OIDC
base URL, run `npm run build -- --webpack`, then copy `public` and `.next/static`
into `.next/standalone` as the Dockerfile does before starting `server.js`.

## Not verified

- Docker Desktop failed to start and WSL returned an environment error locally.
  Compose model validation worked; Docker image builds and full stack startup
  did not run. Native JAR packaging is not an image build.
- No deploy to VM, Let's Encrypt issuance/renewal dry-run, public valid TLS,
  signup/signin HAR, two real external rate-limit clients, external SePay
  401, firewall probe or 120-second full-stack recovery has been observed.
- No STAGING purchase/auction warm-up, memory/disk/headroom measurement,
  database backup/restore, rollback or independent teammate rehearsal ran.
- The literal `git grep -iE "password|secret|key" infra/staging/` criterion is
  not claimed to pass: it also matches environment-variable references,
  validation code and certificate file paths. Operator secret values are
  supplied by the ignored host `.env`; the previous demo passwords were removed.

Use [the runbook](staging-runbook.md), [VM handover](staging-vm-handover.md)
and [capacity status](staging-capacity.md) to complete those checks after the
actual environment is available. Leave their issue checkboxes unchecked.
