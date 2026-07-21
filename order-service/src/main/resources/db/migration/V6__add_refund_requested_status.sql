ALTER TABLE "order"
    DROP CONSTRAINT order_order_status_check;

ALTER TABLE "order"
    ADD CONSTRAINT order_order_status_check
        CHECK (order_status IN ('CREATED', 'COMPLETED', 'FAILED', 'REFUND_REQUESTED', 'PARTIAL_REFUNDED', 'ALL_REFUNDED'));

ALTER TABLE order_product
    DROP CONSTRAINT order_product_order_product_status_check;

ALTER TABLE order_product
    ADD CONSTRAINT order_product_order_product_status_check
        CHECK (order_product_status IN ('PENDING', 'PAID', 'FAILED', 'REFUND_REQUESTED', 'REFUNDED'));
