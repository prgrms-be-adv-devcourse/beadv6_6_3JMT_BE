ALTER TABLE order_product
    ADD COLUMN IF NOT EXISTS buyer_id uuid;

DO $$
DECLARE
    actual_type text;
BEGIN
    SELECT data_type
    INTO actual_type
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'order_product'
      AND column_name = 'buyer_id';

    IF actual_type IS DISTINCT FROM 'uuid' THEN
        RAISE EXCEPTION 'order_product.buyer_id must be uuid, actual type: %', actual_type;
    END IF;
END
$$;

CREATE UNIQUE INDEX uk_order_product_buyer_product_pending
    ON order_product (buyer_id, product_id)
    WHERE buyer_id IS NOT NULL
      AND order_product_status = 'PENDING';
