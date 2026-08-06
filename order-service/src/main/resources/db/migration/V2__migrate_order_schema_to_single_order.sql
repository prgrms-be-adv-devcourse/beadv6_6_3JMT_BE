ALTER TABLE "order" DROP CONSTRAINT order_order_status_check;
ALTER TABLE order_product DROP CONSTRAINT order_product_order_product_status_check;

ALTER TABLE "order" ADD COLUMN completed_at timestamp(6) without time zone;

UPDATE order_product
SET order_product_status = 'FAILED'
WHERE order_product_status = 'CANCELED';

UPDATE "order" o
SET order_status = CASE
    WHEN o.order_status = 'PENDING' THEN 'CREATED'
    WHEN o.order_status IN ('FAILED', 'CANCELED') THEN 'FAILED'
    WHEN o.order_status IN ('PAID', 'REFUNDED')
        AND EXISTS (
            SELECT 1
            FROM order_product op
            WHERE op.order_id = o.id
        )
        AND NOT EXISTS (
            SELECT 1
            FROM order_product op
            WHERE op.order_id = o.id
              AND op.order_product_status <> 'REFUNDED'
        ) THEN 'ALL_REFUNDED'
    WHEN o.order_status IN ('PAID', 'REFUNDED')
        AND EXISTS (
            SELECT 1
            FROM order_product op
            WHERE op.order_id = o.id
              AND op.order_product_status = 'REFUNDED'
        ) THEN 'PARTIAL_REFUNDED'
    WHEN o.order_status = 'REFUNDED' THEN 'ALL_REFUNDED'
    ELSE 'COMPLETED'
END;

UPDATE "order"
SET completed_at = paid_at
WHERE order_status IN ('COMPLETED', 'PARTIAL_REFUNDED', 'ALL_REFUNDED');

UPDATE "order"
SET refunded_at = NULL
WHERE order_status <> 'ALL_REFUNDED';

ALTER TABLE "order"
    ADD CONSTRAINT order_order_status_check
        CHECK (order_status IN ('CREATED', 'COMPLETED', 'FAILED', 'PARTIAL_REFUNDED', 'ALL_REFUNDED'));

ALTER TABLE order_product
    ADD CONSTRAINT order_product_order_product_status_check
        CHECK (order_product_status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED'));

ALTER TABLE order_outbox_event RENAME COLUMN order_id TO aggregate_id;
ALTER INDEX idx_order_outbox_event_order_id RENAME TO idx_order_outbox_event_aggregate_id;

ALTER TABLE "order" DROP COLUMN canceled_at;
ALTER TABLE "order" DROP COLUMN paid_at;
ALTER TABLE "order" DROP COLUMN total_product_count;

ALTER TABLE order_product DROP COLUMN canceled_at;
ALTER TABLE order_product DROP COLUMN product_model_snapshot;
ALTER TABLE order_product DROP COLUMN product_type_snapshot;

CREATE INDEX idx_order_buyer_created_at
    ON "order" USING btree (buyer_id, created_at DESC);
CREATE INDEX idx_order_status_created_at
    ON "order" USING btree (order_status, created_at DESC);
CREATE INDEX idx_order_completed_at
    ON "order" USING btree (completed_at);
CREATE INDEX idx_order_refunded_at
    ON "order" USING btree (refunded_at);
CREATE INDEX idx_order_product_seller_created_at
    ON order_product USING btree (seller_id, created_at DESC);
