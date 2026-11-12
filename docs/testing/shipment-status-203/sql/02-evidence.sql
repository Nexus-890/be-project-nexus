\set ON_ERROR_STOP on
\pset pager off

BEGIN READ ONLY;

WITH expected(case_number, evidence, ac, provider_status, expected_status, expected_history, expected_event, expected_source, expected_receipts) AS (
    VALUES
        (1, 'MAP-01', 'AC-01', 'ready_to_pick', 'CREATED', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (2, 'MAP-02', 'AC-01', 'picking', 'PICKING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (3, 'MAP-03', 'AC-01', 'money_collect_picking', 'PICKING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (4, 'MAP-04', 'AC-01', 'picked', 'SHIPPED', 1, 'ShipmentPickedUp', 'CARRIER_WEBHOOK', 1),
        (5, 'MAP-05', 'AC-01', 'storing', 'IN_TRANSIT', 1, 'ShipmentInTransit', 'CARRIER_WEBHOOK', 1),
        (6, 'MAP-06', 'AC-01', 'sorting', 'IN_TRANSIT', 1, 'ShipmentInTransit', 'CARRIER_WEBHOOK', 1),
        (7, 'MAP-07', 'AC-01', 'transporting', 'IN_TRANSIT', 1, 'ShipmentInTransit', 'CARRIER_WEBHOOK', 1),
        (8, 'MAP-08', 'AC-01', 'delivering', 'OUT_FOR_DELIVERY', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (9, 'MAP-09', 'AC-01', 'money_collect_delivering', 'OUT_FOR_DELIVERY', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (10, 'MAP-10', 'AC-01', 'delivered', 'DELIVERED', 1, 'ShipmentDelivered', 'CARRIER_WEBHOOK', 1),
        (11, 'MAP-11', 'AC-01', 'delivery_fail', 'DELIVERY_FAILED', 1, 'ShipmentFailed', 'CARRIER_WEBHOOK', 1),
        (12, 'MAP-12', 'AC-01', 'exception', 'DELIVERY_FAILED', 1, 'ShipmentFailed', 'CARRIER_WEBHOOK', 1),
        (13, 'MAP-13', 'AC-01', 'lost', 'DELIVERY_FAILED', 1, 'ShipmentFailed', 'CARRIER_WEBHOOK', 1),
        (14, 'MAP-14', 'AC-01', 'damage', 'DELIVERY_FAILED', 1, 'ShipmentFailed', 'CARRIER_WEBHOOK', 1),
        (15, 'MAP-15', 'AC-01', 'scrap', 'DELIVERY_FAILED', 1, 'ShipmentFailed', 'CARRIER_WEBHOOK', 1),
        (16, 'MAP-16', 'AC-01', 'waiting_to_return', 'RETURNING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (17, 'MAP-17', 'AC-01', 'return', 'RETURNING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (18, 'MAP-18', 'AC-01', 'return_transporting', 'RETURNING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (19, 'MAP-19', 'AC-01', 'return_sorting', 'RETURNING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (20, 'MAP-20', 'AC-01', 'returning', 'RETURNING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (21, 'MAP-21', 'AC-01', 'return_fail', 'RETURNING', 1, 'ShipmentStatusUpdated', 'CARRIER_WEBHOOK', 1),
        (22, 'MAP-22', 'AC-01', 'returned', 'RETURNED', 1, 'ShipmentReturned', 'CARRIER_WEBHOOK', 1),
        (23, 'MAP-23', 'AC-01', 'cancel', 'CANCELLED', 1, 'ShipmentCancelled', 'CARRIER_WEBHOOK', 1),
        (101, 'BAD-SIGNATURE', 'AC-02', NULL, 'READY_TO_SHIP', 0, NULL, NULL, 0),
        (102, 'DUPLICATE', 'AC-03', 'picked', 'SHIPPED', 1, 'ShipmentPickedUp', 'CARRIER_WEBHOOK', 1),
        (103, 'BACKWARD', 'AC-04', NULL, 'DELIVERED', 0, NULL, NULL, 0),
        (104, 'CANCEL-BEFORE-PICKUP', 'AC-05', NULL, 'CANCELLED', 1, 'ShipmentCancelled', 'SYSTEM', 0),
        (105, 'CANCEL-AFTER-PICKUP', 'AC-06', NULL, 'SHIPPED', 0, NULL, NULL, 0),
        (106, 'CANCEL-NO-SHIPMENT', 'AC-07', NULL, NULL, 0, NULL, NULL, 0),
        (107, 'MANUAL-UPDATE', 'OUTPUT-STATUS', NULL, 'IN_TRANSIT', 1, 'ShipmentInTransit', 'MANUAL', 0),
        (108, 'MANUAL-OVERRIDE', 'OUTPUT-OVERRIDE', NULL, 'OUT_FOR_DELIVERY', 1, 'ShipmentStatusUpdated', 'MANUAL', 0),
        (109, 'TRACKING-POLLER', 'OUTPUT-POLLER', NULL, 'IN_TRANSIT', 1, 'ShipmentInTransit', 'POLLER', 0),
        (110, 'OWNERSHIP', 'OUTPUT-TRACKING', NULL, 'CREATED', 0, NULL, NULL, 0),
        (111, 'PENDING-TRACKING-GUARD', 'REGRESSION', NULL, 'PENDING_TRACKING', 0, NULL, NULL, 0),
        (112, 'CANCEL-CARRIER-FAILURE', 'REGRESSION', NULL, 'READY_TO_SHIP', 0, NULL, NULL, 0),
        (113, 'CANCEL-NO-BOOKING', 'REGRESSION', NULL, 'CANCELLED', 1, 'ShipmentCancelled', 'SYSTEM', 0)
), fixtures AS (
    SELECT expected.*,
           ('20300000-0000-4000-8000-' || lpad(case_number::text, 12, '0'))::uuid AS shipment_id,
           ('20310000-0000-4000-8000-' || lpad(case_number::text, 12, '0'))::uuid AS order_id
    FROM expected
), facts AS (
    SELECT f.*,
           s.shipment_status::text AS actual_status,
           s.version AS actual_version,
           s.handed_over_at,
           (SELECT count(*) FROM shipment_history h WHERE h.shipment_id = f.shipment_id) AS history_rows,
           (SELECT count(*) FROM outbox o WHERE o.aggregate_type = 'Shipment' AND o.aggregate_id = f.shipment_id::text) AS event_rows,
           (SELECT count(*) FROM carrier_webhook_receipts r WHERE r.shipment_id = f.shipment_id) AS receipt_rows,
           (SELECT count(*) FROM outbox o WHERE o.aggregate_type = 'Shipment' AND o.aggregate_id = f.shipment_id::text AND o.published_at IS NOT NULL) AS published_event_rows,
           NOT EXISTS (
               SELECT 1 FROM outbox o
               LEFT JOIN shipment_history h ON h.id::text = o.payload #>> '{payload,historyId}' AND h.shipment_id = f.shipment_id
               WHERE o.aggregate_type = 'Shipment' AND o.aggregate_id = f.shipment_id::text
                 AND (o.event_type IS DISTINCT FROM f.expected_event
                   OR o.topic IS DISTINCT FROM 'nexus.shipment.events'
                   OR o.payload ->> 'eventType' IS DISTINCT FROM f.expected_event
                   OR o.payload ->> 'aggregateId' IS DISTINCT FROM f.shipment_id::text
                   OR o.payload #>> '{payload,orderId}' IS DISTINCT FROM f.order_id::text
                   OR o.payload #>> '{payload,shipmentId}' IS DISTINCT FROM f.shipment_id::text
                   OR o.payload #>> '{payload,status}' IS DISTINCT FROM f.expected_status
                   OR o.payload #>> '{payload,source}' IS DISTINCT FROM f.expected_source
                   OR COALESCE(NOT ((o.payload #>> '{payload,shipmentVersion}')::bigint > 0
                       AND (o.payload #>> '{payload,shipmentVersion}')::bigint <= s.version), true)
                   OR o.payload #>> '{payload,actorId}' IS DISTINCT FROM h.changed_by::text
                   OR h.id IS NULL
                   OR h.shipment_status::text IS DISTINCT FROM f.expected_status
                   OR h.source IS DISTINCT FROM f.expected_source)
           ) AS event_history_contract_ok
    FROM fixtures f
    LEFT JOIN shipments s ON s.id = f.shipment_id
)
SELECT evidence,
       ac,
       provider_status,
       expected_status,
       actual_status,
       actual_version,
       history_rows,
       event_rows,
       receipt_rows,
       published_event_rows,
       event_history_contract_ok,
       actual_status IS NOT DISTINCT FROM expected_status
           AND CASE WHEN case_number = 106 THEN actual_version IS NULL
                    WHEN case_number = 109 THEN actual_version >= 1
                    ELSE actual_version = expected_history END
           AND history_rows = expected_history
           AND event_rows = expected_history
           AND receipt_rows = expected_receipts
           AND event_history_contract_ok AS case_pass
FROM facts
ORDER BY case_number;

WITH expected(case_number, evidence, expected_processed, expected_tombstone) AS (
    VALUES
        (104, 'AC-05-CANCEL-BEFORE-PICKUP', 1, 1),
        (105, 'AC-06-CANCEL-AFTER-PICKUP', 1, 1),
        (106, 'AC-07-CANCEL-NO-SHIPMENT', 1, 1),
        (112, 'CANCEL-CARRIER-FAILURE', 0, 0),
        (113, 'CANCEL-NO-BOOKING', 1, 1)
), facts AS (
    SELECT e.*,
           (SELECT count(*) FROM processed_events p
            WHERE p.consumer = 'shipment-order-cancelled'
              AND p.event_id = ('203e0000-0000-4000-8000-' || lpad(e.case_number::text, 12, '0'))::uuid) AS processed_rows,
           (SELECT count(*) FROM cancelled_shipment_orders c
            WHERE c.order_id = ('20310000-0000-4000-8000-' || lpad(e.case_number::text, 12, '0'))::uuid) AS tombstone_rows
    FROM expected e
)
SELECT evidence,
       processed_rows,
       tombstone_rows,
       processed_rows = expected_processed AND tombstone_rows = expected_tombstone AS consumer_pass
FROM facts
ORDER BY case_number;

SELECT 'EVENT-HISTORY-DETAIL' AS evidence,
       s.tracking_code,
       s.id AS shipment_id,
       s.order_id,
       h.id AS history_id,
       h.shipment_status AS history_status,
       h.source,
       h.changed_by,
       h.carrier_event_id,
       h.description,
       o.id AS event_id,
       o.event_type,
       o.topic,
       o.payload #>> '{payload,shipmentVersion}' AS event_version,
       o.published_at
FROM shipments s
JOIN shipment_history h ON h.shipment_id = s.id
LEFT JOIN outbox o ON o.aggregate_type = 'Shipment'
    AND o.aggregate_id = s.id::text
    AND o.payload #>> '{payload,historyId}' = h.id::text
WHERE s.id IN (
    SELECT ('20300000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid
    FROM (SELECT generate_series(1, 23) AS n UNION ALL SELECT generate_series(101, 113)) AS cases
)
ORDER BY s.id, h.created_at, h.id;

SELECT 'AC-07-NO-SHIPMENT' AS evidence,
       NOT EXISTS (SELECT 1 FROM shipments WHERE order_id = '20310000-0000-4000-8000-000000000106'::uuid) AS shipment_absent,
       EXISTS (SELECT 1 FROM processed_events WHERE event_id = '203e0000-0000-4000-8000-000000000106'::uuid AND consumer = 'shipment-order-cancelled') AS consumer_completed,
       EXISTS (SELECT 1 FROM cancelled_shipment_orders WHERE order_id = '20310000-0000-4000-8000-000000000106'::uuid) AS cancellation_remembered;

COMMIT;
