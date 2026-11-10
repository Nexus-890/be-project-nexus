ALTER TABLE inventory_movements ADD COLUMN reason varchar(500);
ALTER TABLE inventory_movements ADD CONSTRAINT inventory_movements_adjustment_check
    CHECK (movement_type <> 'ADJUSTMENT' OR (reason IS NOT NULL AND quantity <> 0));
