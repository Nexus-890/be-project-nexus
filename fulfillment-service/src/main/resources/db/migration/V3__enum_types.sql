CREATE TYPE warehouse_status AS ENUM ('ACTIVE', 'INACTIVE', 'MAINTENANCE');
CREATE TYPE inventory_reservation_status AS ENUM ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED');
CREATE TYPE inventory_movement_type AS ENUM (
    'IMPORT', 'EXPORT', 'RESERVE', 'RELEASE', 'SALE', 'RETURN', 'ADJUSTMENT', 'TRANSFER'
);
CREATE TYPE shipment_status AS ENUM (
    'CREATED', 'PICKING', 'PACKED', 'READY_TO_SHIP', 'SHIPPED', 'IN_TRANSIT',
    'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNING', 'RETURNED', 'CANCELLED'
);

ALTER TABLE warehouses DROP CONSTRAINT warehouses_status_check, ALTER COLUMN status DROP DEFAULT;
ALTER TABLE warehouses ALTER COLUMN status TYPE warehouse_status USING status::warehouse_status;
ALTER TABLE warehouses ALTER COLUMN status SET DEFAULT 'ACTIVE'::warehouse_status;
ALTER TABLE inventory_reservations DROP CONSTRAINT inventory_reservations_status_check,
    ALTER COLUMN status DROP DEFAULT;
ALTER TABLE inventory_reservations
    ALTER COLUMN status TYPE inventory_reservation_status USING status::inventory_reservation_status;
ALTER TABLE inventory_reservations ALTER COLUMN status SET DEFAULT 'HELD'::inventory_reservation_status;
ALTER TABLE inventory_movements DROP CONSTRAINT inventory_movements_movement_type_check;
ALTER TABLE inventory_movements
    ALTER COLUMN movement_type TYPE inventory_movement_type USING movement_type::inventory_movement_type;
ALTER TABLE shipments DROP CONSTRAINT shipments_shipment_status_check,
    ALTER COLUMN shipment_status DROP DEFAULT;
ALTER TABLE shipments
    ALTER COLUMN shipment_status TYPE shipment_status USING shipment_status::shipment_status;
ALTER TABLE shipments ALTER COLUMN shipment_status SET DEFAULT 'CREATED'::shipment_status;
ALTER TABLE shipment_history
    ALTER COLUMN shipment_status TYPE shipment_status USING shipment_status::shipment_status;
