import http from 'node:http';
import { readFile, stat, mkdir, writeFile } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { execFile, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { fixtures } from './fixtures.mjs';

const folder = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(folder, '../../..');
const port = Number(process.env.ISSUE203_HELPER_PORT || 9903);
const dbContainer = process.env.ISSUE203_DB_CONTAINER || 'nexus-postgres';
const dbUser = process.env.ISSUE203_DB_USER || 'postgres';
const dbName = process.env.ISSUE203_DB_NAME || 'shipment_db';
const kafkaContainer = process.env.ISSUE203_KAFKA_CONTAINER || 'nexus-kafka';
const kafkaBootstrap = process.env.ISSUE203_KAFKA_BOOTSTRAP || 'localhost:9092';
const logPath = resolve(process.env.ISSUE203_SHIPMENT_LOG || resolve(folder, 'evidence/shipment-service.log'));
const mockToken = process.env.ISSUE203_GHN_TOKEN || 'issue203-local-outbound-token';
const shopId = Number(process.env.ISSUE203_SHOP_ID || 203);
const consumerGroup = `issue203-evidence-${randomUUID()}`;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const cancelCases = new Set([104, 105, 106, 113]);
const mockRequests = [];
const remoteStates = new Map();
const brokerEvents = new Map();
const baselines = new Map();
let consumer;
let consumerError = null;
let consumerReady = false;
let consumerRestart;
let logOffset = 0;
let seededAt = null;
let closing = false;
let resetInProgress = false;
let activePublications = 0;

function command(program, args, input, timeout = 20000) {
  return new Promise((accept, reject) => {
    const child = execFile(program, args, { timeout, maxBuffer: 8 * 1024 * 1024, windowsHide: true }, (error, stdout, stderr) => {
      if (error) reject(new Error(`${program} exited ${error.code}: ${String(stderr).slice(-1200) || error.message}`));
      else accept(stdout.trim());
    });
    child.stdin.on('error', () => {});
    child.stdin.end(input || '');
  });
}

async function sql(statement, variables = []) {
  return command('docker', ['exec', '-i', dbContainer, 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
    '-U', dbUser, '-d', dbName, ...variables.flatMap(([key, value]) => ['-v', `${key}=${value}`])], statement);
}

async function jsonSql(statement) {
  const result = await sql(statement);
  return JSON.parse(result.split(/\r?\n/).filter(Boolean).at(-1));
}

function startConsumer() {
  if (closing || (consumer && consumer.exitCode === null && !consumer.killed && consumer.pid)) return;
  clearTimeout(consumerRestart);
  let pending = '';
  consumerError = null;
  consumer = spawn('docker', ['exec', kafkaContainer, '/opt/kafka/bin/kafka-console-consumer.sh',
    '--bootstrap-server', kafkaBootstrap, '--topic', 'nexus.shipment.events', '--from-beginning',
    '--group', consumerGroup, '--timeout-ms', '30000', '--consumer-property', 'enable.auto.commit=false', '--property', 'print.key=true',
    '--property', 'key.separator=\t'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const current = consumer;
  consumerReady = false;
  consumer.stdout.setEncoding('utf8');
  consumer.once('spawn', () => { if (consumer === current) consumerReady = true; });
  consumer.stdout.on('data', chunk => {
    pending += chunk;
    const lines = pending.split(/\r?\n/);
    pending = lines.pop();
    for (const line of lines) {
      const separator = line.indexOf('\t');
      if (separator < 0) continue;
      try {
        const envelope = JSON.parse(line.slice(separator + 1));
        if (String(envelope.aggregateId).startsWith('20300000-')) {
          brokerEvents.set(envelope.eventId, { key: line.slice(0, separator), topic: 'nexus.shipment.events', envelope });
          consumerError = null;
        }
      } catch { }
    }
  });
  consumer.stderr.on('data', chunk => { consumerError = chunk.toString().slice(-1200); });
  consumer.on('error', error => { consumerError = error.message; consumerReady = false; });
  consumer.once('close', () => {
    if (consumer === current) consumerReady = false;
    if (!closing) consumerRestart = setTimeout(startConsumer, 500);
    consumerRestart?.unref();
  });
}

async function readBody(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 262144) throw new Error('Request body exceeds 256 KiB');
    chunks.push(chunk);
  }
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
}

function respond(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(body, null, 2));
}

function requireFixture(id) {
  const fixture = fixtures.get(Number(id));
  if (!fixture) throw new Error('Unknown fixture caseId');
  return fixture;
}

async function warningLines(fixture) {
  try {
    const info = await stat(logPath);
    const start = info.size < logOffset ? 0 : logOffset;
    const chunks = [];
    for await (const chunk of createReadStream(logPath, { start })) chunks.push(chunk);
    const needle = `Support action required: OrderCancelled arrived after pickup orderId=${fixture.orderId} shipmentId=${fixture.shipmentId} eventId=${fixture.eventId}`;
    return Buffer.concat(chunks).toString('utf8').split(/\r?\n/).filter(line => line.includes('WARN') && line.includes(needle));
  } catch { return null; }
}

async function kafkaHealth() {
  const output = await command('docker', ['exec', kafkaContainer, '/opt/kafka/bin/kafka-topics.sh',
    '--bootstrap-server', kafkaBootstrap, '--describe', '--topic', 'nexus\\.(commerce|shipment)\\.events']);
  const topics = ['nexus.commerce.events', 'nexus.shipment.events'];
  return { ready: topics.every(topic => output.includes(`Topic: ${topic}`)), topics, description: output };
}

async function waitForProcessedPublications() {
  const deadline = Date.now() + 15000;
  do {
    if (activePublications === 0) {
      const output = await command('docker', ['exec', kafkaContainer, '/opt/kafka/bin/kafka-consumer-groups.sh',
        '--bootstrap-server', kafkaBootstrap, '--describe', '--group', 'shipment-order-cancelled']);
      const rows = output.split(/\r?\n/).map(line => line.trim().split(/\s+/))
        .filter(columns => columns[0] === 'shipment-order-cancelled' && columns[1] === 'nexus.commerce.events');
      if (rows.length && rows.every(columns => (/^\d+$/.test(columns[5]) && Number(columns[5]) === 0)
          || (columns[4] === '0' && ['-', '0'].includes(columns[3])))) return;
      if (!rows.length && /does not exist|has no active members/.test(output)) return;
    }
    await new Promise(accept => setTimeout(accept, 250));
  } while (Date.now() < deadline);
  throw new Error('Cancellation consumer has uncommitted Kafka messages. Wait for processing before resetting fixture data.');
}

async function captureBaselines() {
  const snapshots = await jsonSql(`SELECT COALESCE(jsonb_agg(jsonb_build_object('id',id,'status',shipment_status,
    'version',version,'trackingCode',tracking_code,'updatedAt',updated_at,'handedOverAt',handed_over_at,
    'nextTrackingRetryAt',next_tracking_retry_at) ORDER BY id),'[]'::jsonb)
    FROM shipments WHERE id IN (${[...fixtures.values()].map(fixture => `'${fixture.shipmentId}'::uuid`).join(',')});`);
  baselines.clear();
  for (const snapshot of snapshots) baselines.set(snapshot.id, snapshot);
}

async function evidence(fixture) {
  const { shipmentId, orderId, eventId, caseId } = fixture;
  const result = await jsonSql(`
    SELECT jsonb_build_object(
      'caseId', ${caseId}, 'shipmentId', '${shipmentId}', 'orderId', '${orderId}',
      'status', (SELECT shipment_status::text FROM shipments WHERE id='${shipmentId}'),
      'version', (SELECT version FROM shipments WHERE id='${shipmentId}'),
      'historyCount', (SELECT count(*) FROM shipment_history WHERE shipment_id='${shipmentId}'),
      'eventCount', (SELECT count(*) FROM outbox WHERE aggregate_id='${shipmentId}'),
      'receiptCount', (SELECT count(*) FROM carrier_webhook_receipts WHERE shipment_id='${shipmentId}'),
      'processedCount', (SELECT count(*) FROM processed_events WHERE event_id='${eventId}' AND consumer='shipment-order-cancelled'),
      'tombstoneCount', (SELECT count(*) FROM cancelled_shipment_orders WHERE order_id='${orderId}'),
      'nextStatusPollAt', (SELECT next_status_poll_at FROM shipments WHERE id='${shipmentId}'),
      'shipmentSnapshot', (SELECT jsonb_build_object('id',id,'status',shipment_status,
        'version',version,'trackingCode',tracking_code,'updatedAt',updated_at,'handedOverAt',handed_over_at,
        'nextTrackingRetryAt',next_tracking_retry_at) FROM shipments WHERE id='${shipmentId}'),
      'history', COALESCE((SELECT jsonb_agg(jsonb_build_object('id',id,'status',shipment_status,
        'source',source,'actorId',changed_by,'reason',description,'carrierEventId',carrier_event_id,'changedAt',created_at)
        ORDER BY created_at,id) FROM shipment_history WHERE shipment_id='${shipmentId}'),'[]'::jsonb),
      'events', COALESCE((SELECT jsonb_agg(jsonb_build_object('eventId',id,'eventType',event_type,'topic',topic,
        'aggregateId',aggregate_id,'payload',payload->'payload','envelope',payload,'publishedAt',published_at)
        ORDER BY occurred_at,id) FROM outbox WHERE aggregate_id='${shipmentId}'),'[]'::jsonb)
    );`);
  result.cancelCalls = mockRequests.filter(request => request.path.endsWith('/cancel') && request.orderCode === fixture.trackingCode).length;
  result.carrierRequests = mockRequests.filter(request => request.orderCode === fixture.trackingCode);
  result.warningLines = await warningLines(fixture);
  result.warningCount = result.warningLines === null ? null : result.warningLines.length;
  result.logAvailable = result.warningLines !== null;
  result.baseline = baselines.get(shipmentId) || null;
  result.brokerEvents = result.events.map(event => brokerEvents.get(event.eventId)).filter(Boolean);
  result.brokerConsumerError = consumerError;
  const callback = caseId <= 23 || caseId === 102;
  const processed = [104, 105, 106, 113].includes(caseId) ? 1 : 0;
  const assertions = {
    status: result.status === fixture.expectedStatus,
    historyCount: result.historyCount === fixture.changes,
    eventCount: result.eventCount === fixture.changes,
    receiptCount: result.receiptCount === (callback ? 1 : 0),
    processedCount: result.processedCount === processed,
    tombstoneCount: result.tombstoneCount === processed,
    cancelCalls: caseId === 112 ? result.cancelCalls >= 1 : result.cancelCalls === (caseId === 104 ? 1 : 0),
    warningCount: caseId !== 105 || result.warningCount === 1,
    shipmentTopic: result.events.every(event => event.topic === 'nexus.shipment.events'),
    historyEventCorrelation: result.events.every(event => {
      const row = result.history.find(history => history.id === event.payload?.historyId);
      return row && row.status === event.payload.status && event.aggregateId === shipmentId
        && event.payload.orderId === orderId && event.payload.shipmentVersion > 0
        && event.payload.shipmentVersion <= result.version && event.payload.actorId === row.actorId
        && event.payload.source === row.source && (event.payload.reason ?? null) === (row.reason ?? null)
        && event.envelope.eventId === event.eventId && event.envelope.eventType === event.eventType;
    }),
    relayPublished: result.events.every(event => event.publishedAt !== null),
    brokerDelivered: result.brokerEvents.length === result.events.length && result.brokerEvents.every(event => {
      const outbox = result.events.find(row => row.eventId === event.envelope.eventId);
      return event.key === orderId && outbox && isDeepStrictEqual(event.envelope, outbox.envelope);
    }),
    source: result.history.every(row => row.source === (caseId <= 23 || caseId === 102 ? 'CARRIER_WEBHOOK'
      : [107, 108].includes(caseId) ? 'MANUAL' : caseId === 109 ? 'POLLER' : 'SYSTEM')),
    unchangedShipment: fixture.changes !== 0 || isDeepStrictEqual(result.shipmentSnapshot, result.baseline),
    seeded: seededAt !== null
  };
  assertions.all = Object.values(assertions).every(Boolean);
  result.assertions = assertions;
  result.passed = assertions.all;
  result.seededAt = seededAt;
  return result;
}

async function serve(request, response) {
  const url = new URL(request.url, `http://127.0.0.1:${port}`);
  if (url.pathname.startsWith('/__test/')) {
    startConsumer();
    if (url.pathname === '/__test/health' && request.method === 'GET') {
      const database = await jsonSql('SELECT jsonb_build_object(\'ready\',true,\'database\',current_database(),\'tables\',(SELECT count(*) FROM information_schema.tables WHERE table_schema=\'public\' AND table_name IN (\'shipments\',\'carrier_webhook_receipts\',\'cancelled_shipment_orders\')));');
      let logAvailable = false;
      try { await stat(logPath); logAvailable = true; } catch { }
      const kafka = await kafkaHealth();
      return respond(response, 200, { ready: database.tables === 3 && kafka.ready && logAvailable, database, kafka, logAvailable,
        kafkaConsumerRunning: consumerReady, consumerError, seededAt,
        mode: 'GHN_STUB_REAL_SERVICE_DB_KAFKA', fixtureCount: fixtures.size });
    }
    if (['/__test/seed', '/__test/reset'].includes(url.pathname) && request.method === 'POST') {
      if (resetInProgress) return respond(response, 409, { error: 'A fixture reset is already running' });
      const body = await readBody(request);
      const buyerId = body.buyerId || '20320000-0000-4000-8000-000000000001';
      if (!uuidPattern.test(buyerId)) return respond(response, 400, { error: 'buyerId must be a UUID from the buyer token sub' });
      resetInProgress = true;
      try {
        if (seededAt !== null) await waitForProcessedPublications();
        await sql(await readFile(resolve(folder, 'sql/01-seed.sql'), 'utf8'), [['buyer_id', buyerId]]);
        mockRequests.length = 0;
        remoteStates.clear();
        for (const fixture of fixtures.values()) {
          if (fixture.trackingCode) remoteStates.set(fixture.trackingCode, { status: 'ready_to_pick', updated_date: '2026-11-11T00:00:00Z' });
        }
        await captureBaselines();
        try { logOffset = (await stat(logPath)).size; } catch { logOffset = 0; }
        seededAt = new Date().toISOString();
        return respond(response, 200, { seeded: true, buyerId, fixtureCount: fixtures.size, logOffset, seededAt });
      } finally {
        resetInProgress = false;
      }
    }
    const evidenceMatch = url.pathname.match(/^\/__test\/evidence\/(\d+)$/);
    if (evidenceMatch && request.method === 'GET') {
      const fixture = requireFixture(evidenceMatch[1]);
      const deadline = Date.now() + (url.searchParams.get('wait') === 'true' ? 15000 : 0);
      let result;
      do {
        result = await evidence(fixture);
        if (result.passed || Date.now() >= deadline) break;
        await new Promise(accept => setTimeout(accept, 350));
      } while (true);
      await mkdir(resolve(folder, 'evidence'), { recursive: true });
      await writeFile(resolve(folder, `evidence/case-${fixture.caseId}.json`), JSON.stringify(result, null, 2));
      return respond(response, 200, result);
    }
    if (url.pathname === '/__test/publish-cancellation' && request.method === 'POST') {
      if (resetInProgress) return respond(response, 409, { error: 'Wait for fixture reset before publishing' });
      if (seededAt === null) return respond(response, 409, { error: 'Run fixture setup before publishing' });
      const body = await readBody(request);
      const fixture = requireFixture(body.caseId);
      if (!cancelCases.has(fixture.caseId)) return respond(response, 400, { error: 'Only cancellation fixture cases are publishable' });
      const buyer = await jsonSql(`SELECT jsonb_build_object('id',COALESCE((SELECT buyer_id FROM shipments WHERE id='${fixture.shipmentId}'),'20320000-0000-4000-8000-000000000001'::uuid));`);
      const event = {
        eventId: fixture.eventId, eventType: 'OrderCancelled', aggregateId: fixture.orderId,
        occurredAt: new Date().toISOString(), traceId: '20300000000000000000000000000001',
        payload: { orderId: fixture.orderId, buyerId: buyer.id, orderNumber: `NX-203-${fixture.caseId}`,
          orderType: 'STANDARD', referenceId: null, cancelledAt: new Date().toISOString(),
          reason: 'Issue 203 evidence: buyer requested cancellation',
          orderGroupId: fixtureIdForGroup(fixture.caseId), sellerId: '20320000-0000-4000-8000-000000000002',
          orderVersion: 2, paidAmount: '200000.00', currency: 'VND', actorId: buyer.id }
      };
      const copies = body.duplicate ? 2 : 1;
      const line = fixture.orderId + '\t' + JSON.stringify(event) + '\n';
      activePublications++;
      try {
        await command('docker', ['exec', '-i', kafkaContainer, '/opt/kafka/bin/kafka-console-producer.sh',
          '--bootstrap-server', kafkaBootstrap, '--topic', 'nexus.commerce.events',
          '--property', 'parse.key=true', '--property', 'key.separator=\t', '--producer-property', 'acks=all',
          '--producer-property', 'delivery.timeout.ms=10000', '--producer-property', 'request.timeout.ms=5000'], line.repeat(copies));
      } finally {
        activePublications--;
      }
      return respond(response, 200, { caseId: fixture.caseId, eventId: fixture.eventId, orderId: fixture.orderId,
        copies, topic: 'nexus.commerce.events', published: true });
    }
    if (url.pathname === '/__test/arm-poll' && request.method === 'POST') {
      const body = await readBody(request);
      const fixture = fixtures.get(109);
      if (body.shipmentId && body.shipmentId !== fixture.shipmentId) return respond(response, 400, { error: 'Only polling fixture109 can be armed' });
      if (body.status && body.status !== 'transporting') return respond(response, 400, { error: 'Polling target is transporting' });
      remoteStates.set(fixture.trackingCode, { status: 'transporting', updated_date: '2026-11-11T00:10:00Z' });
      await sql(`UPDATE shipments SET next_status_poll_at=now()-interval '1 second' WHERE id='${fixture.shipmentId}';`);
      return respond(response, 200, { armed: true, caseId: 109, shipmentId: fixture.shipmentId, status: 'transporting' });
    }
    if (url.pathname === '/__test/contract' && request.method === 'GET') {
      const eventDoc = await readFile(resolve(repo, 'docs/api/events.md'), 'utf8');
      const flow = eventDoc.split(/\r?\n/).find(line => /^4\. \*\*Paid child cancellation/.test(line)) || '';
      const checked = await command(process.execPath, [resolve(repo, 'docs/api/check-contract.mjs')], '');
      const shipmentSpec = await readFile(resolve(repo, 'docs/api/openapi/shipment-service.yaml'), 'utf8');
      const statusOperations = ['getShipmentTracking', 'getShipmentHistory', 'updateShipmentStatus',
        'overrideShipmentStatus', 'receiveCarrierWebhook'];
      return respond(response, 200, {
        r1Rule: flow.includes('PAID') && flow.includes('until ShipmentPickedUp') && flow.includes('no handover request'),
        routeInventory: checked.includes('PASS:') && /shipment-service: \d+ controller operations/.test(checked)
          && statusOperations.every(operation => shipmentSpec.includes(`operationId: ${operation}\n`)),
        shipmentTopic: 'nexus.shipment.events', integrationFlow4: flow, contractOutput: checked,
        docsPath: 'docs/api/events.md', openapiPath: 'docs/api/openapi/shipment-service.yaml'
      });
    }
    return respond(response, 404, { error: 'Unknown test companion operation' });
  }
  const authenticated = request.headers.token === mockToken && request.headers.shopid === String(shopId);
  if (!authenticated) return respond(response, 401, { code: 401, message: 'Invalid local carrier fixture credentials' });
  if (url.pathname === '/shiip/public-api/v2/shipping-order/detail' && request.method === 'GET') {
    const code = url.searchParams.get('order_code');
    const fixture = [...fixtures.values()].find(value => value.trackingCode === code);
    if (!fixture) return respond(response, 404, { code: 404, message: 'Unknown local tracking fixture' });
    mockRequests.push({ method: 'GET', path: url.pathname, orderCode: code, authenticated, at: new Date().toISOString() });
    return respond(response, 200, { code: 200, message: 'Success', data: { order_code: code,
      ...(remoteStates.get(code) || { status: 'ready_to_pick', updated_date: '2026-11-11T00:00:00Z' }) } });
  }
  if (url.pathname === '/shiip/public-api/v2/switch-status/cancel' && request.method === 'POST') {
    const body = await readBody(request);
    const code = body.order_codes?.[0];
    const fixture = [...fixtures.values()].find(value => value.trackingCode === code);
    if (!fixture || body.order_codes.length !== 1) return respond(response, 400, { code: 400, message: 'Unknown local cancellation fixture' });
    const accepted = fixture.caseId !== 112;
    mockRequests.push({ method: 'POST', path: url.pathname, orderCode: code, authenticated,
      accepted, request: body, at: new Date().toISOString() });
    if (accepted) remoteStates.set(code, { status: 'cancel', updated_date: new Date().toISOString() });
    return respond(response, 200, { code: 200, message: 'Success', data: [{ order_code: code, result: accepted }] });
  }
  return respond(response, 404, { code: 404, message: 'GHN fixture only implements tracking and cancellation' });
}

function fixtureIdForGroup(id) {
  return `20330000-0000-4000-8000-${String(id).padStart(12, '0')}`;
}

const server = http.createServer((request, response) => {
  serve(request, response).catch(error => {
    if (!response.headersSent) respond(response, 500, { error: error.message, companion: true });
    else response.end();
  });
});
server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`Issue 203 test companion: http://127.0.0.1:${port}\nDatabase: ${dbContainer}/${dbName}; Kafka: ${kafkaContainer}; service log: ${logPath}\n`);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  if (closing) return;
  closing = true;
  clearTimeout(consumerRestart);
  consumer?.kill();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
});
