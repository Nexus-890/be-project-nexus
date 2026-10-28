-- Historical rows and non-JPA system writers use the fixed system actor.
-- Promotion tables already carry created_by; preserve those original authors.
DO $$
DECLARE
    business_table text;
BEGIN
    FOREACH business_table IN ARRAY ARRAY[
        'carts', 'cart_items', 'orders', 'order_items', 'order_status_history',
        'promo_mega_sale_events', 'promo_vouchers',
        'promo_event_sellers', 'promo_customer_vouchers', 'promo_voucher_products',
        'promo_voucher_sellers', 'promo_voucher_usages', 'promo_voucher_usage_allocations'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_by uuid', business_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN updated_by uuid', business_table);
        EXECUTE format(
            'UPDATE %I SET created_by = COALESCE(created_by, %L::uuid), updated_by = COALESCE(created_by, %L::uuid)',
            business_table,
            '00000000-0000-0000-0000-000000000000',
            '00000000-0000-0000-0000-000000000000'
        );
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN created_by SET DEFAULT %L::uuid, ALTER COLUMN created_by SET NOT NULL, ALTER COLUMN updated_by SET DEFAULT %L::uuid, ALTER COLUMN updated_by SET NOT NULL',
            business_table,
            '00000000-0000-0000-0000-000000000000',
            '00000000-0000-0000-0000-000000000000'
        );
    END LOOP;
END;
$$;
