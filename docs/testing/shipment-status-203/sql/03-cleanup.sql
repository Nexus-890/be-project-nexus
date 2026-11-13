\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE issue203_fixture_ids ON COMMIT DROP AS
SELECT n,
       ('20300000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid AS shipment_id,
       ('20310000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid AS order_id
FROM (SELECT generate_series(1, 23) AS n UNION ALL SELECT generate_series(101, 113)) AS cases;

DELETE FROM carrier_webhook_receipts WHERE shipment_id IN (SELECT shipment_id FROM issue203_fixture_ids);
DELETE FROM shipment_history WHERE shipment_id IN (SELECT shipment_id FROM issue203_fixture_ids);
DELETE FROM outbox WHERE aggregate_type = 'Shipment' AND aggregate_id IN (SELECT shipment_id::text FROM issue203_fixture_ids);
DELETE FROM cancelled_shipment_orders WHERE order_id IN (SELECT order_id FROM issue203_fixture_ids);
DELETE FROM processed_events
WHERE consumer = 'shipment-order-cancelled'
  AND event_id IN (
      '203e0000-0000-4000-8000-000000000104'::uuid,
      '203e0000-0000-4000-8000-000000000105'::uuid,
      '203e0000-0000-4000-8000-000000000106'::uuid,
      '203e0000-0000-4000-8000-000000000112'::uuid,
      '203e0000-0000-4000-8000-000000000113'::uuid
  );
DELETE FROM shipments WHERE id IN (SELECT shipment_id FROM issue203_fixture_ids);

SELECT 'CLEANUP-203' AS evidence,
       (SELECT count(*) FROM shipments WHERE id IN (SELECT shipment_id FROM issue203_fixture_ids)) AS remaining_shipments,
       (SELECT count(*) FROM outbox WHERE aggregate_type = 'Shipment' AND aggregate_id IN (SELECT shipment_id::text FROM issue203_fixture_ids)) AS remaining_outbox,
       (SELECT count(*) FROM cancelled_shipment_orders WHERE order_id IN (SELECT order_id FROM issue203_fixture_ids)) AS remaining_tombstones;

COMMIT;
