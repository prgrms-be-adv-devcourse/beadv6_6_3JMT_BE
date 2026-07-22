--
-- product-service V3
--
-- ES 색인 파이프라인(#376) 선행 작업: admin-service가 상품 ON_SALE 전환 시
-- 안전하게 이벤트를 발행할 수 있도록 아웃박스 테이블을 product_service 스키마에 둔다.
-- admin-service는 ddl-auto=none이라 스키마를 만들 수 없어, 테이블 소유는 product-service가 갖는다.
-- (docs/superpowers/specs/2026-07-21-admin-product-onsale-event-design.md)
--

CREATE TABLE product_outbox_event (
    event_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    retry_count integer NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    published_at timestamp(6) without time zone,
    CONSTRAINT product_outbox_event_pkey PRIMARY KEY (event_id),
    CONSTRAINT product_outbox_event_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text])))
);

CREATE INDEX idx_product_outbox_event_status_occurred_at ON product_outbox_event USING btree (status, occurred_at);
CREATE INDEX idx_product_outbox_event_aggregate_id ON product_outbox_event USING btree (aggregate_id);
