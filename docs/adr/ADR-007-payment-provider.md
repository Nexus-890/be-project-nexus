# ADR-007: Payment Provider — SePay

**Status:** Proposed; operational acceptance and captured-delivery verification are blocked.

**Date:** 2026-10-17

**Scope:** SePay integration spike; not payment initiation or the Sprint 4 webhook endpoint.
**Reviewers:** @fsa_VinhDP, @fsa_Chung

## Context and decision

Use **SEPAY** for VietQR bank-transfer payments. This intentionally deviates from
SRS 2.6 and BR-110, whose `PAYMENT_PROVIDER` default is **STRIPE**. The current
endpoint catalogue already specifies SEPAY for PAY-01/PAY-02. This ADR records
that deviation; it does not change runtime provider selection, which belongs
to #120, or implement VietQR initiation (#121).

Choose HMAC rather than API-key-only authentication so that changing the request
body invalidates its signature. Do not substitute the separate payment-gateway
IPN protocol for the bank-transfer webhook protocol.

## Account, bank and test-transfer ownership

These entries require the named person's agreement and dashboard evidence.
Neither a reviewer mention nor a task assignment proves account ownership.

| Required fact | Confirmed value / evidence |
|---|---|
| SePay account / credential holder | **Pending: not supplied** |
| Credential storage and authorized readers | **Pending**; never put credentials in Git or this ADR |
| Receiving bank and account reference | **Pending: not supplied** |
| Bank-account owner | **Pending: not supplied** |
| People permitted to read its statement | **Pending: not supplied** |
| Dashboard shows receiving bank configured | **Not verified**; no authenticated dashboard session available |
| Person paying for test transfers | **Pending: not supplied** |
| Agreed amount per transfer (VND) | **Pending: not agreed**; do not infer from example transactions |

On 2026-10-17, opening `https://my.sepay.vn` in the available browser redirected
to `/login` and returned HTTP 403 with a security-verification page. No account
was registered, bank linked, payment made or webhook configured by this spike
session. Account registration, bank consent/OTP and the transfer agreement remain
prerequisites, not completed subtasks.

## Signature contract

The [official bank-transfer webhook authentication documentation](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc)
specifies:

| Item | Contract |
|---|---|
| Algorithm | HMAC-SHA256 with the configured webhook secret |
| Signed input | ASCII timestamp, one `.` byte, then original request-body bytes |
| Signature header | `X-SePay-Signature: sha256=<64 lowercase hexadecimal characters>` |
| Timestamp header | `X-SePay-Timestamp: <Unix seconds>` |
| Clock policy | Accept an absolute difference of at most 300 seconds, including the boundary |
| Secret custody | Inject into the verifier; keep outside source control |

`WebhookSignatureVerifier` verifies the raw bytes and compares digest bytes in
constant time. It rejects invalid input before any business action. Keep JSON
parsing, memo matching, order updates and delivery deduplication outside this
utility. A valid signature alone does not prevent duplicate processing.

Do not trim/reformat the body, normalize CRLF, escape Unicode, or serialize a
parsed JSON object before verification. A receiver must supply the two original
header values. Historical capture tests must use a fixed `Clock` at the signing
timestamp rather than disabling the timestamp check.

## Sandbox and capture

SePay **has a Test Mode sandbox**, according to its
[Test Mode documentation](https://docs.sepay.vn/test-mode.html). Test accounts,
webhooks, API tokens and simulated transactions are separate from Live.
[Test Mode webhook creation](https://developer.sepay.vn/vi/tien-ich-khac/test-mode/tao-webhook)
also supports HMAC. Availability for the team's account is not dashboard-verified.

Use a temporary HTTPS tunnel to a local receiver because STAGING is not available
until Sprint 4. Point a Test Mode webhook at that URL, select JSON and HMAC, then
trigger a transaction in SePay. A simulated transaction actually delivered by
SePay is a **provider-sent Test Mode delivery**, not a real bank transfer. A Live
bank-connection smoke test remains a separate operational check using the agreed
payer and amount above. If reviewers require the fixture to originate from a
Live transfer, collect that only after the bank/transfer agreement is confirmed.

The [webhook integration guide](https://docs.sepay.vn/tich-hop-webhooks.html)
requires HTTP 200/201 with `{"success":true}` within 30 seconds for successful
acknowledgement. Confirm retry behavior from delivery history; provider transport
retries are not the business setting `PAYMENT_RETRY_LIMIT`.

### Captured sample: not yet available

`docs/api/sepay-webhook-sample.json` is deliberately **absent**: no delivery from
SePay has been received. The synthetic bytes in the unit test are explicitly not
a captured sample and must not be copied to this path to satisfy acceptance.

When a real delivery is available:

1. Save the exact request-body bytes to that path (relative to `be-project-nexus`),
   without wrapping headers into the JSON or appending a newline.
2. Record its environment, received-at UTC, available delivery/transaction IDs,
   `X-SePay-Signature`, `X-SePay-Timestamp`, SHA-256 body hash and dashboard delivery
   history evidence here. Do not record the secret.
3. Preserve fixture bytes through Git checkout (a path-specific `-text` attribute
   if needed). If privacy requires changing bytes, collect a suitable Test Mode
   delivery instead; editing a signed body invalidates its original signature.
4. Add a unit test using that file, its actual headers, a fixed `Clock`, and the
   matching secret loaded outside Git. Assert success, then flip one body byte
   and assert failure with the same headers. Missing acceptance-test inputs must
   not be reported as a passing captured-delivery test.
5. Close the temporary tunnel/receiver and disable its webhook after capture.

No production callback, tunnel script, SDK, new dependency, fixture metadata file,
provider abstraction or config change is introduced while capture is blocked.

## Payment issue re-estimation

These are **provisional engineering estimates from this spike's documented
protocol and scope review**, not completed provider-validation estimates. Revisit
after actual capture and bank onboarding; account/OTP waiting time is excluded.
No GitLab issue estimate or checklist has been changed: its URL/access is missing.

| Issue | Previous individual estimate | Revised estimate (dev-day) | Breakdown and dependency |
|---|---|---:|---|
| #120 — provider interface/config | Unavailable from supplied material | 1.0 | 0.5 provider/config selection + 0.5 tests; consume ADR, no verifier rewrite |
| #121 — VietQR initiation | Unavailable from supplied material | 2.0 | 1.0 instructions/memo/deadline + 0.5 repeat/ownership handling + 0.5 tests; needs #120, order creation and bank details |
| #155 — signature/memo matching | Unavailable from supplied material | 2.0 | 0.5 raw-body/verifier integration + 0.5 gateway/ACK handling + 0.5 memo matching + 0.5 tests; needs captured delivery and #121 |
| #156 — idempotent PAID update | Unavailable from supplied material | 2.0 | 0.5 persistence/uniqueness + 0.5 atomic state/event update + 1.0 duplicate/concurrency/rollback tests; needs #155 and order/event contracts |
| #157 — UNRECONCILED transactions | Unavailable from supplied material | 1.0 | 0.5 late/wrong-amount/unmatched handling + 0.5 tests; needs #155/#156 transaction handling |

Confidence is **low until capture and operational prerequisites are verified**.
These numbers must be reconciled with the current GitLab issue scope/estimates
before committing sprint capacity; no claim is made that they are agreed estimates.

The older backlog has F05.6 at 4 dev-day in S3 and F05.7 at 5 dev-day in S4.
Those are aggregate legacy features, not the five issue estimates above. The newer
endpoint catalogue places #120/#121 in **Sprint 2** and #155–157 in **Sprint 4**.
Do not move current work to Sprint 3 based on the older sheet.

Sandbox support means routine Sprint 4 regression can use provider-sent test
transactions rather than paid transfers. Keep a Live-bank smoke checkpoint and
owner availability in that sprint's plan. If account-specific HMAC/Test Mode access
differs from the docs, retain the blocker and revise estimates; API-key-only auth
is not a replacement for the body-tampering acceptance criterion.

## Validation and acceptance tracking

Run using the project's Java 21 toolchain, from `commerce-service`:

```bash
./mvnw -Dtest=WebhookSignatureVerifierTest test
./mvnw verify
```

The deterministic test vector was signed independently with Python's standard
`hmac`/`hashlib`, not with the Java verifier under test. Tests cover exact UTF-8
bytes, single-byte mutations, wrong key/headers, body reserialization, missing or
malformed input, timestamp bounds and the inclusive five-minute window. This is
protocol-level validation only, not proof of receiving a provider delivery.

Observed locally on 2026-10-17, using Java 21:

- Targeted verifier suite: **40 tests, 0 failures, 0 errors, 0 skipped**.
- Commerce `./mvnw verify`: **50 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Verifier compilation with `javac -Xlint:all -Werror`: passed.
- Config Service started and returned HTTP 200 for its health endpoint.
- Commerce started against PostgreSQL with the existing `ddl-auto: validate`
  configuration, using Config Service and with Eureka registration disabled for
  this local smoke check; `GET /actuator/info` returned HTTP 200. No schema or
  application configuration was changed.

The user confirmed that the requested access/capture information is not available.
Consequently, no provider delivery or GitLab checkbox screenshot can be claimed.

### Subtasks

- [ ] Register the SePay account and record its credential holder.
- [ ] Name and configure the receiving bank account and its owner.
- [ ] Point a webhook at a tunnel and capture an actual SePay delivery.
- [ ] Commit that raw body as `docs/api/sepay-webhook-sample.json`.
- [ ] Complete the verifier's unit test against the captured delivery (the verifier and synthetic tests are implemented; real-capture validation is not).
- [ ] Finalize the five revised issue estimates against capture evidence and current GitLab values (provisional estimates are recorded above).

### Issue acceptance criteria

- [ ] Dashboard shows receiving bank configured; ADR names the credential holder.
- [ ] Sample JSON is a body SePay actually sent.
- [ ] Verifier accepts that captured body with its actual signature headers.
- [ ] That same captured body with one byte changed fails verification.
- [ ] ADR names a person and an agreed amount for test transfers.
- [ ] Payment estimates are finalized, citing this spike.

None of these end-to-end criteria is checked on the strength of synthetic tests.
Only tick an item after the linked evidence exists, then take the GitLab screenshot.

## References

- [SRS v1.0](https://drive.google.com/file/d/1nw7_WwZoWhjgA1WiZJzu37ps_MisI5Ls/view): 2.6, 3.4.2, 5.3.
- [Business Rules](https://docs.google.com/spreadsheets/d/1x2ulrpa9n5Xmgb8G7b3zJ9sisHjYJG7wGycalQGHB-U/edit): BR-109–114, BR-118, BR-127–128.
- [Endpoint catalogue](https://drive.google.com/file/d/1KIJsr87F-6TWieBbie9CVzgCBTPsM4Zk/view), modified 2026-10-16: PAY-01/PAY-02, #120/#121/#155–157.
- [Legacy backlog](https://drive.google.com/file/d/1r-7RETPihuO3FolSjZxtcZOPmk4pc5NE/view): F05.6/F05.7.
- [Group report](https://drive.google.com/file/d/1AS18TaHffK5QhxaVyX2q7S8U9pAm1OBJ/view): planning row `#119 Sepay`; confirm the spike issue's URL before linking/closing it.

**Consequences:** verification can be developed without Spring, DB or paid transfers;
this spike remains incomplete until operational facts, a provider capture, its
passing test and final estimates are supplied. #120/#121/#155–157 own subsequent
production integration; this change does not implement them.
