ALTER TABLE warehouses
    ADD COLUMN province_code varchar(9),
    ADD COLUMN ward_code varchar(9),
    ADD CONSTRAINT warehouses_pickup_codes_check CHECK ((province_code IS NULL) = (ward_code IS NULL));
