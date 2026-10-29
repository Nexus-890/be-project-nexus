CREATE TYPE warehouse_status AS ENUM ('ACTIVE', 'INACTIVE', 'MAINTENANCE');
CREATE TYPE inventory_reservation_status AS ENUM ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED');
CREATE TYPE inventory_movement_type AS ENUM (
    'IMPORT', 'EXPORT', 'RESERVE', 'RELEASE', 'SALE', 'RETURN', 'ADJUSTMENT', 'TRANSFER'
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
