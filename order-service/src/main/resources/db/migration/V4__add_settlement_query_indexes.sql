CREATE INDEX idx_order_product_order_id
    ON order_product USING btree (order_id);

CREATE INDEX idx_order_product_refunded_at
    ON order_product USING btree (refunded_at);
