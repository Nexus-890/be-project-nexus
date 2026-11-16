// Đo HTTP POST và độ trễ xử lý trên môi trường đã chuẩn bị, không tạo/sửa dữ liệu ngoài auction được chỉ định.
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

// File input giữ bearer token ngoài repo: {baseUrl,auctionId,tokens:[...],startAmount,increment,count:50}.
const [input, output = 'bid-queue-performance.json'] = process.argv.slice(2);
if (!input) throw new Error('Usage: node measure-bid-queue.mjs <private-config.json> [result.json]');
const config = JSON.parse(await readFile(input, 'utf8'));
const base = new URL(config.baseUrl);
if (!['http:', 'https:'].includes(base.protocol)) throw new Error('HTTP(S) baseUrl is required');
if (!Array.isArray(config.tokens) || !config.tokens.length || !config.tokens.every(value => typeof value === 'string' && value)) throw new Error('Buyer tokens are required');
if (!/^[a-f0-9-]{36}$/i.test(config.auctionId)) throw new Error('auctionId must be UUID');
const count = config.count ?? 50;
if (!Number.isInteger(count) || count < 1 || count > 200) throw new Error('count must be 1..200');
const startAmount = Number(config.startAmount), increment = Number(config.increment);
if (!Number.isSafeInteger(startAmount) || startAmount < 1 || !Number.isSafeInteger(increment) || increment < 1 || !Number.isSafeInteger(startAmount + (count - 1) * increment)) throw new Error('Use positive safe integer VND amounts');
const url = suffix => new URL('/api/v1/auctions/' + config.auctionId + suffix, base);

// Đo từ lúc từng POST bắt đầu tới lúc response nhận xong; tiếp tục GET đến trạng thái kết thúc.
async function run(index) {
  const token = config.tokens[index % config.tokens.length];
  const began = performance.now();
  const response = await fetch(url('/bids'), { method: 'POST', signal: AbortSignal.timeout(30_000), headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() }, body: JSON.stringify({ amount: startAmount + index * increment }) });
  const body = await response.json();
  const postMillis = performance.now() - began;
  if (response.status !== 202 || body.data?.status !== 'QUEUED') return { index, httpStatus: response.status, code: body.code, postMillis, error: 'not accepted' };
  const bidId = body.data.bidId;
  let status = 'QUEUED', reason;
  while (performance.now() - began < 30_000) {
    const result = await fetch(url('/bids/' + bidId), { signal: AbortSignal.timeout(5_000), headers: { Authorization: 'Bearer ' + token } });
    const value = await result.json();
    if (result.status === 429) { await delay(1000); continue; }
    if (!result.ok) return { index, bidId, httpStatus: result.status, code: value.code, postMillis, error: 'status read failed' };
    status = value.data.status; reason = value.data.reason;
    if (status !== 'QUEUED') return { index, bidId, postMillis, terminalMillis: performance.now() - began, status, ...(reason ? { reason } : {}) };
    await delay(250);
  }
  return { index, bidId, postMillis, status, error: 'still queued after 30 seconds' };
}

// Promise.all khởi phát một đợt đồng thời; báo cáo chỉ chứa số đo và định danh bid, không in token.
const attempts = await Promise.all(Array.from({ length: count }, (_, index) => run(index).catch(error => ({ index, error: error.name }))));
const percentile = values => { const sorted = values.filter(Number.isFinite).sort((a, b) => a - b); return sorted.length ? sorted[Math.ceil(sorted.length * .95) - 1] : null; };
const failures = attempts.filter(value => value.error);
const result = { environment: config.environment ?? 'UNSPECIFIED; caller must identify LOCAL or STAGING', baseUrl: base.origin, auctionId: config.auctionId, requests: count, buyerTokens: config.tokens.length, completedAt: new Date().toISOString(), accepted: attempts.filter(value => value.bidId).length, failures: failures.length, postP95Millis: percentile(attempts.map(value => value.postMillis)), terminalP95Millis: percentile(attempts.map(value => value.terminalMillis)), allTerminalWithinTwoSeconds: attempts.every(value => Number.isFinite(value.terminalMillis) && value.terminalMillis <= 2000), attempts };
await writeFile(output, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ ...result, attempts: undefined }, null, 2));
if (failures.length || result.postP95Millis > 50 || !result.allTerminalWithinTwoSeconds) process.exitCode = 1;
