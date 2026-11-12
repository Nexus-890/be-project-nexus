import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

let stage = 'configuration';

function report(result, code = 'OK') {
  console.log(JSON.stringify({ stage, result, code }));
}

function fail(code) {
  throw new Error(code);
}

function envelope(body) {
  assert.equal(body?.code, 0);
  assert.equal(typeof body?.message, 'string');
  assert.ok(Array.isArray(body?.data));
  const count = body.data.length;
  assert.deepEqual(body.page, {
    number: 0,
    size: Math.max(1, count),
    totalElements: count,
    totalPages: count ? 1 : 0,
  });
  return body.data;
}

function localities(body) {
  const data = envelope(body);
  const codes = new Set();
  for (const entry of data) {
    assert.equal(typeof entry?.code, 'string');
    assert.match(entry.code, /^[1-9][0-9]{0,8}$/);
    assert.equal(typeof entry?.name, 'string');
    assert.ok(entry.name.trim().length > 0);
    assert.deepEqual(Object.keys(entry).sort(), ['code', 'name']);
    assert.ok(!codes.has(entry.code));
    codes.add(entry.code);
  }
  return data;
}

try {
  if (process.env.SHIPPING_SMOKE_ENABLED !== 'true') fail('OPT_IN_REQUIRED');
  const token = process.env.SHIPPING_SMOKE_TOKEN;
  const requestFile = process.env.SHIPPING_SMOKE_QUOTE_FILE;
  if (!token?.trim() || !requestFile?.trim()) fail('TOKEN_AND_REQUEST_FILE_REQUIRED');
  const base = new URL(process.env.SHIPPING_SMOKE_BASE_URL || 'http://127.0.0.1:8080');
  if (!['http:', 'https:'].includes(base.protocol)
    || base.username || base.password || base.search || base.hash || base.pathname !== '/') fail('INVALID_BASE_URL');
  const quoteRequest = JSON.parse(readFileSync(requestFile, 'utf8'));
  const address = quoteRequest?.toAddress;
  if (!/^[1-9][0-9]{0,8}$/.test(address?.provinceCode || '')
    || !/^[1-9][0-9]{0,8}$/.test(address?.wardCode || '')) fail('V3_ADDRESS_CODES_REQUIRED');
  if (!address?.recipientName?.trim() || !/^(0[0-9]{9}|\+84[0-9]{9})$/.test(address?.recipientPhone || '')
    || !address?.line1?.trim()) fail('RECIPIENT_DETAILS_REQUIRED');
  const expectedCarrier = process.env.SHIPPING_SMOKE_CARRIER || 'GHN';
  const timeout = Number(process.env.SHIPPING_SMOKE_TIMEOUT_MS || 15000);
  if (!Number.isSafeInteger(timeout) || timeout < 1 || timeout > 120000) fail('INVALID_TIMEOUT');

  async function request(path, { expected = 200, authenticated = false, body } = {}) {
    const headers = { Accept: 'application/json, application/problem+json' };
    if (authenticated) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(new URL(path, base), {
      method: body === undefined ? 'GET' : 'POST',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'manual',
      signal: AbortSignal.timeout(timeout),
    });
    if (response.status !== expected) fail(`UNEXPECTED_HTTP_${response.status}`);
    const contentType = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
    assert.equal(contentType, expected < 400 ? 'application/json' : 'application/problem+json');
    const result = await response.json();
    if (expected >= 400) {
      assert.equal(result.status, expected);
      for (const field of ['type', 'title', 'detail', 'instance', 'code']) assert.equal(typeof result[field], 'string');
    }
    return result;
  }

  report('PASS');
  stage = 'public-provinces';
  const provinces = localities(await request('/api/v1/shipping/provinces'));
  assert.ok(provinces.some(entry => entry.code === address.provinceCode));
  report('PASS');

  stage = 'public-wards';
  const wards = localities(await request(`/api/v1/shipping/wards?provinceCode=${encodeURIComponent(address.provinceCode)}`));
  assert.ok(wards.some(entry => entry.code === address.wardCode));
  report('PASS');

  stage = 'ward-province-filter';
  const otherProvince = provinces.find(entry => entry.code !== address.provinceCode);
  assert.ok(otherProvince);
  const otherWards = localities(await request(`/api/v1/shipping/wards?provinceCode=${encodeURIComponent(otherProvince.code)}`));
  const wardCodes = new Set(wards.map(entry => entry.code));
  assert.ok(otherWards.every(entry => !wardCodes.has(entry.code)));
  report('PASS');

  stage = 'missing-provinceCode';
  const problem = await request('/api/v1/shipping/wards', { expected: 400 });
  assert.equal(problem.code, 'SHIPMENT_INVALID_PARAMETER');
  assert.ok(problem.errors?.some(entry => entry.field === 'provinceCode'));
  report('PASS');

  stage = 'protected-carriers';
  const unauthorized = await request('/api/v1/carriers', { expected: 401 });
  assert.ok(['UNAUTHENTICATED', 'SHIPMENT_UNAUTHENTICATED'].includes(unauthorized.code));
  const carriers = envelope(await request('/api/v1/carriers', { authenticated: true }));
  assert.ok(carriers.some(entry => entry.code === expectedCarrier));
  assert.equal(carriers.filter(entry => entry.isDefault).length, 1);
  for (const carrier of carriers) {
    assert.equal(typeof carrier.isDefault, 'boolean');
    assert.equal(typeof carrier.available, 'boolean');
    assert.deepEqual(Object.keys(carrier).sort(), ['available', 'code', 'isDefault', 'name']);
  }
  report('PASS');

  stage = 'shipping-quote';
  const quotes = envelope(await request('/api/v1/shipping/quotes', { authenticated: true, body: quoteRequest }));
  const quote = quotes.find(entry => entry.carrier === expectedCarrier);
  assert.ok(quote);
  assert.ok(['STANDARD', 'HEAVY'].includes(quote.serviceType));
  assert.equal(quote.serviceType, quoteRequest.parcel.weightGrams < 20000 ? 'STANDARD' : 'HEAVY');
  assert.equal(typeof quote.fee, 'number');
  assert.ok(Number.isFinite(quote.fee) && quote.fee >= 0);
  assert.equal(typeof quote.estimatedDeliveryAt, 'string');
  assert.match(quote.estimatedDeliveryAt, /(?:Z|[+-]\d{2}:\d{2})$/);
  assert.ok(Number.isFinite(Date.parse(quote.estimatedDeliveryAt)));
  assert.deepEqual(Object.keys(quote).sort(), ['carrier', 'estimatedDeliveryAt', 'fee', 'serviceType']);
  report('PASS');
} catch (error) {
  const code = error?.code === 'ERR_ASSERTION' ? 'CONTRACT_ASSERTION_FAILED'
    : /^[A-Z][A-Z0-9_]+$/.test(error?.message || '') ? error.message : 'REQUEST_OR_CONFIGURATION_FAILED';
  report('FAIL', code);
  process.exitCode = 1;
}
