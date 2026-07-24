-- 정산 V2 read model 전환용 파괴적 one-time reset이다.
-- 사용자·인증·판매자 등록 등 다른 user-service 데이터는 삭제하지 않는다.
DELETE FROM seller_settlement;

ALTER TABLE seller_settlement
    ADD COLUMN payload_version smallint NOT NULL,
    ADD CONSTRAINT seller_settlement_payload_version_check
        CHECK (payload_version IN (1, 2));

CREATE TABLE seller_settlement_detail (
    settlement_detail_id uuid NOT NULL,
    seller_settlement_id uuid NOT NULL,
    order_product_id uuid NOT NULL,
    line_type character varying(20) NOT NULL,
    line_amount numeric(12,2) NOT NULL,
    fee_rate numeric(5,4) NOT NULL,
    fee_amount numeric(12,2) NOT NULL,
    line_settlement_amount numeric(12,2) NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT seller_settlement_detail_pkey PRIMARY KEY (settlement_detail_id),
    CONSTRAINT seller_settlement_detail_line_type_check
        CHECK (line_type IN ('SALE', 'REFUND')),
    CONSTRAINT seller_settlement_detail_parent_fk
        FOREIGN KEY (seller_settlement_id)
        REFERENCES seller_settlement(seller_settlement_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_seller_settlement_seller_period
    ON seller_settlement(seller_id, period_start, period_end);

CREATE INDEX idx_seller_settlement_detail_parent_occurred
    ON seller_settlement_detail(seller_settlement_id, occurred_at);
