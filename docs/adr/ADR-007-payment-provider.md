# ADR-007: Payment Provider — SePay

**Status:** Proposed — SePay account and API access are available; the Test Mode signature spike is complete, while the required Live receiving-bank evidence is blocked.

**Date:** 2026-10-17; updated 2026-10-18.

**Scope:** SePay integration spike and evidence only. This ADR does not implement payment initiation, a production webhook endpoint, order transitions, wallet credit, or escrow.

**Reviewers:** @fsa_VinhDP, @fsa_Chung.

## Context and decision

The team's operational target is **SePay Live** for VietQR bank-transfer payment integration. This intentionally differs from SRS 2.6 and BR-110, where the default `PAYMENT_PROVIDER` is **STRIPE**. The current endpoint catalogue already identifies SEPAY for PAY-01/PAY-02. This ADR records the provider decision and spike findings; it does **not** modify the runtime provider choice (#120) or implement VietQR initiation (#121).

For bank-transfer webhooks, use **HMAC-SHA256 over the original request bytes** instead of relying on an API key alone. The integration must reject a changed body even if all other request fields are unchanged. Do not confuse the bank-transfer webhook contract with the separate payment-gateway IPN protocol.

**Scope boundary:** `WebhookSignatureVerifier` is a reusable verification utility, not a full Commerce webhook workflow. Signature verification alone does not implement replay/delivery deduplication, memo matching, order settlement, database writes, or business idempotency. These belong to later payment issues (#155–#157).

## Account, bank, and test-transfer ownership

A Test Mode account is **not** evidence of an actual bank connection or an agreed real-money test transfer. Credential access and bank ownership must be confirmed by the appropriate team members, not inferred from dashboard labels, issue assignments, or screenshots.

| Required fact | Confirmed value / evidence | Status |
|---|---|---|
| SePay account and API access | The team confirms that a SePay user account and API access exist. This is not a statement about a particular individual's custodianship. | **Confirmed** |
| Actual credential holder / person responsible for rotation | **Pending:** confirm who controls the SePay account, webhook HMAC secret, and recovery access. | **Pending** |
| Credential storage and authorized readers | **Pending:** agree on approved secret storage and access. Never store credentials in Git or this ADR. | **Pending** |
| Test Mode receiving bank | **ACB**, personal test account `0000000002`, label `Nexus SePay Spike test`. | **Confirmed in Test Mode UI** |
| Test Mode bank display name | `DO VAN A` (simulated display name, **not** verified real-world account ownership). | **Confirmed in Test Mode UI** |
| Live receiving bank, account reference, and legal owner | **Blocked:** no suitable Live receiving bank is connected. The Vietcombank connection flow currently does not support the personal account available to the team. The team is evaluating another bank. | **Blocked** |
| Authorized people who may inspect the Live bank statement | **Pending.** | **Pending** |
| Dashboard bank evidence | Screenshots of the configured **ACB Test Mode** bank were shown during the spike; retain an appropriately redacted screenshot with the GitLab issue. | **Observed; attachment pending** |
| Person paying for agreed Live test transfers | **Pending:** no named payer agreement. | **Pending** |
| Agreed amount for Live test transfer (VND) | **Pending:** 10,000 VND below was a simulated test value, not an agreed amount for a real transfer. | **Pending** |

The unsuccessful Vietcombank Live attempt must not be described as a connected bank: its current connection flow does not support the team's personal account. Neither the historical Test Mode bank nor its display name is evidence of a Live account or of any person's ownership.

The Test Mode webhook was configured to receive **incoming transfers**, use **JSON**, require **HMAC-SHA256**, and apply to **all test accounts**. Its dashboard label was `Nexus SePay Spike test`. Account registration is reported, but the combined registration/custodian subtask remains open until ownership and approved credential storage are confirmed.

## Signature contract

The [official SePay bank-transfer webhook authentication documentation](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc) specifies the following protocol, implemented by `WebhookSignatureVerifier`:

| Item | Contract |
|---|---|
| Algorithm | HMAC-SHA256 with the configured webhook secret |
| Signed input | ASCII timestamp, followed by a single `.` byte, followed by the **original raw HTTP body bytes** |
| Signature header | `X-SePay-Signature: sha256=<64 lowercase hexadecimal characters>` |
| Timestamp header | `X-SePay-Timestamp: <Unix seconds>` |
| Freshness check | Accept absolute clock skew up to and including 300 seconds |
| Comparison | Compare signature digest bytes in constant time |
| Secret handling | Inject as `SEPAY_WEBHOOK_SECRET` at runtime; keep outside source control |

The verifier rejects missing/malformed headers, invalid signatures, expired timestamps, and modified bodies before any downstream business action. Its inputs must be the original HTTP body bytes and original header values. **Never** trim, normalize CRLF, convert character encodings, reserialize JSON, or append a newline before signature verification.

For a historical captured request, `WebhookCapturedDeliveryTest` uses a fixed `Clock` at the original signature timestamp; it does **not** disable timestamp validation.

A valid signature does not itself prevent duplicate processing. Persisted event identity, reconciliation, and idempotent payment updates remain future-work concerns.

## Test Mode evidence and Live decision

SePay provides **Test Mode**, per the [Test Mode documentation](https://docs.sepay.vn/test-mode.html). Test banks, webhooks, API credentials, and simulated transactions are separate from Live. [Creating a Test Mode webhook](https://developer.sepay.vn/vi/tien-ich-khac/test-mode/tao-webhook) supports HMAC.

**Decision:** SePay Live remains the required environment for operational acceptance. The existing Test Mode capture is retained solely as technical evidence: it is a provider-sent signed callback and supports raw-byte HMAC verification, but it cannot substitute for a Live bank connection, a Live transfer, or Live acceptance evidence.

A Live bank-connection smoke test is **Blocked** until a supported Live receiving bank is selected and connected. It must then have a confirmed credential custodian, authorized owner, statement-access permissions, named payer, and agreed amount. None of these facts may be inferred from the Test Mode dashboard or historical capture.

The [webhook integration guide](https://docs.sepay.vn/tich-hop-webhooks.html) describes an acknowledgement using HTTP 200/201 and `{"success":true}` within 30 seconds. Transport retries and duplicate callbacks must be checked separately from business-level `PAYMENT_RETRY_LIMIT`.

## Captured provider evidence — 2026-10-18

The following is the **actual observed Test Mode spike**, not an illustrative transaction.

| Evidence | Observation |
|---|---|
| Provider environment | **SePay Test Mode**; not Live |
| Simulated bank / account | ACB personal test account `0000000002` (`DO VAN A` is a test display name) |
| Simulation | Incoming **10,000 VND**, description/memo **`NXTEST001`** |
| Webhook | `Nexus SePay Spike test`; incoming transfers, all accounts, JSON, HMAC-SHA256 |
| Temporary delivery path | HTTPS Cloudflare Quick Tunnel forwarding to local Windows Java receiver `127.0.0.1:8787/sepay-spike` |
| Connectivity/security probe | Unsigned `POST {}` to the public webhook returned **HTTP 401** and `{"success":false}` |
| Provider simulation result | SePay showed successful filtering and webhook delivery **`Thành công (1)`**, followed by successful acknowledgment and simulation completion |
| Local receiver result | Receiver logged **`Verified delivery captured; stop the tunnel and disable its webhook.`** |
| Capture artifacts | Local temporary `body.json`, `headers.txt`, `received-at.txt`; not committed as a group |
| Committed sample path | `be-project-nexus/docs/api/sepay-webhook-sample.json` (copied locally; **commit still pending**) |
| Original and copied body SHA-256 | **`35C18230746A13F5CA18F1178A3FB2A7F6A48AABAF6DDFD7C76EFDEC1D87D1F4`**; both hashes matched |
| Captured `X-SePay-Signature` | **Pending documentation:** original value remains in local `headers.txt`; do not confuse this with the HMAC secret |
| Captured `X-SePay-Timestamp` | **Pending documentation:** original value remains in local `headers.txt` |
| UTC receipt time | **Pending documentation:** read original value from local `received-at.txt` |
| Provider delivery/transaction ID | **Pending:** record if the SePay delivery history or raw sample exposes an identifier |
| Dashboard screenshots | Test bank, configured webhook, successful simulation: **observed; issue attachments/links pending** |

**Raw-byte integrity:** PowerShell `Copy-Item` copied the original `body.json` to the sample path. SHA-256 of both files was checked independently and matched. Do not edit, format, anonymize, or serialize the signed fixture; any byte-level change breaks verification. Before staging it, **inspect whether the Test Mode body contains information that may be committed**. If unsafe, capture a new suitable Test Mode event instead of altering the signed body.

**Git line endings:** The local `git check-attr text` initially returned `text: auto` for the sample. Add and verify the following path-specific rule in the repository-root `.gitattributes` before `git add`:

```gitattributes
/be-project-nexus/docs/api/sepay-webhook-sample.json -text
```

After adding the rule, `git check-attr text -- be-project-nexus/docs/api/sepay-webhook-sample.json` must report `text: unset`. This preserves the exact signed bytes through Git checkout, including Windows environments. **Attribute change: Done locally; post-stage verification remains Pending because this spike must stop before staging/commit review.**

**Targeted captured-delivery tests:** The historical Test Mode rerun was reported successful after the matching secret was restored to the Maven process, but its final console/Surefire evidence has not been inspected in this workspace. In the current session the matching secret and original signature/timestamp headers are not available, so the capture test must remain **Blocked** rather than be represented as a new PASS. The test class has two opt-in assertions: the original provider-sent body/signature is accepted, and a **one-byte modification is rejected**.

The earlier missing-secret `BUILD FAILURE` must not be represented as a product validation failure. Conversely, user-reported success should not be presented as an independently inspected build log until the output is attached.

The first successful capture is intentionally immutable: later valid callbacks return HTTP 409 rather than overwrite the evidence. The receiver acknowledges a verified first delivery with HTTP 200 and `{"success":true}`. The spike receiver makes **no** Commerce order, payment, or wallet change.

## Local capture and replay procedure (Windows PowerShell)

Keep this repeatable procedure for maintainers. Use **Windows PowerShell 5.1 or PowerShell 7**, **JDK 21**, the project's Maven wrapper, and `cloudflared.exe` on the **same Windows machine**. No running Commerce application, Config Service, database, or staging endpoint is required for this temporary receiver.

### 1. Start the receiver in the first PowerShell window

Choose your actual repository checkout and JDK 21 installation. The receiver listens on port **8787** and path **`/sepay-spike`**. Do not take over the port if another process already owns it.

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

Enter the **same HMAC secret** configured in SePay via a masked prompt. Do not type a secret into the command line itself, shell history, screenshots, commits, issue comments, or the ADR. See [Microsoft's `Read-Host -AsSecureString` documentation](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/read-host).

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

Keep this first terminal open while the receiver runs. The environment variables are **per PowerShell process**, not automatically shared with other terminal tabs. The receiver source is temporary and is **not** a production endpoint or committed source file.

### 2. Start Cloudflare Quick Tunnel in a second PowerShell window

```powershell
Get-Command cloudflared.exe -ErrorAction Stop
cloudflared.exe tunnel --url http://127.0.0.1:8787 --no-autoupdate
```

Wait for the generated HTTPS URL and a registered tunnel connection. Append `/sepay-spike` and use that full callback URL in the **SePay Test Mode** webhook configuration. Quick Tunnel hostnames are ephemeral; never assume an earlier hostname still works.

In SePay select incoming transfers, JSON, HMAC-SHA256, and the same secret used by the receiver. For the 2026-10-18 capture, the webhook was configured for **all Test Mode accounts**; the simulated ACB test bank supplied the incoming transaction.

### 3. Probe connectivity and capture a provider request

In a **third** PowerShell window, use `curl.exe` rather than PowerShell's possible `curl` alias. An unsigned request should be rejected and should **not** consume the first verified capture.

```powershell
curl.exe -i --request POST --header 'Content-Type: application/json' --data-binary '{}' 'http://127.0.0.1:8787/sepay-spike'
$publicBaseUrl = Read-Host 'HTTPS base URL printed by the running cloudflared'
$webhookUrl = $publicBaseUrl.TrimEnd('/') + '/sepay-spike'
curl.exe -i --request POST --header 'Content-Type: application/json' --data-binary '{}' $webhookUrl
```

Both unsigned probes should return **HTTP 401** and `{"success":false}`. Then trigger a simulated incoming transaction in SePay Test Mode. A correctly signed **first** provider request yields HTTP 200 with `{"success":true}` and creates exactly three files in the local capture directory:

- `body.json` — exact original request bytes;
- `headers.txt` — actual signature and timestamp headers (not the secret);
- `received-at.txt` — receipt time in UTC.

A later valid request returns 409 to avoid overwriting evidence. Confirm the SePay delivery history and the local `Verified delivery captured` log. **After capture, disable the temporary SePay webhook, stop the tunnel and receiver with Ctrl+C, and retain the first PowerShell session until tests finish.**

### 4. Copy unchanged bytes and run the captured-delivery tests

Run in `be-project-nexus/commerce-service`, preferably in the **first** PowerShell session so its original `SEPAY_WEBHOOK_SECRET` and capture directory remain available. If another terminal is used, load the **same** secret again with a masked prompt; do not generate a new one.

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

If the first test fails with `Set SEPAY_WEBHOOK_SECRET outside Git before enabling captured-delivery tests`, verify whether that variable is present **in the same terminal that invokes Maven**. Do not print its value. Re-enter the **matching** existing secret via `Read-Host -AsSecureString`, then rerun. A wrong/new secret cannot verify the captured signature.

The two opt-in tests must both run (not skip): one accepts the original provider-sent body with the original signature/timestamp, and the other changes **one byte** and expects verification to fail. The test uses a fixed historical clock at the captured header timestamp.

To remove the session's secret/header variables after validating (preserve a separate approved secret store):

```powershell
Remove-Item Env:SEPAY_WEBHOOK_SECRET, Env:SEPAY_CAPTURE_SIGNATURE, Env:SEPAY_CAPTURE_TIMESTAMP, Env:SEPAY_CAPTURE_TEST -ErrorAction SilentlyContinue
```

### 5. Stage the fixture safely

At the repository root, add the exact sample path to `.gitattributes` using `-text`; verify the resolved attribute before staging the fixture. Confirm the body is appropriate to commit, and keep the fixture content byte-identical to the captured provider request. Do **not** commit `headers.txt`, `received-at.txt`, the HMAC secret, a temporary `WebhookCapture.java`, or other local capture artifacts.

Before committing, verify `git diff --check`, `git status --short`, the file's SHA-256, and final Maven test evidence. A screenshot of SePay's success state and a terminal showing the completed capture tests should be attached to the GitLab issue, with private values masked.

## Payment issue re-estimation

These remain **provisional engineering estimates** based on the protocol review plus the Test Mode capture. They are not team-approved GitLab estimates. Compare them to the **current** issue descriptions, existing estimates, and sprint capacity with the reviewers before editing the tracker. The Live receiving-bank blocker is excluded from each estimate: it is external waiting/coordination work, not implementation capacity.

| Issue | Previous individual estimate | Proposed revised estimate (dev-day) | Breakdown and dependency |
|---|---|---:|---|
| #120 — provider interface/config | **Pending:** current issue value not verified | **1.0** | 0.5 provider/config selection + 0.5 tests; consume this ADR, do not rewrite verifier. **PARTIAL:** account/API access exists; Live bank configuration is blocked. |
| #121 — VietQR initiation | **Pending:** current issue value not verified | **2.0** | 1.0 memo/payment instructions/deadline + 0.5 ownership/retry handling + 0.5 tests; depends on #120, order creation, and a supported Live receiving bank. **BLOCKED** operationally; no initiation is implemented here. |
| #155 — signature/memo matching | **Pending:** current issue value not verified | **2.0** | 0.5 raw-body verifier integration + 0.5 callback/ACK + 0.5 memo matching + 0.5 tests; depends on this capture and #121. **PARTIAL:** reusable verifier and immutable Test Mode fixture exist; Live proof and integration do not. |
| #156 — idempotent PAID update | **Pending:** current issue value not verified | **2.0** | 0.5 persistence/uniqueness + 0.5 atomic state/event updates + 1.0 duplicate/concurrency/rollback tests; depends on #155 and order/event contracts. **PENDING.** |
| #157 — UNRECONCILED transactions | **Pending:** current issue value not verified | **1.0** | 0.5 late/wrong-amount/unmatched handling + 0.5 tests; depends on #155/#156. **PENDING.** |

**Estimate confidence:** medium for the signature/protocol mechanics after a Test Mode callback; **low** for production bank onboarding, downstream order/database integration, operational approvals, and delivery retry/idempotency behavior. Any approved Live-bank smoke test and human/OTP waiting time must be budgeted separately. These numbers are **Pending reviewer reconciliation and tracker updates**.

The legacy backlog has F05.6 at 4 dev-days in S3 and F05.7 at 5 dev-days in S4; these are aggregate feature estimates, **not** the five individual issue estimates above. The newer endpoint catalogue places #120/#121 in **Sprint 2** and #155–157 in **Sprint 4**. Do not move this work to Sprint 3 solely because the older sheet differs.

SePay Test Mode permits repeatable future regression without real payments. Still plan a distinct authorized **Live bank smoke checkpoint**. Provider transport retries are not equivalent to business retries; later implementation must consider idempotency, stale callbacks, bad memos, wrong amounts, and reconciliation.

## Validation and evidence tracking

### Existing local and CI-style validation

From `be-project-nexus/commerce-service`, using Java 21:

```powershell
.\mvnw.cmd '-Dtest=WebhookSignatureVerifierTest' test
if ($LASTEXITCODE -ne 0) { throw 'Verifier unit tests failed' }
.\mvnw.cmd verify
if ($LASTEXITCODE -ne 0) { throw 'Commerce verification failed' }
```

`WebhookCapturedDeliveryTest` is **opt-in** through `SEPAY_CAPTURE_TEST=true`. Its two tests are **skipped** in an ordinary build without this flag; that is not a successful real-capture test. When enabled, missing sample, signature, timestamp, or secret must **fail** rather than silently skip. Tests read original fixture bytes and must **never** regenerate or re-sign the fixture.

**Prior historical results (not to be confused with the new Test Mode capture):**

- **2026-10-17, Linux, Java 21:** 40 targeted verifier tests passed; Commerce Maven `verify` reported 50 tests passed; `javac -Xlint:all -Werror` passed. Config Service health returned 200; Commerce's `/actuator/info` returned 200 against the previously configured PostgreSQL smoke environment. No payment schema or configuration was changed.
- **2026-10-18, Linux, Java 21, before real capture:** 40 verifier tests passed; two new capture tests were skipped; Maven `verify` reported 50 passed plus two capture tests skipped. Temporary synthetic-fixture wiring passed in isolation, but **did not** qualify as a provider capture. The Java receiver was compiled and checked for signature rejection, exact-byte storage, request handling, and no overwrite. PowerShell scripts were parsed on PowerShell 7.5.4 on the Linux host; native Windows execution was then **not yet** verified.

**Observed/reported Test Mode evidence — 2026-10-18:**

- Native Windows receiver and Cloudflare Quick Tunnel ran and accepted an actual **SePay Test Mode-sent** webhook.
- Unsigned probe was rejected with HTTP 401.
- SePay simulator showed the webhook delivery as successful (`Thành công (1)`); the local receiver logged a **verified** delivery.
- Local original and repository-sample SHA-256 hashes matched: `35C18230746A13F5CA18F1178A3FB2A7F6A48AABAF6DDFD7C76EFDEC1D87D1F4`.
- The first targeted `WebhookCapturedDeliveryTest` run failed due to a missing **process environment variable**, not a signature mismatch. After securely reloading the original secret, the user **reported a successful rerun**. The final `Tests run: ... / BUILD SUCCESS` screenshot or Surefire report remains **Pending attachment/inspection**.
- **Current local validation, 2026-10-18, Windows, JDK 21:** `WebhookSignatureVerifierTest` passed **40/40** (no failures, errors, or skips). `mvn verify` passed **52 tests** with **2 skipped**, which are the opt-in `WebhookCapturedDeliveryTest` tests. The current session did not have the matching capture secret and original signature/timestamp headers, so those two tests were intentionally not enabled.
- Git now resolves `be-project-nexus/docs/api/sepay-webhook-sample.json` to `text: unset`; staged-blob hash verification remains **Pending review** because no staging was performed.
- No production webhook, payment initiation, order update, escrow, wallet, dependency, database migration, or service configuration was introduced for this capture.

### Evidence to attach to the GitLab spike issue

Provide non-secret/redacted evidence for: (a) SePay Test Mode ACB bank/account configuration; (b) active Test Mode HMAC webhook and a successful simulated delivery; (c) terminal receipt/verification and the two targeted test results with `Skipped: 0`; (d) exact raw-body SHA-256 and Git path; (e) final commit and updated issue estimates. Record actual spent time, reference the commit, and request review from **@fsa_VinhDP** and **@fsa_Chung** after the evidence is in place.

**Do not attach the HMAC secret** or expose it in screenshots. Any saved provider signature/timestamp is delivery metadata, not the signing secret; review what is safe to publish. Test bank display names must not be presented as evidence of Live ownership.

### Subtasks (local documentation status; GitLab checkboxes are separate)

| Subtask | Status | Evidence / blocker |
|---|---|---|
| Register SePay account and record credential holder | **PARTIAL** | Account/API access is confirmed; credential custodian and approved secret storage are still **Pending**. |
| Name receiving bank account and owner | **BLOCKED** | The current Vietcombank flow does not support the team's personal account; no suitable Live bank is connected. Owner, statement access, payer, and agreed amount are unknown. |
| Point webhook at tunnel and capture real delivery | **DONE — Test Mode only** | A provider-sent Test Mode HMAC delivery was captured. This does not complete the required Live transfer evidence. |
| Commit raw body | **PENDING review** | Fixture SHA-256 is recorded and `.gitattributes` now prevents normalization; no staging or commit is performed without approval. |
| Verify captured delivery and tamper rejection | **PARTIAL** | Unit verifier tests cover exact-byte and one-byte mutations. Re-running the provider-capture test is **Blocked** until the matching secret and original headers are supplied to the Maven process. |
| Re-estimate SePay/payment webhook work | **PARTIAL** | Provisional estimates are above; tracker reconciliation needs the current GitLab issue values and reviewer approval. |

### Issue acceptance criteria

- **BLOCKED:** Dashboard shows a supported Live receiving bank and ADR names the confirmed credential holder. Account/API access alone is insufficient; Live bank and holder confirmation are missing.
- **PENDING review:** `docs/api/sepay-webhook-sample.json` is a provider-sent Test Mode raw body, but has not been staged or committed.
- **BLOCKED in this session:** The captured body passes with its actual signature headers. The original secret and headers are unavailable to re-run this test; a previously reported PASS is not new inspected evidence.
- **PARTIAL:** One-byte tampering is rejected by unit tests. The provider-capture version remains blocked with the same missing inputs.
- **BLOCKED:** ADR names the agreed payer and amount for a real Live transfer; neither has been confirmed.
- **PENDING:** Payment issue estimates must be reconciled with current tracker values and reviewer approval.

Keep GitLab checkboxes open until the corresponding evidence and/or approval is linked. **Test Mode delivery validates the spike's signed-webhook mechanism; it does not resolve the Live operational prerequisites.**

## References

- [SePay bank-transfer webhook authentication](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc)
- [SePay Test Mode](https://docs.sepay.vn/test-mode.html)
- [SePay Test Mode webhook creation](https://developer.sepay.vn/vi/tien-ich-khac/test-mode/tao-webhook)
- [SePay webhook integration guide](https://docs.sepay.vn/tich-hop-webhooks.html)
- [SRS v1.0](https://drive.google.com/file/d/1nw7_WwZoWhjgA1WiZJzu37ps_MisI5Ls/view): §§2.6, 3.4.2, 5.3.
- [Business Rules](https://docs.google.com/spreadsheets/d/1x2ulrpa9n5Xmgb8G7b3zJ9sisHjYJG7wGycalQGHB-U/edit): BR-109–114, BR-118, BR-127–128.
- [Endpoint catalogue](https://drive.google.com/file/d/1KIJsr87F-6TWieBbie9CVzgCBTPsM4Zk/view): PAY-01/PAY-02; #120/#121/#155–157; modified 2026-10-16.
- [Legacy backlog](https://drive.google.com/file/d/1r-7RETPihuO3FolSjZxtcZOPmk4pc5NE/view): F05.6/F05.7.
- [Group report](https://drive.google.com/file/d/1AS18TaHffK5QhxaVyX2q7S8U9pAm1OBJ/view): planning row `#119 Sepay`; confirm actual spike issue URL before linking/closing.

## Consequences and open decisions

The team can develop and test a correct raw-body HMAC verifier using a genuine **Test Mode provider-sent** fixture, without integrating a live bank, deploying a callback endpoint, or implementing money movement. The fixture must remain byte-identical after Git checkout; it is not enough that the parsed JSON looks equivalent. This is only a technical spike result: SePay **Live** remains the operational target.

**Still Pending or Blocked:** named credential custodian and secret access policy; a supported Live receiving bank plus owner/statement permissions; named payer and agreed Live transfer amount; an authorized Live smoke test; original header/timestamp/received-at metadata and matching test inputs; test/evidence attachments; fixture privacy review before staging; finalized GitLab issue estimates; reviewer approval.

Future #120/#121/#155–#157 work owns production provider configuration, VietQR initiation, HMAC-protected callback integration, memo matching, idempotent order transitions, and unreconciled-transfer handling. None of those business behaviors is claimed to have been implemented by this spike.
