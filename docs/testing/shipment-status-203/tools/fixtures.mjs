export const mappings = [
  ['ready_to_pick', 'CREATED', 'PENDING_TRACKING'],
  ['picking', 'PICKING', 'CREATED'],
  ['money_collect_picking', 'PICKING', 'CREATED'],
  ['picked', 'SHIPPED', 'READY_TO_SHIP'],
  ['storing', 'IN_TRANSIT', 'SHIPPED'],
  ['sorting', 'IN_TRANSIT', 'SHIPPED'],
  ['transporting', 'IN_TRANSIT', 'SHIPPED'],
  ['delivering', 'OUT_FOR_DELIVERY', 'IN_TRANSIT'],
  ['money_collect_delivering', 'OUT_FOR_DELIVERY', 'IN_TRANSIT'],
  ['delivered', 'DELIVERED', 'OUT_FOR_DELIVERY'],
  ['delivery_fail', 'DELIVERY_FAILED', 'OUT_FOR_DELIVERY'],
  ['exception', 'DELIVERY_FAILED', 'OUT_FOR_DELIVERY'],
  ['lost', 'DELIVERY_FAILED', 'OUT_FOR_DELIVERY'],
  ['damage', 'DELIVERY_FAILED', 'OUT_FOR_DELIVERY'],
  ['scrap', 'DELIVERY_FAILED', 'OUT_FOR_DELIVERY'],
  ['waiting_to_return', 'RETURNING', 'DELIVERY_FAILED'],
  ['return', 'RETURNING', 'DELIVERY_FAILED'],
  ['return_transporting', 'RETURNING', 'DELIVERY_FAILED'],
  ['return_sorting', 'RETURNING', 'DELIVERY_FAILED'],
  ['returning', 'RETURNING', 'DELIVERY_FAILED'],
  ['return_fail', 'RETURNING', 'DELIVERY_FAILED'],
  ['returned', 'RETURNED', 'RETURNING'],
  ['cancel', 'CANCELLED', 'CREATED']
];

export function fixtureId(prefix, id) {
  return `${prefix}-0000-4000-8000-${String(id).padStart(12, '0')}`;
}

const extra = [
  [101, 'BADSIG', 'READY_TO_SHIP', 'READY_TO_SHIP', 0],
  [102, 'DUP', 'READY_TO_SHIP', 'SHIPPED', 1],
  [103, 'BACK', 'DELIVERED', 'DELIVERED', 0],
  [104, 'CANCEL-PRE', 'READY_TO_SHIP', 'CANCELLED', 1],
  [105, 'CANCEL-POST', 'SHIPPED', 'SHIPPED', 0],
  [106, 'NO-SHIPMENT', null, null, 0],
  [107, 'MANUAL', 'READY_TO_SHIP', 'IN_TRANSIT', 1],
  [108, 'OVERRIDE', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 1],
  [109, 'POLL', 'SHIPPED', 'IN_TRANSIT', 1],
  [110, 'OWNER', 'CREATED', 'CREATED', 0],
  [111, 'NO-TRACKING', 'PENDING_TRACKING', 'PENDING_TRACKING', 0],
  [112, 'CANCEL-FAIL', 'READY_TO_SHIP', 'READY_TO_SHIP', 0],
  [113, 'CANCEL-NO-BOOKING', 'PENDING_TRACKING', 'CANCELLED', 1]
];

export const fixtures = new Map([
  ...mappings.map(([carrierStatus, expectedStatus, initialStatus], index) => ({
    caseId: index + 1, carrierStatus, expectedStatus, initialStatus,
    trackingCode: `S203-MAP-${String(index + 1).padStart(2, '0')}`, changes: 1
  })),
  ...extra.map(([caseId, name, initialStatus, expectedStatus, changes]) => ({
    caseId, initialStatus, expectedStatus, changes,
    trackingCode: [106, 111, 113].includes(caseId) ? null : `S203-${name}`
  }))
].map(entry => [entry.caseId, {
  ...entry,
  shipmentId: fixtureId('20300000', entry.caseId),
  orderId: fixtureId('20310000', entry.caseId),
  eventId: fixtureId('203e0000', entry.caseId)
}]));
