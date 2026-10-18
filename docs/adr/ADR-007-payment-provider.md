# ADR-007: Payment Provider — SePay

**Status:** Proposed; verifier implemented, provider capture and operational evidence pending.

**Date:** 2026-10-17; updated 2026-10-18

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
| SePay account / credential holder | Nguyễn Trường An reports registering a free account on 2026-10-18; credential custody confirmation is still pending |
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
session. On 2026-10-18, Nguyễn Trường An confirmed account registration and reported
preparing the receiving bank connection. The user later reported creating a
Test Mode bank account and configuring its webhook. The current capture path is
Test Mode; dashboard evidence, Live bank connection and the transfer agreement
remain pending.
Registration is reported complete, but the combined registration/custodian subtask
below stays unchecked until credential custody is confirmed.

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
trigger a simulated transaction in SePay. Live Mode is a separate check using the
agreed test transfer after bank connection is confirmed. A simulated transaction
actually delivered by SePay is a **provider-sent Test Mode delivery**, not a real
bank transfer. A Live bank-connection smoke test remains an operational check using the agreed
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
4. Run `WebhookCapturedDeliveryTest` using that file, its actual headers, a fixed
   `Clock`, and the matching secret loaded outside Git (commands below). It checks
   success, then flips one body byte and checks failure with the same headers.
   Missing acceptance-test inputs must not be reported as a passing capture test.
5. Close the temporary tunnel/receiver and disable its webhook after capture.

No production callback, committed tunnel script, SDK, new dependency, fixture
metadata file, provider abstraction or config change is introduced. The following
temporary receiver exists only for this spike; it does not update orders or money.

### Local capture procedure (Windows PowerShell)

Use **Windows PowerShell 5.1 or PowerShell 7**, not Command Prompt, Bash or WSL.
Run both receiver and tunnel on the same Windows machine. Install JDK 21 and
[cloudflared for Windows](https://developers.cloudflare.com/tunnel/downloads/#windows),
with `cloudflared.exe` available on `PATH`. No Config Service, database or running
Commerce application is needed for this temporary receiver.

#### 1. Start the receiver in the first PowerShell window

Enter your actual repository and installed JDK paths when prompted; no drive or
installation directory is assumed. Port **8787** and path **`/sepay-spike`** come
from the existing receiver below. Stop if the port is occupied; do not replace
another process or silently use a different port.

```powershell
$ErrorActionPreference = 'Stop'
$repo = Read-Host 'Absolute path to your repository checkout'
Set-Location (Join-Path $repo 'be-project-nexus\commerce-service')
$env:JAVA_HOME = Read-Host 'Absolute path to your installed JDK 21'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $javac)) {
    throw 'JAVA_HOME must point to a JDK containing bin\java.exe and bin\javac.exe'
}
$version = & $java --version
if ($LASTEXITCODE -ne 0 -or ($version -join "`n") -notmatch '(?m)^(openjdk|java) 21(?:[.\s]|$)') {
    throw 'Select JDK 21 before continuing'
}
$version
if (Get-NetTCPConnection -State Listen -LocalPort 8787 -ErrorAction SilentlyContinue) {
    throw 'Port 8787 is already occupied; inspect the existing listener first'
}
.\mvnw.cmd '-DskipTests' compile
if ($LASTEXITCODE -ne 0) { throw 'Maven compilation failed' }
$env:SEPAY_CAPTURE_DIR = Join-Path $env:TEMP ('sepay-capture-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $env:SEPAY_CAPTURE_DIR | Out-Null
Write-Host "Capture directory: $env:SEPAY_CAPTURE_DIR"
```

Enter the **same HMAC secret** you configure in SePay. The masked prompt keeps it
out of command history; it is passed through this process's environment, not
written to a file or Git. These commands use
[Read-Host -AsSecureString](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/read-host).

```powershell
$secureSecret = Read-Host 'SePay webhook HMAC secret' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureSecret)
try {
    $env:SEPAY_WEBHOOK_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secureSecret.Dispose()
}
if ([string]::IsNullOrWhiteSpace($env:SEPAY_WEBHOOK_SECRET)) { throw 'HMAC secret is required' }
$source = @'
import com.group5.commerceservice.payment.WebhookSignatureVerifier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

class WebhookCapture {
    public static void main(String[] args) throws Exception {
        var verifier = new WebhookSignatureVerifier(System.getenv("SEPAY_WEBHOOK_SECRET"));
        Path directory = Path.of(System.getenv("SEPAY_CAPTURE_DIR"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8787), 0);
        server.createContext("/sepay-spike", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals("/sepay-spike")) {
                    reply(exchange, 404); return;
                }
                if (!exchange.getRequestMethod().equals("POST")) {
                    reply(exchange, 405); return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
                if (body.length > 1_048_576) { reply(exchange, 413); return; }
                String signature = exchange.getRequestHeaders().getFirst("X-SePay-Signature");
                String timestamp = exchange.getRequestHeaders().getFirst("X-SePay-Timestamp");
                if (!verifier.verify(body, signature, timestamp)) { reply(exchange, 401); return; }
                if (Files.exists(directory.resolve("body.json"))) { reply(exchange, 409); return; }
                Files.write(directory.resolve("body.json"), body, StandardOpenOption.CREATE_NEW);
                Files.writeString(directory.resolve("headers.txt"),
                        "X-SePay-Signature: " + signature + "\nX-SePay-Timestamp: " + timestamp + "\n",
                        StandardOpenOption.CREATE_NEW);
                Files.writeString(directory.resolve("received-at.txt"), Instant.now().toString(),
                        StandardOpenOption.CREATE_NEW);
                reply(exchange, 200);
                System.out.println("Verified delivery captured; stop the tunnel and disable its webhook.");
            }
        });
        server.start();
        System.out.println("Spike receiver: http://127.0.0.1:8787/sepay-spike");
    }

    private static void reply(HttpExchange exchange, int status) throws java.io.IOException {
        byte[] response = (status == 200 ? "{\"success\":true}" : "{\"success\":false}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }
}
'@
$receiverSource = Join-Path $env:SEPAY_CAPTURE_DIR 'WebhookCapture.java'
[System.IO.File]::WriteAllText($receiverSource, $source, [System.Text.UTF8Encoding]::new($false))
& $java --class-path 'target\classes' $receiverSource
```

Keep this window running until capture. Its environment variables are not shared
with a separate PowerShell window. The Java source is written as UTF-8 without a
BOM using a literal here-string; the receiver itself is unchanged.

#### 2. Start the tunnel in a second PowerShell window

```powershell
Get-Command cloudflared.exe -ErrorAction Stop
cloudflared.exe tunnel --url http://127.0.0.1:8787 --no-autoupdate
```

Wait for the HTTPS URL and a registered connection in the tunnel output. Use the
URL printed by **this Windows process**, append `/sepay-spike`, and enter that
complete URL in SePay. Do not reuse a URL from another session or the Linux agent
machine. Restarting a Quick Tunnel may produce a different hostname.

In SePay **Test Mode**, select incoming transfers, your test bank account, JSON
and HMAC-SHA256 with the same secret entered above. A provider-sent Test Mode
transaction is not a real bank transfer. Live testing is optional and still
requires confirmed bank connection and the named payer/amount agreement.

#### 3. Check HTTP POST in a third PowerShell window

Use `curl.exe` explicitly: `curl` may be a PowerShell alias. These **unsigned**
POST checks must return **401** with `{"success":false}`; they check connectivity
without consuming the first valid capture.

```powershell
curl.exe -i --request POST --header 'Content-Type: application/json' --data-binary '{}' 'http://127.0.0.1:8787/sepay-spike'
$publicBaseUrl = Read-Host 'HTTPS base URL printed by your running cloudflared (without /sepay-spike)'
$webhookUrl = $publicBaseUrl.TrimEnd('/') + '/sepay-spike'
Write-Host "SePay webhook URL: $webhookUrl"
curl.exe -i --request POST --header 'Content-Type: application/json' --data-binary '{}' $webhookUrl
```

Now trigger a simulated transaction in SePay. The first correctly signed delivery
returns **200** with `{"success":true}` and saves `body.json`, `headers.txt` and
`received-at.txt` in the capture directory. Later valid requests return 409 rather
than overwriting evidence. The acknowledgement proves receipt/signature
verification, **not** an order update, wallet credit or transaction insertion.
After capture, disable the temporary webhook and stop the receiver and tunnel
with Ctrl+C in their respective windows. Keep the first PowerShell window open
for the next step so its capture-directory and secret values remain available.

#### 4. Run captured-delivery tests in the first PowerShell window

Run only after a genuine SePay delivery. Stay in `commerce-service` and reuse the
same first PowerShell session after stopping its receiver. Copy bytes directly:
do not use `Get-Content | Set-Content`, JSON reserialization or editor formatting
for the captured body.

```powershell
New-Item -ItemType Directory -Path '..\docs\api' -Force | Out-Null
$capturedBody = Join-Path $env:SEPAY_CAPTURE_DIR 'body.json'
$sample = Join-Path (Get-Location).Path '..\docs\api\sepay-webhook-sample.json'
Copy-Item -LiteralPath $capturedBody -Destination $sample
$headers = @{}
foreach ($line in (Get-Content -LiteralPath (Join-Path $env:SEPAY_CAPTURE_DIR 'headers.txt'))) {
    $parts = $line -split ': ', 2
    if ($parts.Length -eq 2) { $headers[$parts[0]] = $parts[1] }
}
$env:SEPAY_CAPTURE_SIGNATURE = $headers['X-SePay-Signature']
$env:SEPAY_CAPTURE_TIMESTAMP = $headers['X-SePay-Timestamp']
$originalHash = (Get-FileHash -LiteralPath $capturedBody -Algorithm SHA256).Hash
$sampleHash = (Get-FileHash -LiteralPath $sample -Algorithm SHA256).Hash
if ($originalHash -ne $sampleHash) { throw 'Copied fixture bytes do not match the original delivery' }
$env:SEPAY_CAPTURE_TEST = 'true'
try {
    .\mvnw.cmd '-Dtest=WebhookCapturedDeliveryTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Captured-delivery verification failed' }
} finally {
    Remove-Item Env:SEPAY_CAPTURE_TEST -ErrorAction SilentlyContinue
}
Get-FileHash -LiteralPath $sample -Algorithm SHA256
```

Record the actual headers, hash, received-at time, **Test Mode** environment and
delivery-history evidence in this ADR before committing the raw body. Do not
redact a signed body; obtain a suitable provider-sent Test Mode delivery instead
if publishing it is unsuitable. Preserve its exact bytes through Git as described
above, including on Windows checkouts. After tests, clear credentials from this
PowerShell session (keep the original in your approved local secret store):

```powershell
Remove-Item Env:SEPAY_WEBHOOK_SECRET, Env:SEPAY_CAPTURE_SIGNATURE, Env:SEPAY_CAPTURE_TIMESTAMP -ErrorAction SilentlyContinue
```

## Payment issue re-estimation

These are **provisional engineering estimates from this spike's documented
protocol and scope review**, not completed provider-validation estimates. Revisit
after actual capture and bank onboarding; account/OTP waiting time is excluded.
No GitLab issue estimate or checklist has been changed: authenticated tracker
access and the specific issue URL are still unavailable.

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

Run in Windows PowerShell using the project's Java 21 toolchain, from
`commerce-service`, with `JAVA_HOME` configured as above:

```powershell
.\mvnw.cmd '-Dtest=WebhookSignatureVerifierTest' test
if ($LASTEXITCODE -ne 0) { throw 'Verifier unit tests failed' }
.\mvnw.cmd verify
if ($LASTEXITCODE -ne 0) { throw 'Commerce verification failed' }
```

`WebhookCapturedDeliveryTest` is opt-in via `$env:SEPAY_CAPTURE_TEST = 'true'`. The two tests
are **skipped**, not passed, in ordinary builds without that flag. Once enabled,
missing sample/headers/secret fail the build instead of silently skipping. The
tests read the original file bytes and never generate or re-sign a fixture. A
fixed clock at the original signing time preserves timestamp validation for a
historical capture. The tampering test also requires a valid original baseline.

The deterministic test vector was signed independently with Python's standard
`hmac`/`hashlib`, not with the Java verifier under test. Tests cover exact UTF-8
bytes, single-byte mutations, wrong key/headers, body reserialization, missing or
malformed input, timestamp bounds and the inclusive five-minute window. This is
protocol-level validation only, not proof of receiving a provider delivery.

Historical validation on Linux on 2026-10-17, using Java 21:

- Targeted verifier suite: **40 tests, 0 failures, 0 errors, 0 skipped**.
- Commerce Maven `verify`: **50 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Verifier compilation with `javac -Xlint:all -Werror`: passed.
- Config Service started and returned HTTP 200 for its health endpoint.
- Commerce started against PostgreSQL with the existing `ddl-auto: validate`
  configuration, using Config Service and with Eureka registration disabled for
  this local smoke check; `GET /actuator/info` returned HTTP 200. No schema or
  application configuration was changed.

Historical validation on Linux on 2026-10-18, using Java 21:

- Targeted suites: **40 passed, 2 capture tests skipped**; no failures/errors.
- Commerce Maven `verify`: **50 passed, 2 capture tests skipped; BUILD SUCCESS**.
- Enabled capture tests fail when the secret or fixture is missing. Their wiring
  was also checked with an isolated temporary synthetic fixture: both assertions
  pass for its valid signature and fail after mutation of that fixture. This is
  not provider-capture acceptance; no synthetic fixture was written into the repo.
- The receiver snippet above compiled with `javac -Xlint:all -Werror` and ran on
  Java 21. Local synthetic HTTP checks covered valid HMAC/200 acknowledgement,
  original-byte/header preservation, invalid signature, changed body, expired
  timestamp, method/path, oversized body and refusal to overwrite a capture.
- The previous Bash instructions passed `bash -n`; those instructions are now
  replaced by the Windows PowerShell procedure above. `git diff --check` passed.
- No production source, dependency, database, application configuration or deployed
  endpoint changed; no tunnel was left running by these local checks.

Windows instruction validation on 2026-10-18:

- All **7 PowerShell code blocks** parsed without errors in PowerShell 7.5.4 on
  the Linux validation host. There are no remaining Bash capture command blocks.
- The literal here-string and UTF-8 writer produced byte-identical Java receiver
  source, which compiled under Java 21 with `javac -Xlint:all -Werror`.
- PowerShell checks exercised the masked-input conversion with a synthetic
  secret, byte-preserving copy of UTF-8/CRLF data, header extraction and SHA-256
  comparison in an isolated temporary directory; no provider sample was created.
- Native Windows execution of `mvnw.cmd`, `Get-NetTCPConnection` and
  `cloudflared.exe`, and Windows PowerShell 5.1 execution, remain unverified on
  this Linux host. These checks do not claim a Windows end-to-end capture.

As of 2026-10-18 the user reports a registered account and a configured Test Mode
bank account, but provider capture is still pending. No provider delivery, passing
captured-delivery test or GitLab checkbox screenshot can be claimed.

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
