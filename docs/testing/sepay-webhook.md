# SePay webhook signature and memo matching — #155 / #156

`POST /api/v1/webhooks/sepay` is public at the gateway and payment-service.
The provider authenticates with `X-SePay-Signature` and `X-SePay-Timestamp`.
Payment/refund APIs keep their existing bearer-token requirements.

## Delivered behavior

The service verifies HMAC-SHA256 using the original body bytes before parsing
JSON or querying a payment session. The unchanged verifier signs the ASCII Unix
timestamp, one `.` byte, then the body, and accepts absolute clock skew up to
300 seconds. Missing, malformed, stale or incorrect authentication returns
`application/problem+json`, 401 `PAYMENT_UNAUTHENTICATED`, with detail
`The webhook signature is invalid`. Signed malformed/null/incompatible JSON
returns 400 `PAYMENT_INVALID_WEBHOOK_REQUEST`.

A nonempty normalized `code` is authoritative, even if it does not match.
Otherwise the service first looks up normalized `content` exactly. If that fails,
it removes one configured leading `SEPAY_TRANSFER_CONTENT_PREFIX` and retries
the exact lookup. Normalization uppercases using `Locale.ROOT` and removes
Unicode whitespace. Only 1–32 uppercase ASCII letters/digits are queried against
`payment_sessions.memo_code`; arbitrary memo substrings are not searched.

An accepted object returns native `{"success":true}`, including a missing,
invalid or unmatched memo. The controller uses `@ProtocolResponse`; this ACK
does not have an `ApiResponse` envelope. A no-match writes exactly one WARN with
the normalized memo, with control characters sanitized and length limited.
Neither the signature header nor the full request body is logged.

Matching alone performs no financial writes. After a successful match, #156
processes incoming receipts idempotently: exact payable-session funding stores
the SUCCESS transaction, funds every instruction and writes one group confirmation
in the same payment transaction. Amount mismatches and unpayable sessions store
UNRECONCILED receipts without a confirmation. Outgoing transfers are acknowledged
without funding. Commerce processes CheckoutPaymentConfirmed asynchronously.
See [checkout payment confirmation](checkout-payment-confirmation.md) for the
payment/commerce checks. A supplied invalid bearer token can still be rejected
by shared security; provider checks should omit the bearer header.

The [payment OpenAPI](../api/openapi/payment-service.yaml) is the wire contract.
The [gateway permissions](../api/gateway-permissions.md) document the exact public
method/path pair; sibling paths, child paths and other methods are not public.

## Automated checks

Use JDK 21. From `be-project-nexus`, install the shared libraries, then run the
focused tests and contract checks:

```powershell
mvn -f lib/pom.xml -DskipTests install
mvn -f payment-service/pom.xml '-Dtest=PaymentMatcherTest,SepayWebhookControllerTest,SepayWebhookConfigurationTest,WebhookSignatureVerifierTest' test
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/payment-service.yaml
```

The focused controller checks must include the real service security chain:
no bearer plus valid provider authentication succeeds; changing one signature
character returns the service's 401 code. Invalid authentication with malformed
JSON must still return 401, proving that parsing happens later. Matcher cases
cover case/whitespace, authoritative `code`, content fallback, configured prefix,
an exact memo that starts with the configured prefix, and no-match behavior.

For PostgreSQL persistence checks, use Docker Desktop with Linux containers, or
set `PAYMENT_DB_TEST=true` plus `PAYMENT_TEST_DB_URL`,
`PAYMENT_TEST_DB_USERNAME` and `PAYMENT_TEST_DB_PASSWORD` for a dedicated
throwaway database. Never point migration/fixture tests at a shared environment.
The existing support uses PostgreSQL 18 Testcontainers unless external test DB
mode is selected. Run:

```powershell
mvn -f payment-service/pom.xml '-Duser.timezone=UTC' '-Dtest=SepayWebhookPersistenceTest' test
```

The #155 persistence checks exercise matching/signature handling with the financial
confirmation collaborator isolated; they do not prove #156 settlement. Compare
sessions, instructions, transactions and outbox before/after unmatched and
rejected requests; use the #156 integration checks for a real matched receipt. Gateway
integration must forward the exact bytes/headers without a bearer token and
preserve the downstream `PAYMENT_UNAUTHENTICATED` response, rather than return
a gateway authentication error. Run the relevant gateway security tests and
the full payment-service `verify` suite before merging; these commands alone
are instructions, not a record of a completed run.

## Original captured delivery

[ADR-007](../adr/ADR-007-payment-provider.md) records the provider-sent Test Mode
capture and its earlier verifier results. The immutable fixture is
`docs/api/sepay-webhook-sample.json`; Git's path-specific `-text` attribute
preserves the signed bytes on Windows. Do not edit/reformat it, normalize line
endings, reserialize it or regenerate a signature to substitute for the original.

`WebhookCapturedDeliveryTest` is opt-in. In a shell with the matching secret and
original delivery metadata already supplied from their approved local storage,
set these environment variables without printing or committing their values:

- `SEPAY_WEBHOOK_SECRET`: the matching webhook secret.
- `SEPAY_CAPTURE_SIGNATURE`: original `X-SePay-Signature` header.
- `SEPAY_CAPTURE_TIMESTAMP`: original `X-SePay-Timestamp` header.
- `SEPAY_CAPTURE_TEST=true`: explicitly enable the captured-delivery tests.
- `SEPAY_CAPTURE_BODY_PATH` (optional): absolute path to a newer immutable raw
  capture outside Git. If omitted, the historical repository fixture is used.

The secret and original headers are intentionally outside Git. If any input is
missing, an ordinary test run skips these tests; a skipped test is not successful
capture acceptance. An enabled run with missing inputs must fail. From
`be-project-nexus`, run:

```powershell
mvn -f payment-service/pom.xml '-Dtest=WebhookCapturedDeliveryTest' test
```

The historical delivery uses a test-only fixed clock at its original timestamp;
production keeps the current clock and the same freshness check. Assert the
original body/signature succeeds before checking body/signature tampering, so
a stale or invalid baseline cannot make a rejection test pass accidentally.
The captured memo need not match a payment session: a genuine no-match still
returns 200. Use independent synthetic payloads for memo-match cases rather
than modifying this captured body. Test Mode capture evidence does not claim
a Live bank transfer or a staging deployment.

## Runtime configuration

Supply `SEPAY_WEBHOOK_SECRET` to payment-service through the runtime secret
environment. It is required and has no usable default. The application exposes
the exact POST as a public endpoint, and the verifier replaces bearer-token
authentication only for this callback. Keep the same receiving-bank/transfer
prefix configuration used by payment initiation. Configure the provider to send
JSON and HMAC-SHA256 headers; do not send secrets in URLs or request bodies.

For a live HTTP smoke check through the gateway, use a fresh provider delivery
or an independently signed synthetic request with the current timestamp.
Historical headers should fail the production freshness window. Check the
native 200 ACK, downstream signature 401 and one no-match WARN. A no-match or
rejected request leaves DB rows unchanged; a real matched incoming receipt can
fund its checkout under #156. Retain only redacted evidence. No real money transfer is required for
the captured-sample acceptance test.

## Reusable gateway smoke check

With a local gateway/payment-service running and the same **synthetic test
secret** supplied as `SEPAY_WEBHOOK_SECRET` to the service and the current shell,
run from `be-project-nexus`:

```powershell
node docs/testing/sepay-webhook-smoke.mjs
```

The script uses Node built-ins and synthetic payloads with fresh timestamps.
It sends no bearer token, starts no payment, and checks five responses: native
200 ACK, one-character signature mutation (401), bad signature plus malformed
JSON (401), correctly signed malformed JSON (400), and missing signature (401).
Every rejection must carry the payment-service code and callback `instance`;
a gateway missing-token error will fail this check. Output contains only the
case, status and a verified code or static failure identifier. No signature,
secret or full body is printed, including on network errors.

`SEPAY_WEBHOOK_SMOKE_BASE_URL` defaults to `http://127.0.0.1:8080`; set it to a
different local gateway origin if necessary. `SEPAY_WEBHOOK_SMOKE_MEMO` defaults
to `NXUNKNOWN155`; a controlled matched-session memo can trigger #156 financial
effects, so use a disposable fixture when overriding it.
The smoke check itself makes no database queries or state comparisons. For the
default no-match case, inspect the service log for one WARN and use the persistence
tests above to prove unchanged rows. It uses a synthetic signature, so a passing
run does not replace the original captured-delivery acceptance test.

## Verification recorded on 2026-11-06

The dated results below describe the matching-only #155 implementation at their
recorded commits. They do not claim settlement behavior or validate the #156
implementation added later.

Validation used JDK 21 and an isolated PostgreSQL 18.6 database. Maven `verify`
completed successfully for payment-service (186 tests: 183 passed, 3 skipped),
api-gateway (75 passed) and config-service (5 passed). The three opt-in original
captured-delivery tests were skipped because the original signature, timestamp
and matching secret were unavailable; this run does not satisfy real-capture
acceptance. ADR-007 retains the earlier provider-capture evidence.

The smoke script ran against the packaged gateway and payment applications on
separate loopback ports using the repository route/security configuration and a
synthetic test key. All five cases passed for a matching session and again for
an unmatched memo. No bearer token was sent. Database fingerprints before/after
were identical for payment_sessions, payment_instructions, payment_transactions,
outbox and refunds. The unmatched delivery produced exactly one WARN naming its
normalized memo; neither a signature nor a synthetic body marker appeared in
the service log.

The contract inventory passed with 62 operations (5 in payment-service), payment
OpenAPI passed Redocly 2.63.0 lint, and whitespace checks passed. The original
verifier remained unchanged. The signed sample retained its ADR-007 SHA-256
`582BCD18DC4727DF28C6ABB8D0A0B86058BCE1BE77E5AC6E7EB5C72EAFD2493A` and Git
`text: unset` attribute. No staging deployment or real bank transfer was performed.

## Fresh provider capture and acceptance — 2026-11-07

The user triggered an incoming simulated transaction in SePay Test Mode. The
dashboard reported one successful webhook delivery, and the temporary receiver
accepted it with the unchanged verifier and current system clock at
`2026-11-07T13:29:13.102598500Z` (20:29:13 Asia/Saigon). The received body is
310 bytes with SHA-256
`232E5E78688074700F6E06E76CC07F9334D47DE56205C30360ECEEDE8D6F6B56`.

Using that exact body and original signature/timestamp, the expanded
`WebhookCapturedDeliveryTest` completed **3/3 PASS, 0 failures, 0 errors,
0 skipped**, on JDK 21 at 20:34:24 Asia/Saigon. It proves:

- The real provider delivery reaches the actual controller/security filter
  without a bearer token and returns native HTTP 200 `{"success":true}`.
- Changing one signature hex character returns 401 `PAYMENT_UNAUTHENTICATED`
  before any session repository access.
- Changing one body byte invalidates its original signature.

The endpoint test uses the original signing time as its test-only clock and a
mock session repository. It verifies a genuine no-match acknowledgement, not a
database settlement. The capture receiver's initial verification used the
current clock, so accepting the original delivery did not bypass freshness.

Evidence is retained outside Git at
`D:/KyOJT/.artifacts/sepay-captures/issue-155-20261107/`: immutable `body.json`,
original `headers.txt`, `received-at.txt`, and `captured-tests.log`. The signing
secret is in `webhook-secret.dpapi`, encrypted for the current Windows user on
this machine. The local `run-captured-tests.ps1` decrypts it, loads the metadata
and external body path, runs the three tests and restores the process environment.
Do not commit this evidence directory or print its authentication inputs.
DPAPI is a local retention mechanism; a portable team handover still requires
an approved shared secret store.

```powershell
& 'D:\KyOJT\.artifacts\sepay-captures\issue-155-20261107\run-captured-tests.ps1'
```

This verifies the **new 310-byte provider capture**, using
`SEPAY_CAPTURE_BODY_PATH`. The old 305-byte repository fixture and its hash are
unchanged; its original signing inputs were not recovered or reverified. No
staging deployment or Live bank transfer was required for this acceptance.

## Develop integration verification — 2026-11-08

Integrated `origin/develop` at `2100394` into the #155 branch. The merge retained
the new payment-initiation `transferContent`/`serverNow` fields and the system
health APIs alongside the webhook, with no source conflicts.

On JDK 21, full payment-service `clean verify` passed **187 tests, 0 failures,
0 errors, 0 skipped**, including all three real provider-capture tests. Migrations
and Hibernate `ddl-auto: validate` ran against a new isolated PostgreSQL 18.6
database. Gateway `verify` passed **75/75** and config-service `verify` passed
**5/5**. The merged contract inventory passed with **64 operations** and payment
OpenAPI passed Redocly 2.63.0 lint.

The rebuilt gateway and payment-service applications also passed all five smoke
cases for a matched memo and all five for an unmatched memo on separate local
ports. A request without Authorization and with an altered signature returned
service 401 `PAYMENT_UNAUTHENTICATED`. Fingerprints of all five payment tables
were unchanged; the unmatched request produced exactly one WARN and the matched
request produced none. Signature, secret and full-body markers were absent from
application logs. Both temporary application processes were stopped afterward.

The first payment verification attempt reused the earlier test database and
encountered 12 refund-test idempotency conflicts because those tests retain fixed
keys between runs. Repeating the full suite against a newly created empty test
database passed without production or test-code changes. For a full external-DB
run, use a fresh dedicated database rather than one containing previous suite
data. Testcontainers normally provides this isolation automatically.

Integration logs are retained outside Git at
`D:/KyOJT/.artifacts/issue155-integration-20261108/`.
