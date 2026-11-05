import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

// Creates its own throwaway PostgreSQL container. Never targets an existing database.
const repo = fileURLToPath(new URL('../..', import.meta.url));
const output = process.env.AUCTION_SMOKE_OUTPUT || mkdtempSync(join(tmpdir(), 'auction-read-143-'));
mkdirSync(output, { recursive: true });
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const container = `nexus-auction-read-143-${Date.now()}`;
const prefix = '00000000-0000-4000-8000-00000000000';
const children = [];
const checks = [];
let databaseCreated = false;

function docker(args, input) {
  return execFileSync('docker', args, { input, encoding: 'utf8', windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] }).trim();
}
function sql(query) {
  return docker(['exec', '-i', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'auction_test', '-d', 'auction_db'], query);
}
async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
function start(module, mainClass, port, args = []) {
  // Build classpaths with Maven dependency:build-classpath; avoids depending on stale executable jars.
  const prepared = join(output, `${module}-classpath.txt`);
  const classpathFile = existsSync(prepared) ? prepared : join(repo, module, 'target/smoke-classpath.txt');
  const classpath = join(repo, module, 'target/classes') + delimiter + readFileSync(classpathFile, 'utf8').trim();
  const logPath = join(output, `${module}.log`);
  const log = createWriteStream(logPath);
  const child = spawn(java, ['-Duser.timezone=UTC', '-Dspring.devtools.restart.enabled=false', '-cp', classpath, mainClass,
    '--server.address=127.0.0.1', `--server.port=${port}`, `--spring.application.name=${module}`, ...args],
  { cwd: output, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  child.on('close', () => log.end());
  children.push(child);
  return { child, logPath, port };
}
async function ready(service, route, expected) {
  for (let i = 0; i < 180; i++) {
    if (service.child.exitCode !== null) throw Error(readFileSync(service.logPath, 'utf8').slice(-5000));
    try {
      const response = await fetch(`http://127.0.0.1:${service.port}${route}`, { signal: AbortSignal.timeout(1500) });
      if (response.status === expected) return;
    } catch {}
    await delay(500);
  }
  throw Error(`Service not ready: ${service.logPath}`);
}
async function request(name, port, route, expected, options = {}) {
  const response = await fetch(`http://127.0.0.1:${port}${route}`, { ...options, signal: AbortSignal.timeout(10000) });
  const body = await response.json();
  assert.equal(response.status, expected, `${name}: ${JSON.stringify(body)}`);
  assert.match(response.headers.get('content-type'), expected === 200 ? /^application\/json/ : /^application\/problem\+json/);
  checks.push({ name, status: response.status, body });
  console.log(`${name}: ${response.status}`);
  return body;
}

try {
  docker(['run', '--rm', '-d', '--name', container, '-p', '127.0.0.1::5432',
    '-e', 'POSTGRES_USER=auction_test', '-e', 'POSTGRES_PASSWORD=fixture-only', '-e', 'POSTGRES_DB=auction_db', 'postgres:18']);
  databaseCreated = true;
  const dbPort = docker(['port', container, '5432/tcp']).split(':').at(-1);
  for (let i = 0; ; i++) {
    try { sql('SELECT 1'); break; } catch (error) { if (i >= 60) throw error; await delay(500); }
  }
  const configPort = await freePort();
  const auctionPort = await freePort();
  const gatewayPort = await freePort();
  const config = start('config-service', 'com.group5.configservice.ConfigServiceApplication', configPort);
  await ready(config, '/api-gateway/default', 200);
  const common = [`--spring.config.import=configserver:http://127.0.0.1:${configPort}`,
    '--spring.config.location=optional:classpath:/auction-read-smoke.yml',
    '--eureka.client.enabled=false', '--management.tracing.enabled=false'];
  const auction = start('auction-service', 'com.group5.auctionservice.AuctionServiceApplication', auctionPort, [...common,
    `--spring.datasource.url=jdbc:postgresql://127.0.0.1:${dbPort}/auction_db`,
    '--spring.datasource.username=auction_test', '--spring.datasource.password=fixture-only']);
  const gateway = start('api-gateway', 'com.group5.apigateway.ApiGatewayApplication', gatewayPort, [...common,
    `--spring.cloud.discovery.client.simple.instances.auction-service[0].uri=http://127.0.0.1:${auctionPort}`,
    '--spring.data.redis.port=1']);
  await Promise.all([ready(auction, '/api/v1/auctions', 200), ready(gateway, '/api/v1/auctions', 200)]);
  for (const [id, status, visibility, end, deleted] of [
    [1, 'ACTIVE', 'PUBLIC', 180, false], [2, 'ACTIVE', 'PUBLIC', 60, false],
    [3, 'ACTIVE', 'PUBLIC', 60, false], [4, 'SCHEDULED', 'PUBLIC', 30, false],
    [5, 'ACTIVE', 'RESTRICTED', 15, false], [6, 'ACTIVE', 'PUBLIC', 10, true], [7, 'ENDED', 'PUBLIC', -60, false]
  ]) {
    sql(`INSERT INTO auctions (id,product_id,sku_id,seller_id,title,image_url,starting_price,current_price,
      bid_increment,start_at,end_at,original_end_at,status,visibility,deleted_at)
      VALUES ('${prefix}${id}','${prefix}1','${prefix}2','${prefix}3','Snapshot ${id}',
      'https://cdn.example.com/${id}.jpg',125.25,125.25,10,'2026-11-05T00:00:00Z',
      '2026-11-05T12:00:00Z'::timestamptz+interval '${end} minutes',
      '2026-11-05T12:00:00Z'::timestamptz+interval '${end} minutes','${status}','${visibility}',${deleted ? 'now()' : 'NULL'});`);
  }
  const before = sql('SELECT json_agg(a ORDER BY id) FROM auctions a');
  const path = '/api/v1/auctions';
  for (const [name, port] of [['Gateway anonymous detail', gatewayPort], ['Service anonymous detail', auctionPort]]) {
    const body = await request(name, port, `${path}/${prefix}1`, 200);
    assert.equal(body.data.title, 'Snapshot 1');
    assert.equal(body.data.imageUrl, 'https://cdn.example.com/1.jpg');
    assert.equal(body.page, undefined);
    assert.equal(body.data.winnerId, undefined);
  }
  const list = await request('ACTIVE filter, tie order and first page', gatewayPort, `${path}?status=ACTIVE&size=2`, 200);
  assert.deepEqual(list.data.map(a => a.id), [prefix + '2', prefix + '3']);
  assert.ok(list.data.every(a => a.status === 'ACTIVE'));
  assert.deepEqual(list.page, { number: 0, size: 2, totalElements: 3, totalPages: 2 });
  const second = await request('Second page', gatewayPort, `${path}?status=ACTIVE&size=2&page=1`, 200);
  assert.deepEqual(second.data.map(a => a.id), [prefix + '1']);
  const all = await request('Omitted status and defaults', gatewayPort, path, 200);
  assert.equal(all.data.length, 5);
  assert.deepEqual(all.page, { number: 0, size: 10, totalElements: 5, totalPages: 1 });
  for (const id of [5, 6, 9]) {
    const body = await request(`Restricted/deleted/unknown detail ${id}`, gatewayPort, `${path}/${prefix}${id}`, 404);
    assert.equal(body.code, 'AUCTION_NOT_FOUND');
  }
  const empty = await request('Empty filtered list', gatewayPort, path + '?status=WON', 200);
  assert.deepEqual(empty.data, []); assert.equal(empty.page.totalElements, 0);
  const beyond = await request('Page beyond end', gatewayPort, path + '?status=ACTIVE&size=2&page=9', 200);
  assert.deepEqual(beyond.data, []); assert.equal(beyond.page.totalElements, 3);
  for (const query of ['status=INVALID', 'status=active', 'page=-1', 'size=0', 'size=101']) {
    const body = await request(`Invalid query ${query}`, gatewayPort, `${path}?${query}`, 400);
    assert.equal(body.code, query.startsWith('status=') ? 'INVALID_PARAMETER' : 'VALIDATION_FAILED');
  }
  for (const port of [gatewayPort, auctionPort]) await request('Anonymous POST stays protected', port, path, 401,
    { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
  for (const route of ['/mine', `/${prefix}1/bids`]) await request('Other GET stays protected', gatewayPort, path + route, 401);
  await request('Supplied invalid bearer stays rejected', gatewayPort, path, 401, { headers: { authorization: 'Bearer invalid' } });
  assert.equal(sql('SELECT json_agg(a ORDER BY id) FROM auctions a'), before, 'Read requests changed database rows');
  writeFileSync(join(output, 'http-evidence.json'), JSON.stringify({
    database: 'Isolated PostgreSQL 18 container; 7 seeded snapshot rows',
    runtime: 'Real Config Server, Gateway and auction-service; Catalog not started; no bearer required',
    limits: 'Seeded snapshots do not verify #141 creation integration. Redis fail-open; no live Keycloak login.',
    unchangedDatabase: true, requests: checks.length, checks
  }, null, 2));
  console.log(`PASS: ${checks.length} HTTP cases; database unchanged. Evidence: ${join(output, 'http-evidence.json')}`);
} finally {
  for (const child of children) child.kill();
  if (databaseCreated) docker(['stop', container]);
}
