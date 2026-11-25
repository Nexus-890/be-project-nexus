# Manual seller payouts

An eligible funded seller order creates one payout when Commerce emits `OrderCompleted`. MANUAL execution uses a protected VietQR instruction and staff attestation of an external transfer; Nexus does not send money through a banking API. `MANUAL_STAFF_CONFIRMED`, seller acknowledgement and bank credit are separate facts.

## Configuration and rollout

Payment reads `nexus.payout` from Config Server. Configure environment variables on the backend host or Compose service; `.env.example` documents the local essentials. Restart/recreate Payment after changing execution settings so validation and task registration run again. Restart Config Server too when changing its configuration/environment.

| Mode | `PAYOUT_JOBS_ENABLED` | `PAYOUT_MANUAL_ENABLED` | Behavior |
| --- | --- | --- | --- |
| `PAYOUT_EXECUTION_MODE=MANUAL` | false | true | New manual claims and QR issuance |
| `PAYOUT_EXECUTION_MODE=AUTO` | true | false | Explicit legacy automatic dispatch/reconciliation |
| Either mode | false | false | Pause new execution; preserve recording/reconciliation of already-issued manual attempts |
| Either mode | true | true | Startup rejected |

An enabled executor must match the selected mode. Missing configuration defaults to AUTO mode with both execution flags false. Mode is frozen when a payout is created; changing environment variables never converts existing MANUAL/AUTO rows. Select MANUAL before completing new test orders. The default AUTO provider `sandbox` simulates outcomes and does not establish a real bank payment.

Manual execution also needs:

- `PAYOUT_ACCOUNT_ENCRYPTION_KEY`: Base64 of 32 cryptographically random bytes, stored only through the backend secret mechanism. Preserve the existing key when bank records have already been encrypted; changing it without a controlled migration makes those records unreadable.
- `PAYOUT_MANUAL_SOURCE_ACCOUNTS_JSON`: approved platform source aliases, for example `[{"id":"platform-main","alias":"Platform payout account"}]`. These entries identify the external source selected by staff; they do not store bank credentials or prove the transfer originated there. Empty aliases block readiness/claim.
- An active/default seller bank, reviewed by ADMIN using its current version. Bank review is not confirmation of account existence or bank credit.
- A positive integral VND net amount within the supported QR limit. Fees and liability remain frozen; unsupported amounts are held rather than rounded.

JSON source aliases work in Java/IDE and Compose. A nonempty native/indexed `nexus.payout.manual.source-accounts` list takes precedence. Invalid JSON, duplicate IDs or invalid alias fields reject configuration.

## Private evidence storage

Provision a separate Supabase bucket `payout-evidence`, `public=false`, file limit `10485760`, MIME types `image/jpeg`, `image/png`, `application/pdf`. Do not grant anonymous/browser users direct read/upload access; review broad `storage.objects` policies that could apply to the bucket. Product image storage remains independent.

Payment reuses `SUPABASE_URL` and the backend-only `SUPABASE_SERVICE_KEY` from the same project. Optional `PAYOUT_EVIDENCE_SUPABASE_URL` / `PAYOUT_EVIDENCE_SUPABASE_SERVER_KEY` override them; blank overrides fall back to the shared settings. Server keys belong to the project rather than a bucket. Modern secret keys use the `apikey` header; legacy service-role JWTs additionally use Bearer authentication. Never expose either type to FE or Git.

`PAYOUT_EVIDENCE_BUCKET` defaults to `payout-evidence`. Retry/duration/provider/storage tuning remains optional in Config YAML; ordinary local setup does not need every override in `.env`. The supported maximum file size is fixed at 10 MiB. Claim lease defaults to five minutes, issuance deadline ten minutes, and reference business dates use `Asia/Ho_Chi_Minh`.

Upload accepts one file at a time, at most three per attempt, and checks content signatures rather than trusting extension/MIME. Short transactions authorize before upload and recheck state/version/quota at finalization. Storage I/O holds no financial database locks. Failed finalization compensates the unused object; a bounded orphan scanner removes only old unreferenced objects in the recognized UUID layout, with a default 24-hour grace. Known evidence is retained. Cleanup uses its own worker and never calls a transfer provider.

The storage boundary verifies the bucket is private. Download checks role and exact payout/attempt/evidence binding, validates stored content integrity and returns private binary bytes. Missing/unavailable/corrupt storage returns a safe dependency error, never a public-storage fallback.

## Execution and reconciliation

1. Read capabilities/queue/detail and use authoritative `allowedActions`, versions and blocked reasons. GETs do not claim, issue or mutate financial state. Readiness-check safely rechecks PENDING/ON_HOLD without creating an attempt or payment.
2. One ADMIN/SUPPORT_STAFF claims the payout. A staff role never bypasses another executor's live QR ownership, and staff cannot execute/reconcile their own seller payout.
3. The owner explicitly issues the QR after funding/refund and reviewed-bank checks. Recipient, exact amount and attempt reference are frozen. Local encoding uses the NAPAS VietQR account-transfer profile `QRIBFTTA`; full account numbers are encrypted at rest and absent from ordinary JSON/history/errors.
4. Staff checks the recipient, amount and reference in the external banking app, transfers, then records source alias, bank reference, transferred time and bound proof. One transaction commits confirmation, PAYOUT, history, status and success outbox event. Receipt becomes `AWAITING_CONFIRMATION`.
5. Only the owning SELLER acknowledges receipt or raises a dispute. These commands do not send money, change order completion, refund or authorize another payout. Review summaries remain sanitized; private staff proof/notes do not become seller data.
6. ADMIN alone resolves financial uncertainty, authorizes reconciled retry, records a genuine return, or corrects an attestation made without a real transfer. NOT_SENT leaves FAILED pending explicit retry. RETURNED adds PAYOUT_RETURN linked to the original. Mistaken attestation adds PAYOUT_CORRECTION and a corrective event, preserves original history, marks the attempt corrected and leaves the same payout FAILED. The retry command requeues it; the next exclusive claim creates a fresh attempt/reference and receipt state.

The queue is a lightweight projection rather than a list of complete details. It loads masked payout/recipient data, current attempt/receipt, case summaries, readiness and allowed actions using bounded page-level queries. `history` and `evidence` are empty arrays; current-attempt case summaries have empty explanations. Fetch detail for the authorized history, private evidence metadata and case notes. Filters/count/paging still operate on all applicable rows, including unresolved cases on SUCCEEDED payouts. `REVIEW_DISPUTE` applies only when the current attempt has an open SELLER_DISPUTE, not merely an open staff exception.

An executor may report a problem after a successful transfer and later discover the report was mistaken. ADMIN can resolve that selected open `STAFF_EXCEPTION` with `CONFIRMED_SENT` when both payout and attempt remain SUCCEEDED. This is a nonfinancial case closure: require current payout/attempt/case versions, a reason and bound evidence, close only the selected staff case and append audit. Preserve the original payout, PAYOUT movement, bank reference, receipt state and every other case, including any seller dispute. Do not create a new movement, success event, return or correction to dismiss an incorrect report. `CONFIRMED_SENT` on UNKNOWN still uses the normal reconciled settlement path and requires transfer facts; an already successful seller dispute alone is not eligible for the staff-report dismissal path.

A new staff report records one `MANUAL_PROBLEM` audit linked to its case. Reporting from QR_ISSUED moves the payout/attempt to UNKNOWN in that same command without adding a second problem audit. Replaying the original command adds no new case or audit. Automatic expiry/bank-change transitions retain their own single audit when no staff report command is being recorded.

Safe release/lease expiry applies only before issuance. Once a QR has been issued, bank changes, expiry or uncertainty require UNKNOWN and reconciliation; logout/read failure/abandonment cannot authorize another transfer. A saved QR can still be payable in a banking app after the Nexus deadline. Never label actually sent or wrong-recipient money NOT_SENT merely to pay again.

Writes require a UUID `Idempotency-Key` and the relevant version. Replay recognition precedes rejection of a version advanced by that same command, but current authorization still applies. Keep the same key/body/version for a lost-response replay; new commands use refreshed versions. Bank references are reserved by source account + business date + bank reference. Application locks/idempotency prevent duplicate authorization/accounting; they cannot stop an independent second payment made in a bank app.

## Bank reconciliation through SePay

When the payout source account is linked to SePay, the bank itself confirms the transfer. SePay posts every outgoing transaction of that account to `POST /api/v1/webhooks/sepay` with `transferType=out`. Payment verifies the signature, then:

1. Checks `accountNumber` against `PAYOUT_MANUAL_SEPAY_BANK_ACCOUNT` (blank reuses `SEPAY_BANK_ACCOUNT`). Other accounts are recorded as `WRONG_ACCOUNT` and ignored.
2. Finds the attempt by the `NXP` reference in `code`, `content` or `description`, case-insensitively. A transfer without a known reference is recorded as `UNMATCHED`.
3. Locks the payout like a staff command and records the SePay transaction id once; a redelivered webhook changes nothing.

| Attempt state | Bank transfer | Result |
| --- | --- | --- |
| QR_ISSUED | exact amount, made between QR issuance (2 minutes skew) and the QR deadline, reviewed bank unchanged, funding ready | `CONFIRMED`: same settlement as a staff confirmation with `confirmationSource=BANK_VERIFIED`, SYSTEM actor, SePay `referenceCode` as bank reference (else `SEPAY-<id>`), no evidence upload |
| QR_ISSUED | wrong amount, outside the window, bank/funding changed, or no source alias mapped | `REVIEW_REQUIRED`: attempt and payout become UNKNOWN with a reconciliation case |
| CLAIMED | any | `REVIEW_REQUIRED`: money left before the QR was issued |
| UNKNOWN | exact amount | `RECORDED`: `BANK_MATCHED` audit; ADMIN resolves with the bank facts shown on the attempt |
| SUCCEEDED (staff-confirmed) | exact amount | `VERIFIED`: `BANK_MATCHED` audit, nothing financial changes |
| SUCCEEDED, NOT_SENT, RETURNED, CONFIRMATION_CORRECTED | wrong amount or unexpected | `REVIEW_REQUIRED`: open `STAFF_EXCEPTION` and `BANK_MISMATCH` audit |
| any | a second transfer with the same reference | `REVIEW_REQUIRED`: `DUPLICATE_BANK_TRANSFER` case, possible double payment |

`PAYOUT_MANUAL_SEPAY_SOURCE_ACCOUNT_ID` names the source alias recorded for bank-verified confirmations; blank uses the only configured alias. `PAYOUT_MANUAL_SEPAY_RECONCILIATION_ENABLED=false` stops reconciliation. Every outgoing transaction is kept in `manual_payout_bank_transactions` with its outcome. Staff detail shows the first matched transfer as `currentAttempt.bankMatch`; seller projections never include it.

## Browser integration contract

The canonical API is `api/openapi/payment-service.yaml`. The new staff base is `/api/v1/payments/manual-payouts`; owner receipt/dispute commands are under `/api/v1/payments/payouts/{payoutId}/manual-attempts/{attemptId}`. Preserve the original ADMIN-only lists and legacy numeric fields; new manual amounts are exact fixed-two-decimal strings.

FE does not need a Supabase key, public/signed bucket URL or direct storage access. Upload `FormData` containing `file` and `expectedAttemptVersion` with current Bearer authentication and UUID `Idempotency-Key`; let the browser generate the multipart boundary. For QR/evidence, fetch with Bearer, check the HTTP status, call `blob()` and display/download a `URL.createObjectURL` result. Direct API `<img src>` cannot attach the in-memory Bearer token. Revoke object URLs on replacement/close/unmount/auth changes and abort or ignore late responses. Do not place tokens in URLs.

Binary responses use `Cache-Control: no-store, private`; errors are Problem Details. Staff attestation, bank verification and seller receipt need distinct labels. Existing FE parsers must accept manual history/source/actions before enablement; status alone does not authorize retry.

## Cutover and verification

Apply additive Flyway migrations; never rewrite an applied migration or reset historical success. Before disabling legacy sending/lookup, reconcile active AUTO/UNKNOWN attempts and review funded orders with simulated successes. Run the following read-only inventory against the upgraded Payment schema using a read-only account; resolve liabilities explicitly without creating a second ordinary payout for an order.

```sql
-- Funding is not proof of a real outgoing seller payment.
SELECT p.id AS payout_id, p.order_id, p.seller_id, p.status,
       p.net_amount, p.currency, p.provider, p.succeeded_at
FROM payouts p
JOIN payment_instructions i ON i.id=p.instruction_id
JOIN payment_transactions t ON t.id=i.funded_transaction_id
WHERE p.execution_mode='AUTO' AND p.status='SUCCEEDED'
  AND t.type='NORMAL' AND t.status='SUCCESS'
  AND (p.provider='sandbox' OR EXISTS (
      SELECT 1 FROM payout_history h WHERE h.payout_id=p.id AND h.simulated))
ORDER BY p.created_at,p.id;

SELECT id AS payout_id, order_id, seller_id, status, provider,
       provider_reference, attempt_count, attempt_started_at, net_amount, currency
FROM payouts
WHERE execution_mode='AUTO' AND status IN ('PROCESSING','UNKNOWN')
ORDER BY created_at,id;
```

After restart, inspect effective Config Server/Payment settings and actual automatic-task registration. Missing/false jobs config must register neither dispatch nor reconcile task; do not disable the global scheduler. Keep SePay inbound handling, completion consumers, eligibility, outbox and safe manual recovery working. The default management exposure does not include `/actuator/scheduledtasks`; a 404 there is not evidence of task absence.

Complete the two-seller FE → Gateway → Payment → database integration before bank acceptance. Commerce completion comes from buyer confirmation of a DELIVERED order or the due-order completion job; the Admin status endpoint cannot set COMPLETED directly. One checkout may fund multiple seller orders once, but each eligible order creates a separate payout/QR/amount/reference.

Keep new manual work disabled until compatible BE/FE releases, source aliases, encryption key, private storage and cutover checks are ready. Distinguish automated fixtures, real private-storage tests, bank-app scans and actual seller credit. Use only explicitly authorized bank accounts/amounts for real acceptance; credentials, OTP and real receipt files stay outside Git.
