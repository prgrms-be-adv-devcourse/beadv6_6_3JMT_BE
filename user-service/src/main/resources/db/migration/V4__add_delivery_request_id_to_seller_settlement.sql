ALTER TABLE seller_settlement
    ADD COLUMN delivery_request_id UUID NULL;

CREATE UNIQUE INDEX uk_seller_settlement_delivery_request_id
    ON seller_settlement (delivery_request_id)
    WHERE delivery_request_id IS NOT NULL;

ALTER TABLE seller_settlement_detail
    ADD COLUMN settlement_source_line_id UUID NULL;
