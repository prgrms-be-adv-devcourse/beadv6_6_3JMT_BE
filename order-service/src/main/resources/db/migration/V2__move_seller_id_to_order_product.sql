ALTER TABLE order_product ADD COLUMN seller_id uuid;

UPDATE order_product op
SET seller_id = o.seller_id
FROM "order" o
WHERE op.order_id = o.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM order_product WHERE seller_id IS NULL) THEN
        RAISE EXCEPTION 'order_product seller_id backfill failed';
    END IF;
END $$;

ALTER TABLE order_product ALTER COLUMN seller_id SET NOT NULL;
CREATE INDEX idx_order_product_seller_created_at
    ON order_product USING btree (seller_id, created_at DESC);
DROP INDEX idx_order_seller_created_at;
ALTER TABLE "order" DROP COLUMN seller_id;
