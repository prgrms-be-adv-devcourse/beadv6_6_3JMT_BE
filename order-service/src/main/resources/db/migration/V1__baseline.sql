--
-- order-service baseline schema
--
-- 이 서비스가 실제로 소유하는 테이블만 정의한다. 스키마 접두사를 명시하지 않고
-- JDBC URL의 currentSchema(order_service)에 위임한다.
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';
SET default_table_access_method = heap;

CREATE TABLE cart (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    buyer_id uuid NOT NULL,
    total_amount integer NOT NULL
);

CREATE TABLE cart_product (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    added_at timestamp(6) without time zone NOT NULL,
    product_id uuid NOT NULL,
    cart_id uuid NOT NULL
);

CREATE TABLE "order" (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    buyer_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    order_number character varying(30) NOT NULL,
    order_status character varying(20) NOT NULL,
    completed_at timestamp(6) without time zone,
    refunded_at timestamp(6) without time zone,
    total_order_amount integer NOT NULL,
    CONSTRAINT order_order_status_check CHECK (((order_status)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('PARTIAL_REFUNDED'::character varying)::text, ('ALL_REFUNDED'::character varying)::text])))
);

CREATE TABLE order_outbox_event (
    event_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type character varying(100) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    retry_count integer NOT NULL,
    occurred_at timestamp(6) without time zone NOT NULL,
    published_at timestamp(6) without time zone,
    CONSTRAINT order_outbox_event_status_check CHECK (((status)::text = ANY (ARRAY['PENDING'::text, 'PUBLISHED'::text, 'FAILED'::text])))
);

CREATE TABLE order_processed_event (
    id bigint NOT NULL,
    event_id uuid NOT NULL,
    consumer_group character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    event_occurred_at timestamp(6) without time zone NOT NULL,
    processed_at timestamp(6) without time zone NOT NULL
);

ALTER TABLE order_processed_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME order_processed_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE order_product (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    downloaded boolean NOT NULL,
    order_product_status character varying(20) NOT NULL,
    product_amount_snapshot integer NOT NULL,
    product_id uuid NOT NULL,
    product_title_snapshot character varying(200) NOT NULL,
    refunded_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone NOT NULL,
    order_id uuid NOT NULL,
    CONSTRAINT order_product_order_product_status_check CHECK (((order_product_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PAID'::character varying)::text, ('FAILED'::character varying)::text, ('REFUNDED'::character varying)::text])))
);

ALTER TABLE ONLY cart
    ADD CONSTRAINT cart_pkey PRIMARY KEY (id);

ALTER TABLE ONLY cart_product
    ADD CONSTRAINT cart_product_pkey PRIMARY KEY (id);

ALTER TABLE ONLY "order"
    ADD CONSTRAINT order_pkey PRIMARY KEY (id);

ALTER TABLE ONLY "order"
    ADD CONSTRAINT uk4b9qgonn9wr1584ye6j5acwga UNIQUE (order_number);

ALTER TABLE ONLY order_outbox_event
    ADD CONSTRAINT order_outbox_event_pkey PRIMARY KEY (event_id);

ALTER TABLE ONLY order_processed_event
    ADD CONSTRAINT order_processed_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_processed_event
    ADD CONSTRAINT uk_order_processed_event_id_group UNIQUE (event_id, consumer_group);

ALTER TABLE ONLY order_product
    ADD CONSTRAINT order_product_pkey PRIMARY KEY (id);

CREATE INDEX idx_order_outbox_event_aggregate_id ON order_outbox_event USING btree (aggregate_id);

CREATE INDEX idx_order_outbox_event_status_occurred_at ON order_outbox_event USING btree (status, occurred_at);

CREATE INDEX idx_order_buyer_created_at ON "order" USING btree (buyer_id, created_at DESC);

CREATE INDEX idx_order_seller_created_at ON "order" USING btree (seller_id, created_at DESC);

CREATE INDEX idx_order_status_created_at ON "order" USING btree (order_status, created_at DESC);

CREATE INDEX idx_order_completed_at ON "order" USING btree (completed_at);

CREATE INDEX idx_order_refunded_at ON "order" USING btree (refunded_at);

CREATE INDEX idx_order_processed_event_event_type ON order_processed_event USING btree (event_type);

CREATE INDEX idx_order_processed_event_processed_at ON order_processed_event USING btree (processed_at);

ALTER TABLE ONLY cart_product
    ADD CONSTRAINT fklv5x4iresnv4xspvomrwd8ej9 FOREIGN KEY (cart_id) REFERENCES cart(id);

ALTER TABLE ONLY order_product
    ADD CONSTRAINT fkq5kokaug6qg2dtttgdw7kiris FOREIGN KEY (order_id) REFERENCES "order"(id);
