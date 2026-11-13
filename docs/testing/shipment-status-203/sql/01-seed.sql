\set ON_ERROR_STOP on
\if :{?buyer_id}
\else
\set buyer_id '20320000-0000-4000-8000-000000000001'
\endif

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

CREATE TEMP TABLE issue203_seed (
    case_number integer PRIMARY KEY,
    initial_status shipment_status NOT NULL,
    tracking_code varchar(80)
) ON COMMIT DROP;

INSERT INTO issue203_seed(case_number, initial_status, tracking_code) VALUES
    (1, 'PENDING_TRACKING', 'S203-MAP-01'),
    (2, 'CREATED', 'S203-MAP-02'),
    (3, 'CREATED', 'S203-MAP-03'),
    (4, 'READY_TO_SHIP', 'S203-MAP-04'),
    (5, 'SHIPPED', 'S203-MAP-05'),
    (6, 'SHIPPED', 'S203-MAP-06'),
    (7, 'SHIPPED', 'S203-MAP-07'),
    (8, 'IN_TRANSIT', 'S203-MAP-08'),
    (9, 'IN_TRANSIT', 'S203-MAP-09'),
    (10, 'OUT_FOR_DELIVERY', 'S203-MAP-10'),
    (11, 'OUT_FOR_DELIVERY', 'S203-MAP-11'),
    (12, 'OUT_FOR_DELIVERY', 'S203-MAP-12'),
    (13, 'OUT_FOR_DELIVERY', 'S203-MAP-13'),
    (14, 'OUT_FOR_DELIVERY', 'S203-MAP-14'),
    (15, 'OUT_FOR_DELIVERY', 'S203-MAP-15'),
    (16, 'DELIVERY_FAILED', 'S203-MAP-16'),
    (17, 'DELIVERY_FAILED', 'S203-MAP-17'),
    (18, 'DELIVERY_FAILED', 'S203-MAP-18'),
    (19, 'DELIVERY_FAILED', 'S203-MAP-19'),
    (20, 'DELIVERY_FAILED', 'S203-MAP-20'),
    (21, 'DELIVERY_FAILED', 'S203-MAP-21'),
    (22, 'RETURNING', 'S203-MAP-22'),
    (23, 'CREATED', 'S203-MAP-23'),
    (101, 'READY_TO_SHIP', 'S203-BADSIG'),
    (102, 'READY_TO_SHIP', 'S203-DUP'),
    (103, 'DELIVERED', 'S203-BACK'),
    (104, 'READY_TO_SHIP', 'S203-CANCEL-PRE'),
    (105, 'SHIPPED', 'S203-CANCEL-POST'),
    (107, 'READY_TO_SHIP', 'S203-MANUAL'),
    (108, 'IN_TRANSIT', 'S203-OVERRIDE'),
    (109, 'SHIPPED', 'S203-POLL'),
    (110, 'CREATED', 'S203-OWNER'),
    (111, 'PENDING_TRACKING', NULL),
    (112, 'READY_TO_SHIP', 'S203-CANCEL-FAIL'),
    (113, 'PENDING_TRACKING', NULL);

INSERT INTO shipments (
    id, order_id, warehouse_id, carrier, tracking_code, shipment_status,
    service_type, shipping_fee, buyer_id, shipping_info, version,
    tracking_retry_count, next_tracking_retry_at, handed_over_at,
    last_carrier_update_at, next_status_poll_at, status_poll_failures
)
SELECT ids.shipment_id,
       ids.order_id,
       '20340000-0000-4000-8000-000000000001'::uuid,
       'GHN',
       seed.tracking_code,
       seed.initial_status,
       'STANDARD',
       30000.00,
       :'buyer_id'::uuid,
       jsonb_build_object(
           'carrier', 'GHN',
           'serviceType', 'STANDARD',
           'recipient', jsonb_build_object(
               'name', 'Issue 203 test buyer',
               'phone', '0900000203',
               'address', jsonb_build_object('line1', '203 Test Street', 'provinceCode', '1000001', 'wardCode', '1003646')
           ),
           'items', jsonb_build_array(jsonb_build_object(
               'skuId', '20350000-0000-4000-8000-000000000001',
               'productName', 'Issue 203 test parcel',
               'quantity', 1
           )),
           'parcel', jsonb_build_object('weightGrams', 1000, 'lengthCm', 20, 'widthCm', 15, 'heightCm', 10),
           'pickupAddress', jsonb_build_object('line1', '203 Test Warehouse', 'provinceCode', '1000001', 'wardCode', '1003646')
       ),
       0,
       0,
       CASE WHEN seed.tracking_code IS NULL THEN '2099-01-01T00:00:00Z'::timestamptz ELSE NULL END,
       CASE WHEN seed.initial_status IN ('SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNING', 'RETURNED')
            THEN '2026-01-01T00:00:00Z'::timestamptz ELSE NULL END,
       NULL,
       '2099-01-01T00:00:00Z'::timestamptz,
       0
FROM issue203_seed AS seed
JOIN issue203_fixture_ids AS ids ON ids.n = seed.case_number;

SELECT 'SEED-203' AS evidence,
       count(*) AS fixture_shipments,
       count(*) = 35 AS expected_35_shipments,
       bool_and(version = 0) AS zero_initial_versions,
       bool_and(next_status_poll_at = '2099-01-01T00:00:00Z'::timestamptz) AS poller_not_armed,
       count(*) FILTER (WHERE order_id = '20310000-0000-4000-8000-000000000106'::uuid) = 0 AS no_shipment_case_106
FROM shipments
WHERE id IN (SELECT shipment_id FROM issue203_fixture_ids);

SELECT 'SEED-203-BASELINE' AS evidence,
       (SELECT count(*) FROM shipment_history WHERE shipment_id IN (SELECT shipment_id FROM issue203_fixture_ids)) AS history_rows,
       (SELECT count(*) FROM outbox WHERE aggregate_type = 'Shipment' AND aggregate_id IN (SELECT shipment_id::text FROM issue203_fixture_ids)) AS outbox_rows,
       (SELECT count(*) FROM carrier_webhook_receipts WHERE shipment_id IN (SELECT shipment_id FROM issue203_fixture_ids)) AS webhook_receipts,
       (SELECT count(*) FROM cancelled_shipment_orders WHERE order_id IN (SELECT order_id FROM issue203_fixture_ids)) AS cancellation_tombstones;

COMMIT;
