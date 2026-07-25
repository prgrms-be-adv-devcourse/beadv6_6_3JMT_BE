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

UPDATE order_product op
SET buyer_id = o.buyer_id
FROM "order" o
WHERE op.order_id = o.id
  AND op.buyer_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_product
        WHERE buyer_id IS NOT NULL
          AND order_product_status = 'PENDING'
        GROUP BY buyer_id, product_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'duplicate PENDING order products exist for buyer_id and product_id';
    END IF;
END
$$;

CREATE UNIQUE INDEX uk_order_product_buyer_product_pending
    ON order_product (buyer_id, product_id)
    WHERE buyer_id IS NOT NULL
      AND order_product_status = 'PENDING';
