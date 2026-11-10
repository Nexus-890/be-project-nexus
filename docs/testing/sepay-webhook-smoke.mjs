import { createHmac } from 'node:crypto';

// Uses only synthetic callbacks. The running payment service must use the same
// externally supplied test secret. Never print credentials, signatures or bodies.
const secret = process.env.SEPAY_WEBHOOK_SECRET;
const route = '/api/v1/webhooks/sepay';
const memo = process.env.SEPAY_WEBHOOK_SMOKE_MEMO || 'NXUNKNOWN155';
let endpoint;

function report(name, status, code) {
  console.log(`case=${name} status=${status} code=${code}`);
}

if (!secret || !secret.trim()) {
  report('configuration', 'FAILED', 'SEPAY_WEBHOOK_SECRET_REQUIRED');
  process.exit(1);
}
try {
  const base = new URL(process.env.SEPAY_WEBHOOK_SMOKE_BASE_URL || 'http://127.0.0.1:8080');
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
    throw new Error('Invalid base URL');
  }
  endpoint = new URL(route, base);
} catch {
  report('configuration', 'FAILED', 'INVALID_BASE_URL');
  process.exit(1);
}

function signature(body, timestamp) {
  return 'sha256=' + createHmac('sha256', secret)
    .update(timestamp, 'ascii').update('.', 'ascii').update(body).digest('hex');
}

const synthetic = Buffer.from(JSON.stringify({
  id: 155000001,
  gateway: 'SYNTHETIC',
  code: memo,
  content: memo,
  transferType: 'in',
  transferAmount: 1,
  referenceCode: 'SYNTHETIC-WEBHOOK-155',
}), 'utf8');
const malformed = Buffer.from('{', 'utf8');
const cases = [
  { name: 'valid-signed-no-bearer', body: synthetic, expectedStatus: 200 },
  { name: 'changed-signature-character', body: synthetic, altered: true,
    expectedStatus: 401, expectedCode: 'PAYMENT_UNAUTHENTICATED' },
  { name: 'invalid-signature-malformed-json', body: malformed, altered: true,
    expectedStatus: 401, expectedCode: 'PAYMENT_UNAUTHENTICATED' },
  { name: 'signed-malformed-json', body: malformed,
    expectedStatus: 400, expectedCode: 'PAYMENT_INVALID_WEBHOOK_REQUEST' },
  { name: 'missing-signature', body: synthetic, missing: true,
    expectedStatus: 401, expectedCode: 'PAYMENT_UNAUTHENTICATED' },
];

let failures = 0;
for (const test of cases) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  let digest = signature(test.body, timestamp);
  if (test.altered) {
    // Keep the wire format valid while changing exactly one hexadecimal digit.
    digest = digest.slice(0, -1) + (digest.endsWith('0') ? '1' : '0');
  }
  const headers = { 'Content-Type': 'application/json', 'X-SePay-Timestamp': timestamp };
  if (!test.missing) headers['X-SePay-Signature'] = digest;

  let response;
  let body;
  try {
    response = await fetch(endpoint, {
      method: 'POST', headers, body: test.body,
      redirect: 'manual', signal: AbortSignal.timeout(15000),
    });
    body = await response.json();
  } catch {
    failures++;
    report(test.name, response?.status || 'NO_RESPONSE', response ? 'INVALID_JSON' : 'TRANSPORT_ERROR');
    continue;
  }

  const contentType = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  let failure;
  if (response.status !== test.expectedStatus) failure = 'UNEXPECTED_STATUS';
  else if (test.expectedStatus === 200) {
    if (contentType !== 'application/json') failure = 'INVALID_CONTENT_TYPE';
    else if (!body || typeof body !== 'object' || Array.isArray(body)
      || body.success !== true || Object.keys(body).length !== 1) failure = 'INVALID_ACK';
  } else {
    if (contentType !== 'application/problem+json') failure = 'INVALID_CONTENT_TYPE';
    else if (body?.code !== test.expectedCode) failure = 'UNEXPECTED_CODE';
    else if (body.status !== test.expectedStatus || body.instance !== route
      || body.type !== 'urn:problem:domain-error') failure = 'INVALID_PROBLEM';
    else if (body.detail !== (test.expectedStatus === 401
      ? 'The webhook signature is invalid' : 'The webhook body must be a valid SePay JSON object')) {
      failure = 'INVALID_DETAIL';
    }
  }

  if (failure) {
    failures++;
    report(test.name, response.status, failure);
  } else {
    report(test.name, response.status, test.expectedCode || 'ACK_ACCEPTED');
  }
}

process.exitCode = failures ? 1 : 0;
