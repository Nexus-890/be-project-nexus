ALTER TYPE inventory_movement_type ADD VALUE IF NOT EXISTS 'PICK';
ALTER TYPE inventory_movement_type ADD VALUE IF NOT EXISTS 'SHIP';

ALTER TABLE inventory_reservations
    ADD COLUMN picked_at  timestamptz,
    ADD COLUMN picked_by  uuid,
    ADD COLUMN shipped_at timestamptz,
    ADD COLUMN shipped_by uuid,
    ADD CONSTRAINT inventory_reservations_pick_needs_confirm
        CHECK (picked_at IS NULL OR status IN ('CONFIRMED', 'RELEASED')),
    ADD CONSTRAINT inventory_reservations_ship_after_pick
        CHECK (shipped_at IS NULL OR picked_at IS NOT NULL AND shipped_at >= picked_at);
