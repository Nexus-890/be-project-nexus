import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { Script } from 'node:vm';

const output = dirname(fileURLToPath(import.meta.url));
const uuid = (prefix, index) => `${prefix}-0000-4000-8000-${String(index).padStart(12, '0')}`;
const shipment = (index) => uuid('20300000', index);
const order = (index) => uuid('20310000', index);
const cases = [];
const eventFor = (status) => ({ SHIPPED: 'ShipmentPickedUp', IN_TRANSIT: 'ShipmentInTransit', DELIVERED: 'ShipmentDelivered', DELIVERY_FAILED: 'ShipmentFailed', RETURNED: 'ShipmentReturned', CANCELLED: 'ShipmentCancelled' }[status] || 'ShipmentStatusUpdated');
const exec = (code) => code.trim().split('\n');
const checkStatus = (status) => `pm.test('HTTP ${status}', function () { pm.response.to.have.status(${status}); });`;
const protocol = `${checkStatus(200)}
pm.test('Native carrier acknowledgement', function () { const b = pm.response.json(); pm.expect(b).to.deep.equal({code: 200, message: 'Success'}); pm.expect(b).not.to.have.property('data'); pm.expect(b).not.to.have.property('page'); });`;
const problem = (status, codes) => `${checkStatus(status)}
pm.test('Problem Details with named code', function () { const b = pm.response.json(); pm.expect(pm.response.headers.get('Content-Type').toLowerCase()).to.include('application/problem+json'); for (const key of ['type','title','status','detail','instance','code']) pm.expect(b).to.have.property(key); pm.expect(b.status).to.equal(${status}); pm.expect(${JSON.stringify(Array.isArray(codes) ? codes : [codes])}).to.include(b.code); pm.expect(b).not.to.have.property('data'); });`;
const envelope = (status = 200) => `${checkStatus(status)}
pm.test('ApiResponse envelope', function () { const b = pm.response.json(); pm.expect(b.code).to.be.a('number'); pm.expect(b.message).to.be.a('string'); pm.expect(b).to.have.property('data'); });`;
const requiredToken = (name, actorVariable) => `const token = pm.environment.get('${name}');
if (!token || !token.trim()) throw new Error('Login manually and fill ${name} in the selected environment before running this folder.');
${actorVariable ? `if (!pm.variables.get('${actorVariable}')) throw new Error('The ${name} JWT must contain a valid UUID sub claim.');` : ''}`;

function request(id, title, method, url, {body, raw, test = checkStatus(200), pre, token, actor, headers = [], description = '', ac = []} = {}) {
  const item = {
    name: `${id} | ${title}`,
    event: [
      ...(pre || token ? [{listen: 'prerequest', script: {type: 'text/javascript', exec: exec([token ? requiredToken(token, actor) : '', pre || ''].filter(Boolean).join('\n'))}}] : []),
      {listen: 'test', script: {type: 'text/javascript', exec: exec(test)}}
    ],
    request: {
      method,
      header: [...(body !== undefined || raw !== undefined ? [{key: 'Content-Type', value: 'application/json', type: 'text'}] : []), ...headers],
      auth: token ? {type: 'bearer', bearer: [{key: 'token', value: `{{${token}}}`, type: 'string'}]} : {type: 'noauth'},
      url,
      description: `${ac.length ? `Acceptance criteria: ${ac.join(', ')}.\n\n` : ''}${description}`,
      ...(body !== undefined || raw !== undefined ? {body: {mode: 'raw', raw: raw ?? JSON.stringify(body, null, 2), options: {raw: {language: 'json'}}}} : {})
    }
  };
  cases.push({id, title, method, url, ac, name: item.name});
  return item;
}

function callback(id, title, caseId, status, options = {}) {
  const trackingCode = caseId <= 23 ? `S203-MAP-${String(caseId).padStart(2, '0')}` : ({101: 'S203-BADSIG', 102: 'S203-DUP', 103: 'S203-BACK'}[caseId]);
  return request(id, title, 'POST', '{{baseUrl}}/api/v1/webhooks/carriers/ghn', {
    body: {ShopID: '{{shopId}}', OrderCode: trackingCode, Type: 'switch_status', Status: status, Time: '{{callbackTime}}', Reason: id, ...options.body},
    pre: "const shopId = Number(pm.variables.get('shopId')); if (!Number.isInteger(shopId) || shopId < 1) throw new Error('shopId must be a positive integer');",
    headers: options.signature === null ? [] : [{key: 'X-Carrier-Signature', value: options.signature ?? '{{webhookSecret}}', type: 'text'}],
    test: options.test || protocol,
    description: options.description || `GHN callback for fixture ${caseId}. Capture the response and Test Results, then its paired evidence request.`,
    ac: options.ac || [],
    ...options.extra
  });
}

function evidence(id, title, caseId, {status, history = 0, events = history, receipts = 0, processed = 0, tombstones = 0, cancels = 0, cancelMin, warnings, source, eventType, version, reason, actor, ac = []} = {}) {
  const normalizedStatus = status === undefined ? null : status;
  const checks = [
    checkStatus(200),
    `const b = pm.response.json();`,
    `pm.test('Evidence case identity', function () { pm.expect(b.caseId).to.equal(${caseId}); pm.expect(b.orderId).to.equal('${order(caseId)}'); });`,
    `pm.test('Persisted shipment and history counts', function () { pm.expect(b.status).to.equal(${JSON.stringify(normalizedStatus)}); pm.expect(b.historyCount).to.equal(${history}); pm.expect(b.eventCount).to.equal(${events}); pm.expect(b.receiptCount).to.equal(${receipts}); });`,
    `pm.test('Consumer idempotency and cancellation tombstone', function () { pm.expect(b.processedCount).to.equal(${processed}); pm.expect(b.tombstoneCount).to.equal(${tombstones}); });`,
    cancelMin !== undefined ? `pm.test('Actual CarrierAdapter cancellation attempt observed', function () { pm.expect(b.cancelCalls).to.be.at.least(${cancelMin}); });` : `pm.test('Actual CarrierAdapter cancellation calls', function () { pm.expect(b.cancelCalls).to.equal(${cancels}); });`,
    `pm.test('Server evidence assertions', function () { pm.expect(b.assertions).to.be.an('object'); pm.expect(b.assertions.all, JSON.stringify(b.assertions)).to.equal(true); pm.expect(b.passed).to.equal(true); });`,
    ...(version !== undefined ? [`pm.test('Shipment version unchanged or changed exactly once', function () { pm.expect(b.version).to.equal(${version}); });`] : []),
    ...(warnings !== undefined ? [`pm.test('Support WARN captured exactly once', function () { pm.expect(b.warningCount, 'Run the issue203 startup script so the companion can read the service log; a missing log is not evidence').to.equal(${warnings}); });`] : []),
    ...(history ? [
      `pm.test('History / event correlation', function () { pm.expect(b.history).to.have.lengthOf(${history}); pm.expect(b.events).to.have.lengthOf(${events}); const h = b.history[0]; const e = b.events[0]; pm.expect(e.topic).to.equal('nexus.shipment.events'); pm.expect(e.aggregateId).to.equal('${shipment(caseId)}'); pm.expect(e.payload.historyId).to.equal(h.id); pm.expect(e.payload.shipmentId).to.equal('${shipment(caseId)}'); pm.expect(e.payload.orderId).to.equal('${order(caseId)}'); pm.expect(e.payload.status).to.equal(${JSON.stringify(normalizedStatus)}); pm.expect(e.payload.shipmentVersion).to.be.above(0).and.at.most(b.version); });`,
      ...(source ? [`pm.test('Verified change source', function () { pm.expect(b.history[0].source).to.equal('${source}'); pm.expect(b.events[0].payload.source).to.equal('${source}'); });`] : []),
      ...(eventType ? [`pm.test('Correct Shipment event type', function () { pm.expect(b.events[0].eventType).to.equal('${eventType}'); });`] : []),
      ...(reason ? [`pm.test('Persisted audit reason', function () { pm.expect(b.history[0].reason).to.equal(${JSON.stringify(reason)}); pm.expect(b.events[0].payload.reason).to.equal(${JSON.stringify(reason)}); });`] : []),
      ...(actor ? [`pm.test('Actor comes from authenticated JWT', function () { const expected = pm.variables.get('${actor}'); pm.expect(expected).to.be.a('string').and.not.empty; pm.expect(b.history[0].actorId).to.equal(expected); pm.expect(b.events[0].payload.actorId).to.equal(expected); });`] : [])
    ] : [])
  ];
  return request(id, title, 'GET', `{{helperBaseUrl}}/__test/evidence/${caseId}?wait=true`, {test: checks.join('\n'), ac,
    description: `This local companion reads actual shipment_db tables and observes outbound mock-carrier calls; it is not a production shipment endpoint. Capture the full response and Test Results. Expected status=${normalizedStatus}, history=${history}, events=${events}, receipts=${receipts}, processed=${processed}, carrier cancel calls=${cancelMin === undefined ? cancels : `>=${cancelMin}`}.`});
}

function publish(id, title, caseId, {duplicate = false, ac = []} = {}) {
  return request(id, title, 'POST', '{{helperBaseUrl}}/__test/publish-cancellation', {body: {caseId, duplicate}, ac,
    test: `${checkStatus(200)}
pm.test('Real Kafka publication acknowledged', function () { const b = pm.response.json(); pm.expect(b.published).to.equal(true); pm.expect(b.topic).to.equal('nexus.commerce.events'); pm.expect(b.caseId).to.equal(${caseId}); pm.expect(b.eventId).to.equal('${uuid('203e0000', caseId)}'); pm.expect(b.orderId).to.equal('${order(caseId)}'); pm.expect(b.copies).to.equal(${duplicate ? 2 : 1}); });`,
    description: `Publishes ${duplicate ? 'two copies of the SAME eventId' : 'one OrderCancelled'} through the local companion to the real Kafka broker. Then run its paired evidence request; the HTTP publication response alone does not prove consumer success.`});
}

const folders = [];
folders.push({name: '00 Setup | Reset only issue203 fixtures', description: 'Fill the four access tokens after your own login, then run the collection in order. Buyer identity is read automatically from accessTokenBuyer; no UUID or infrastructure variable must be copied into the environment. No login occurs in this collection.', item: [
  request('SETUP-01', 'Companion and infrastructure health', 'GET', '{{helperBaseUrl}}/__test/health', {test: `${checkStatus(200)}
pm.test('Prepared infrastructure is ready', function () { const b = pm.response.json(); pm.expect(b.ready).to.equal(true); pm.expect(b.logAvailable).to.equal(true); pm.expect(b.kafkaConsumerRunning).to.equal(true); pm.expect(b.mode).to.equal('GHN_STUB_REAL_SERVICE_DB_KAFKA'); });`, description: 'Capture the helper health response before starting. The companion binds to localhost and supplies local GHN fixtures and database/Kafka evidence.'}),
  request('SETUP-02', 'Seed and reset deterministic issue203 data', 'POST', '{{helperBaseUrl}}/__test/seed', {body: {buyerId: '{{buyerId}}'}, test: `${checkStatus(200)}
pm.test('Fixture seed completed with token-derived buyer', function () { const b = pm.response.json(); pm.expect(b.seeded).to.equal(true); pm.expect(b.buyerId).to.equal(pm.variables.get('buyerId')); pm.expect(b.fixtureCount).to.equal(36); });`, description: 'Resets only the reserved issue203 shipment/order/event IDs. The collection reads buyerId from your buyer access token. It does not create login accounts. Re-running this request invalidates screenshots from a previous run until the cases are run again.'})
]});

const mappings = [
  ['ready_to_pick', 'CREATED'], ['picking', 'PICKING'], ['money_collect_picking', 'PICKING'], ['picked', 'SHIPPED'],
  ['storing', 'IN_TRANSIT'], ['sorting', 'IN_TRANSIT'], ['transporting', 'IN_TRANSIT'], ['delivering', 'OUT_FOR_DELIVERY'],
  ['money_collect_delivering', 'OUT_FOR_DELIVERY'], ['delivered', 'DELIVERED'], ['delivery_fail', 'DELIVERY_FAILED'],
  ['exception', 'DELIVERY_FAILED'], ['lost', 'DELIVERY_FAILED'], ['damage', 'DELIVERY_FAILED'], ['scrap', 'DELIVERY_FAILED'],
  ['waiting_to_return', 'RETURNING'], ['return', 'RETURNING'], ['return_transporting', 'RETURNING'], ['return_sorting', 'RETURNING'],
  ['returning', 'RETURNING'], ['return_fail', 'RETURNING'], ['returned', 'RETURNED'], ['cancel', 'CANCELLED']
];
folders.push({name: '01 AC1 | All 23 GHN status mappings and one history/event', description: 'Each pair uses an independent seeded shipment, so a terminal status does not block the following mapping. SQL begins with zero history/outbox rows. Calls go through the real GHN adapter and callback parser; only the external carrier server is local.', item: mappings.flatMap(([external, internal], offset) => {
  const index = offset + 1;
  const label = `AC1-M${String(index).padStart(2, '0')}`;
  return [callback(label, `${external} -> ${internal}`, index, external, {ac: ['AC1']}),
    evidence(`${label}-E`, `One ${internal} history and ${eventFor(internal)}`, index, {status: internal, history: 1, receipts: 1, source: 'CARRIER_WEBHOOK', eventType: eventFor(internal), version: 1, ac: ['AC1']})];
})});

folders.push({name: '02 AC2 | Invalid signature never changes data', item: [
  callback('AC2-01', 'Wrong signature returns 401', 101, 'picked', {signature: 'intentionally-invalid-signature', test: problem(401, 'SHIPMENT_INVALID_SIGNATURE'), ac: ['AC2']}),
  callback('AC2-02', 'Missing signature returns 401', 101, 'picked', {signature: null, test: problem(401, 'SHIPMENT_INVALID_SIGNATURE'), ac: ['AC2']}),
  request('AC2-03', 'Bad signature is checked before malformed JSON', 'POST', '{{baseUrl}}/api/v1/webhooks/carriers/ghn', {raw: 'not-json', headers: [{key: 'X-Carrier-Signature', value: 'intentionally-invalid-signature'}], test: problem(401, 'SHIPMENT_INVALID_SIGNATURE'), ac: ['AC2']}),
  evidence('AC2-04-E', 'Unchanged status/version and zero effects', 101, {status: 'READY_TO_SHIP', version: 0, ac: ['AC2']})
]});

folders.push({name: '03 AC3 | Duplicate webhook accepted 200 exactly once', item: [
  callback('AC3-01', 'First delivery accepted', 102, 'picked', {ac: ['AC3']}),
  callback('AC3-02', 'Same provider delivery accepted again', 102, 'picked', {body: {Reason: 'AC3-01'}, ac: ['AC3']}),
  callback('AC3-03', 'Equivalent timestamp and changed Reason remain duplicate', 102, 'picked', {body: {Time: '2026-11-11T07:00:00+07:00', Reason: 'Formatting and human description changed'}, ac: ['AC3'], description: 'OrderCode, Type, raw Status and Time instant are unchanged. The changed offset and Reason must not create a second identity.'}),
  evidence('AC3-04-E', 'One status transition, one history/event/receipt', 102, {status: 'SHIPPED', history: 1, receipts: 1, source: 'CARRIER_WEBHOOK', eventType: 'ShipmentPickedUp', version: 1, ac: ['AC3']})
]});

folders.push({name: '04 AC4 | Backward transition rejected 409', item: [
  callback('AC4-01', 'DELIVERED -> IN_TRANSIT is rejected', 103, 'transporting', {test: problem(409, 'SHIPMENT_INVALID_TRANSITION'), ac: ['AC4']}),
  evidence('AC4-02-E', 'DELIVERED and version remain unchanged', 103, {status: 'DELIVERED', version: 0, ac: ['AC4']})
]});

folders.push({name: '05 AC5-AC7 | Real OrderCancelled Kafka consumer', description: 'The companion publishes real OrderCancelled messages. Evidence waits for consumer completion and reads the real database, outbound carrier calls and service log. AC6 requires a readable service log; a missing log must fail rather than pass.', item: [
  publish('AC5-01', 'OrderCancelled before pickup', 104, {ac: ['AC5']}),
  publish('AC5-02', 'Redeliver same cancellation event twice', 104, {duplicate: true, ac: ['AC5']}),
  evidence('AC5-03-E', 'Carrier cancelled once and one cancellation history/event', 104, {status: 'CANCELLED', history: 1, processed: 1, tombstones: 1, cancels: 1, source: 'SYSTEM', eventType: 'ShipmentCancelled', version: 1, ac: ['AC5']}),
  publish('AC6-01', 'OrderCancelled after pickup', 105, {ac: ['AC6']}),
  publish('AC6-02', 'Redelivery after pickup is idempotent', 105, {duplicate: true, ac: ['AC6']}),
  evidence('AC6-03-E', 'No shipment effects and exactly one Support WARN', 105, {status: 'SHIPPED', processed: 1, tombstones: 1, warnings: 1, version: 0, ac: ['AC6']}),
  publish('AC7-01', 'OrderCancelled with no shipment', 106, {ac: ['AC7']}),
  publish('AC7-02', 'No-shipment event redelivery', 106, {duplicate: true, ac: ['AC7']}),
  evidence('AC7-03-E', 'No error, one marker/tombstone, no shipment', 106, {status: null, processed: 1, tombstones: 1, ac: ['AC7']}),
  publish('CANCEL-NOBOOK-01', 'Cancel pending shipment without a carrier booking', 113),
  evidence('CANCEL-NOBOOK-02-E', 'Local cancellation without carrier call', 113, {status: 'CANCELLED', history: 1, processed: 1, tombstones: 1, cancels: 0, source: 'SYSTEM', eventType: 'ShipmentCancelled', version: 1})
]});

folders.push({name: '06 AC8 | R1 cancellation documentation and event route', item: [
  request('AC8-01', 'Flow 4 contains R1 rule without handover request', 'GET', '{{helperBaseUrl}}/__test/contract', {ac: ['AC8'], test: `${checkStatus(200)}
pm.test('R1 rule and service split documented', function () { const b = pm.response.json(); pm.expect(b.r1Rule).to.equal(true); pm.expect(b.routeInventory).to.equal(true); pm.expect(b.shipmentTopic).to.equal('nexus.shipment.events'); });`, description: 'Capture the JSON result and docs/api/events.md integration flow 4. The helper reads the repository document and contract checker; it does not infer approval or call a handover endpoint.'})
]});

const apiStatus = (id, title, caseId, status, token, {override = false, reason = 'Issue203 manual evidence', test, actor, ac = []} = {}) => request(id, title, override ? 'POST' : 'PATCH', `{{baseUrl}}/api/v1/shipments/${shipment(caseId)}/${override ? 'override' : 'status'}`, {token, actor, body: {status, reason}, ac, test: test ?? `${envelope()}
pm.test('Correct shipment target state', function () { const b = pm.response.json(); pm.expect(b.data.id).to.equal('${shipment(caseId)}'); pm.expect(b.data.status).to.equal('${status}'); pm.expect(b).not.to.have.property('page'); });`});
const forbidden = ['SHIPMENT_FORBIDDEN', 'FORBIDDEN'];
const unauthenticated = ['SHIPMENT_UNAUTHENTICATED', 'UNAUTHENTICATED'];
folders.push({name: '07 Manual status, override, tracking and access | Login required', description: 'Fill all four access tokens yourself. ADMIN/SUPPORT_STAFF require SHIPPING.UPDATE_STATUS, SHIPPING.MANUAL_OVERRIDE and SHIPPING.VIEW_SHIPMENT. The buyer must own the seeded buyerId; the other buyer must have a different sub. Missing tokens raise a visible precondition error, never skip a security test.', item: [
  apiStatus('AUTH-01', 'Anonymous cannot update status', 107, 'IN_TRANSIT', undefined, {test: problem(401, unauthenticated)}),
  apiStatus('AUTH-02', 'Buyer cannot update status', 107, 'IN_TRANSIT', 'accessTokenBuyer', {test: problem(403, forbidden)}),
  apiStatus('MANUAL-01', 'Support Staff updates status', 107, 'IN_TRANSIT', 'accessTokenSupport', {actor: 'supportActorId', reason: 'TC203 Support confirmed carrier progress'}),
  apiStatus('MANUAL-02', 'Admin can repeat current state without another effect', 107, 'IN_TRANSIT', 'accessTokenAdmin', {reason: 'Admin checked same carrier state'}),
  evidence('MANUAL-03-E', 'One MANUAL change with authenticated Support actor', 107, {status: 'IN_TRANSIT', history: 1, source: 'MANUAL', eventType: 'ShipmentInTransit', version: 1, reason: 'TC203 Support confirmed carrier progress', actor: 'supportActorId'}),
  request('OVERRIDE-01', 'Missing reason rejected', 'POST', `{{baseUrl}}/api/v1/shipments/${shipment(108)}/override`, {token: 'accessTokenAdmin', body: {status: 'OUT_FOR_DELIVERY'}, test: problem(400, 'SHIPMENT_VALIDATION_FAILED')}),
  apiStatus('OVERRIDE-02', 'Blank reason rejected', 108, 'OUT_FOR_DELIVERY', 'accessTokenAdmin', {override: true, reason: '   ', test: problem(400, 'SHIPMENT_VALIDATION_FAILED')}),
  apiStatus('OVERRIDE-03', 'Buyer cannot override', 108, 'OUT_FOR_DELIVERY', 'accessTokenBuyer', {override: true, test: problem(403, forbidden)}),
  apiStatus('OVERRIDE-04', 'Admin override with reason', 108, 'OUT_FOR_DELIVERY', 'accessTokenAdmin', {override: true, actor: 'adminActorId', reason: 'TC203 Admin verified delivery attempt'}),
  apiStatus('OVERRIDE-05', 'Support can repeat authorized override without another effect', 108, 'OUT_FOR_DELIVERY', 'accessTokenSupport', {override: true, reason: 'Support checked same delivery attempt'}),
  evidence('OVERRIDE-06-E', 'One override history/event and authenticated Admin reason', 108, {status: 'OUT_FOR_DELIVERY', history: 1, source: 'MANUAL', eventType: 'ShipmentStatusUpdated', version: 1, reason: 'TC203 Admin verified delivery attempt', actor: 'adminActorId'}),
  apiStatus('AC4-03', 'Admin PATCH cannot move DELIVERED backwards', 103, 'IN_TRANSIT', 'accessTokenAdmin', {test: problem(409, 'SHIPMENT_INVALID_TRANSITION'), ac: ['AC4']}),
  apiStatus('AC4-04', 'Support PATCH cannot move DELIVERED backwards', 103, 'IN_TRANSIT', 'accessTokenSupport', {test: problem(409, 'SHIPMENT_INVALID_TRANSITION'), ac: ['AC4']}),
  apiStatus('AC4-05', 'Override reason cannot bypass backward transition rule', 103, 'IN_TRANSIT', 'accessTokenAdmin', {override: true, test: problem(409, 'SHIPMENT_INVALID_TRANSITION'), ac: ['AC4']}),
  apiStatus('PENDING-01', 'Manual progress without tracking rejected', 111, 'PACKED', 'accessTokenSupport', {test: problem(409, 'SHIPMENT_INVALID_TRANSITION')}),
  evidence('PENDING-02-E', 'Pending tracking remains unchanged', 111, {status: 'PENDING_TRACKING', version: 0}),
  request('TRACK-01', 'Admin reads paged manual history', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(107)}/history?page=0&size=1`, {token: 'accessTokenAdmin', test: `${envelope()}
pm.test('Tracking array and sibling page', function () { const b = pm.response.json(); pm.expect(b.data).to.be.an('array').with.lengthOf(1); pm.expect(b.data[0].status).to.equal('IN_TRANSIT'); pm.expect(b.data[0].source).to.equal('MANUAL'); pm.expect(b.data[0].changedBy).to.equal(pm.variables.get('supportActorId')); pm.expect(b.page).to.deep.equal({number:0,size:1,totalElements:1,totalPages:1}); });`}),
  request('TRACK-02', 'Support reads tracking', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(108)}/history`, {token: 'accessTokenSupport', test: `${envelope()}
pm.test('Support sees override audit', function () { const b = pm.response.json(); pm.expect(b.data).to.have.lengthOf(1); pm.expect(b.data[0].description).to.equal('TC203 Admin verified delivery attempt'); pm.expect(b.page.totalElements).to.equal(1); });`}),
  request('TRACK-03', 'Owning buyer reads empty tracking', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(110)}/history`, {token: 'accessTokenBuyer', test: `${envelope()}
pm.test('Own shipment visible with empty history/page', function () { const b = pm.response.json(); pm.expect(b.data).to.deep.equal([]); pm.expect(b.page).to.deep.equal({number:0,size:20,totalElements:0,totalPages:0}); });`}),
  request('TRACK-04', 'Another buyer cannot see tracking', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(110)}/history`, {token: 'accessTokenOtherBuyer', test: problem(404, 'SHIPMENT_NOT_FOUND')}),
  request('TRACK-05', 'Invalid pagination rejected', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(110)}/history?page=-1&size=101`, {token: 'accessTokenAdmin', test: problem(400, 'SHIPMENT_INVALID_PARAMETER')}),
  request('TRACK-06', 'Beyond last page returns empty array and actual totals', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(107)}/history?page=5&size=1`, {token: 'accessTokenAdmin', test: `${envelope()}
pm.test('Beyond-end page contract', function () { const b = pm.response.json(); pm.expect(b.data).to.deep.equal([]); pm.expect(b.page).to.deep.equal({number:5,size:1,totalElements:1,totalPages:1}); });`}),
  request('TRACK-SUMMARY', 'Tracking summary keeps the develop FE contract', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(107)}/tracking`, {token: 'accessTokenBuyer', test: `${envelope()}
pm.test('Tracking summary contains persisted shipment and chronological history', function () { const d=pm.response.json().data; pm.expect(d).to.have.keys('shipmentId','orderId','carrier','trackingCode','status','history'); pm.expect(d.history).to.be.an('array'); pm.expect(d.status).to.equal('IN_TRANSIT'); });`}),
  request('TRACK-07', 'Tracking requires login', 'GET', `{{baseUrl}}/api/v1/shipments/${shipment(110)}/history`, {test: problem(401, unauthenticated)})
]});

folders.push({name: '08 Tracking poller | Repair a missed callback', description: 'The companion arms exactly one seeded fixture and changes the external mock GHN status; it never calls a production poll endpoint. Wait for the real scheduled TrackingPollJob, then assert source=POLLER and no webhook receipt.', item: [
  request('POLL-01', 'Arm external GHN transporting state and due poll', 'POST', '{{helperBaseUrl}}/__test/arm-poll', {body: {shipmentId: shipment(109), status: 'transporting'}, test: `${checkStatus(200)}
pm.test('Poll arm acknowledged', function () { pm.expect(pm.response.json()).to.be.an('object'); });`}),
  evidence('POLL-02-E', 'Scheduled poller writes one IN_TRANSIT history/event', 109, {status: 'IN_TRANSIT', history: 1, source: 'POLLER', eventType: 'ShipmentInTransit'}),
  callback('POLL-03', 'Unknown GHN status rejected without effects', 101, 'unknown_provider_state', {test: problem(422, 'SHIPMENT_INVALID_CARRIER_STATUS')}),
  callback('POLL-04', 'Wrong GHN shop rejected without effects', 101, 'picked', {body: {ShopID: 999999}, test: problem(400, 'SHIPMENT_INVALID_WEBHOOK')}),
  request('POLL-05', 'Malformed authenticated callback rejected', 'POST', '{{baseUrl}}/api/v1/webhooks/carriers/ghn', {raw: 'not-json', headers: [{key: 'X-Carrier-Signature', value: '{{webhookSecret}}'}], test: problem(400, 'SHIPMENT_INVALID_WEBHOOK')}),
  evidence('POLL-06-E', 'Rejected payloads preserve original fixture', 101, {status: 'READY_TO_SHIP', version: 0})
]});

folders.push({name: '09 Carrier cancellation failure | Login required', description: 'This supplementary rollback case calls the real manual status API with a Support Staff access token. The GHN stub rejects cancellation; no Kafka poison message is introduced. AC5, AC6 and AC7 independently exercise the real Kafka consumer in folder 05.', item: [
  apiStatus('CANCEL-FAIL-01', 'Carrier rejects manual cancellation', 112, 'CANCELLED', 'accessTokenSupport', {reason: 'TC203 carrier cancellation rollback', test: problem(409, 'SHIPMENT_CARRIER_CANCELLATION_REJECTED')}),
  evidence('CANCEL-FAIL-02-E', 'Failure commits no status/history/outbox/marker', 112, {status: 'READY_TO_SHIP', cancelMin: 1, version: 0})
]});

for (const folder of folders) {
  for (const item of folder.item) {
    if (item.request.body?.raw) item.request.body.raw = item.request.body.raw.replace('"ShopID": "{{shopId}}"', '"ShopID": {{shopId}}');
  }
}

const collection = {
  info: {
    _postman_id: '203c0000-0000-4000-8000-000000000001',
    name: 'Nexus #203 | Shipment status full AC evidence',
    description: 'Use the prepared issue203 runtime, import this collection and its four-token environment, paste your own login access tokens, then run folders 00-09 in order. All URLs, local carrier settings and fixture IDs are collection defaults. Buyer/actor IDs are extracted from JWTs automatically; no login request is automated. Seed resets only reserved issue203 fixture IDs. Evidence requests read actual shipment_db, Kafka and service logs through the companion. The external GHN provider is simulated locally; this is not a live GHN sandbox run. See ../README.md for AC-to-testcase and screenshot instructions.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'
  },
  auth: {type: 'noauth'},
  variable: [
    ['baseUrl', 'http://localhost:18088'],
    ['serviceBaseUrl', 'http://localhost:18088'],
    ['helperBaseUrl', 'http://127.0.0.1:9903'],
    ['shopId', '203'],
    ['webhookSecret', 'issue203-local-webhook-secret'],
    ['buyerId', '20320000-0000-4000-8000-000000000001'],
    ['adminActorId', ''],
    ['supportActorId', ''],
    ['otherBuyerId', ''],
    ['callbackTime', '2026-11-11T00:00:00Z']
  ].map(([key, value]) => ({key, value, type: 'string'})),
  event: [{listen: 'prerequest', script: {type: 'text/javascript', exec: exec(`(function () {
  const identities = [['accessTokenAdmin', 'adminActorId'], ['accessTokenSupport', 'supportActorId'], ['accessTokenBuyer', 'buyerId'], ['accessTokenOtherBuyer', 'otherBuyerId']];
  for (const pair of identities) {
    const token = pm.environment.get(pair[0]);
    if (!token || !token.trim()) {
      pm.collectionVariables.set(pair[1], pair[1] === 'buyerId' ? '20320000-0000-4000-8000-000000000001' : '');
      continue;
    }
    try {
      let encoded = token.trim().split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      encoded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=');
      const claims = JSON.parse(decodeURIComponent(atob(encoded).split('').map(function (character) { return '%' + ('00' + character.charCodeAt(0).toString(16)).slice(-2); }).join('')));
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(claims.sub)) throw new Error('Invalid subject');
      pm.collectionVariables.set(pair[1], claims.sub);
    } catch (error) {
      throw new Error(pair[0] + ' must contain the JWT access_token from your own login, with a UUID sub claim.');
    }
  }
  const otherBuyer = pm.variables.get('otherBuyerId');
  if (otherBuyer && otherBuyer === pm.variables.get('buyerId')) throw new Error('accessTokenOtherBuyer must belong to a different buyer.');
})();`)}}],
  item: folders
};

const environmentValues = [
  ['accessTokenAdmin', '', 'secret'],
  ['accessTokenSupport', '', 'secret'],
  ['accessTokenBuyer', '', 'secret'],
  ['accessTokenOtherBuyer', '', 'secret']
];
const environment = {
  id: '203c0000-0000-4000-8000-000000000002',
  name: 'Nexus #203 | Your four access tokens',
  values: environmentValues.map(([key, value, type]) => ({key, value, type, enabled: true})),
  _postman_variable_scope: 'environment',
  _postman_exported_using: 'Issue203 deterministic fixture builder'
};

for (const event of collection.event) new Script(event.script.exec.join('\n'), {filename: `collection-${event.listen}.js`});
for (const folder of collection.item) {
  for (const item of folder.item) {
    for (const event of item.event) new Script(event.script.exec.join('\n'), {filename: `${item.name}-${event.listen}.js`});
  }
}

mkdirSync(output, {recursive: true});
writeFileSync(join(output, 'issue203-shipment-status.postman_collection.json'), JSON.stringify(collection, null, 2) + '\n');
writeFileSync(join(output, 'issue203-local.postman_environment.json'), JSON.stringify(environment, null, 2) + '\n');
writeFileSync(join(output, 'testcase-index.json'), JSON.stringify({requestCount: cases.length, folders: folders.map((folder) => ({name: folder.name, requests: folder.item.map((item) => item.name)})), cases}, null, 2) + '\n');
console.log(`Generated ${cases.length} requests across ${folders.length} folders.`);
